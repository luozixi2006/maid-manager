"""Reproducible local-only embedding asset build; no user data is involved.
pip: torch==2.6.0 transformers==4.46.3 onnx==1.17.0 onnxruntime==1.20.1 numpy==1.26.4
"""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT.parent / "embedding-cache"
os.environ["HF_HOME"] = str(CACHE / "hf")
os.environ["HUGGINGFACE_HUB_CACHE"] = str(CACHE / "hf/hub")
os.environ["TRANSFORMERS_CACHE"] = str(CACHE / "transformers")
os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
os.environ["HF_HUB_DISABLE_XET"] = "1"

import hashlib
import json
import shutil
import numpy as np
import torch
import onnxruntime as ort
from huggingface_hub import snapshot_download
from transformers import AutoModel, AutoTokenizer
from onnxruntime.quantization import quantize_dynamic, QuantType

MODEL = "BAAI/bge-small-zh-v1.5"
REVISION = "7999e1d3359715c523056ef9478215996d62a620"
source = Path(snapshot_download(MODEL, revision=REVISION,
    allow_patterns=["model.safetensors", "config.json", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json", "vocab.txt", "README.md"]))
tokenizer = AutoTokenizer.from_pretrained(source, local_files_only=True)
encoder = AutoModel.from_pretrained(source, local_files_only=True, use_safetensors=True, attn_implementation="eager").eval()
torch.set_num_threads(2)

# Register the original encoder as a submodule so export treats parameters as weights.
class ExportModel(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.model = encoder
    def forward(self, input_ids, attention_mask, token_type_ids):
        output = self.model(input_ids=input_ids, attention_mask=attention_mask, token_type_ids=token_type_ids)[0][:, 0]
        return torch.nn.functional.normalize(output, p=2, dim=1)

assets = ROOT / "app/src/main/assets/memory"
assets.mkdir(parents=True, exist_ok=True)
full = CACHE / "bge-full.onnx"
sample = tokenizer("用户喜欢无糖咖啡。", return_tensors="pt", max_length=256, truncation=True)
names = ["input_ids", "attention_mask", "token_type_ids"]
torch.onnx.export(ExportModel().eval(), tuple(sample[n] for n in names), str(full), input_names=names,
    output_names=["embedding"], dynamic_axes={**{n:{0:"batch",1:"sequence"} for n in names},"embedding":{0:"batch"}},
    opset_version=17, do_constant_folding=True)
quantized = assets / "bge-small-zh-v1.5-int8.onnx"
quantize_dynamic(str(full), str(quantized), weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"])
shutil.copyfile(source / "vocab.txt", assets / "vocab.txt")
session = ort.InferenceSession(str(quantized), providers=["CPUExecutionProvider"])
examples = ["为这个句子生成表示以用于检索相关文章：我平时喜欢喝什么？", "用户喜欢喝不加糖的咖啡。", "用户正在开发手表项目。", "为这个句子生成表示以用于检索相关文章：手表项目做到哪了？"]
batch = tokenizer(examples, padding=True, truncation=True, max_length=256, return_tensors="np")
vectors = session.run(None, {n:batch[n].astype(np.int64) for n in names})[0]
assert vectors.shape == (4,512)
assert np.all(np.isfinite(vectors))
assert float(vectors[0] @ vectors[1]) > float(vectors[0] @ vectors[2])
assert float(vectors[3] @ vectors[2]) > float(vectors[3] @ vectors[1])
metadata={"source":MODEL,"revision":REVISION,"license":"MIT","pooling":"CLS + L2 normalize","quantization":"ONNX Runtime dynamic MatMul int8","max_tokens":256,
    "sha256":hashlib.sha256(quantized.read_bytes()).hexdigest(),"bytes":quantized.stat().st_size,
    "validation":{"drink_related":float(vectors[0] @ vectors[1]),"drink_unrelated":float(vectors[0] @ vectors[2]),"project_related":float(vectors[3] @ vectors[2])}}
(assets / "provenance.json").write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding="utf-8")
# Fixtures verify Android WordPiece and numerical inference against the official tokenizer.
fixtures=[{"text":text,"ids":tokenizer(text,truncation=True,max_length=256)["input_ids"]} for text in examples+["Hello Café! OPPO Watch 3，型号 OWW211。","我讨厌辣"]]
(assets / "tokenizer-fixtures.json").write_text(json.dumps(fixtures,ensure_ascii=False),encoding="utf-8")
print(json.dumps(metadata,ensure_ascii=False))
