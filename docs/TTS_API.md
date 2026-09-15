# 语音设置

本应用支持兼容的语音接口，自建 IndexTTS 可以继续使用。

- Base URL：填写自己的服务根地址，例如 Tailscale 地址和端口，不要复制别人的地址。
- API 密钥：由服务管理员提供；本地免鉴权服务留空。
- Model：与服务配置一致，例如 `IndexTTS-2.5`。
- Voice ID：与服务参考音频一致，例如 `default.wav`。

程序请求 `POST {BaseURL}/audio/speech`，发送 `model`、`voice`、`input`、`response_format: wav`、`speed: 1.0`。
服务返回 WAV 二进制音频。官方项目及参考音频要求：https://github.com/index-tts/index-tts 。

远程自建服务需要手机和电脑都连接到同一个 Tailscale 网络，地址填写电脑真实的 Tailscale IP。
