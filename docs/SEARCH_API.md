# 搜索设置

启用并保存至少一个搜索源后，在聊天输入栏打开联网。程序并行查询已配置来源。

- Brave：到 https://brave.com/search/api/ 申请密钥，填写 API 密钥即可。
- Tavily：到 https://app.tavily.com/ 获取密钥；文档 https://docs.tavily.com/ 。
- SearXNG：填写自己的可信实例根地址，必须启用 JSON 搜索响应。部署文档 https://docs.searxng.org/ 。
- 自定义：填写你部署的兼容网关地址，不能把普通搜索网页地址当作 API。

自定义请求为 `POST {BaseURL}/search`，JSON 含 `query`、`engine`（可为空）、`max_results`；密钥非空时同时发送 `Authorization: Bearer` 和 `X-API-Key`。
响应为 `{ "results": [{ "title": "标题", "snippet": "摘要", "url": "https://..." }] }`。
也支持历史网关返回的 `data` 列表。具体支持格式以 `search/CustomSearchProvider.kt` 为准。

密钥均保存在设备本地；外部网站在系统浏览器中打开，应用不会代填密钥。
