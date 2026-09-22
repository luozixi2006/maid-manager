package com.miniichat.memory

import android.content.Context
import ai.onnxruntime.*
import java.io.File
import java.security.MessageDigest

/** Official BAAI weights, locally exported and verified. All text stays on this phone. */
object LocalEmbedding {
    const val MODEL="bge-small-zh-v1.5-int8-7999e1d-cls-v1"
    private const val HASH="cbf2a7597465573f5316bb44be0929cc4cb5aadaadf4c612d7eb9e6edc9a6ae7"
    private var session:OrtSession?=null
    private var tokenizer:WordPiece?=null
    val status=kotlinx.coroutines.flow.MutableStateFlow("本地语义检索尚未启动")
    @Synchronized fun embed(context:Context,text:String,query:Boolean=false):List<Float> {
        if(text.isBlank())return emptyList()
        try {
            val env=OrtEnvironment.getEnvironment()
            if(session==null) {
                val directory=File(context.filesDir,"memory-model").apply{mkdirs()}
                val file=File(directory,"$MODEL.onnx")
                if(!file.isFile) {
                    val temp=File(directory,"$MODEL.part")
                    context.assets.open("memory/bge-small-zh-v1.5-int8.onnx").use{input->temp.outputStream().use{input.copyTo(it)}}
                    check(sha(temp)==HASH){"本地向量模型校验失败"}
                    check(temp.renameTo(file)){"本地向量模型保存失败"}
                }
                check(sha(file)==HASH){"本地向量模型校验失败"}
                tokenizer=WordPiece(context.assets.open("memory/vocab.txt").bufferedReader().use{it.readLines()})
                OrtSession.SessionOptions().use{options->options.setIntraOpNumThreads(2);session=env.createSession(file.path,options)}
            }
            val ids=tokenizer!!.encode((if(query)"为这个句子生成表示以用于检索相关文章：" else "")+text)
            val input=mapOf("input_ids" to ids,"attention_mask" to LongArray(ids.size){1},"token_type_ids" to LongArray(ids.size))
                .mapValues{(_,value)->OnnxTensor.createTensor(env,arrayOf(value))}
            try {
                val vector=session!!.run(input).use{result->(result[0].value as Array<FloatArray>)[0].toList()}
                check(vector.size==512 && vector.all{it.isFinite()})
                status.value="本地语义检索已启用 · 不上传向量化文本";return vector
            }finally{input.values.forEach{it.close()}}
        }catch(error:Exception){status.value="本地语义检索暂不可用，已改用本地关键词检索";return emptyList()}
        catch(error:LinkageError){status.value="当前设备未加载向量运行库，已改用本地关键词检索";return emptyList()}
    }
    private fun sha(file:File):String {val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val bytes=ByteArray(8192);while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)}};return digest.digest().joinToString(""){"%02x".format(it)}}
}
