package com.miniichat.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Text-only requests keep their original format; images travel with their own message. */
internal fun messageContent(message: ChatMessage): JsonElement {
    if (message.imageDataUrls.isEmpty()) return JsonPrimitive(message.content)
    require(message.role == "user") { "只有用户消息可以上传照片" }
    return buildJsonArray {
        if (message.content.isNotBlank()) add(buildJsonObject {
            put("type", "text")
            put("text", message.content)
        })
        message.imageDataUrls.forEach { url ->
            require(url.startsWith("data:image/jpeg;base64,")) { "图片格式错误" }
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", url) })
            })
        }
    }
}
