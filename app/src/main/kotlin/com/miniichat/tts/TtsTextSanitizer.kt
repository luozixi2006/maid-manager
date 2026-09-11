package com.miniichat.tts

fun sanitizeTextForTts(input: String): String {
    return input
        .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), " ")
        .replace(Regex("\\[([^]]+)]\\((?:https?://|www\\.)[^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+|www\\.\\S+"), " ")
        .replace(Regex("```[a-zA-Z0-9_+-]*"), " ")
        .replace("```", " ")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*>\\s?"), "")
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        .replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
        .replace(Regex("[*_~`]"), "")
        .replace(Regex("(?i)\\b(?:deepseek|gpt|claude|gemini|qwen)[-\\w.]*\\b"), "")
        .lineSequence()
        .filterNot { it.trim() in setOf("复制", "播放", "加载中", "暂停", "来源", "Copy", "Play") }
        .joinToString("\n")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
