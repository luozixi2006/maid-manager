package com.miniichat.tasks.agent

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/** Text-only Office reading; no macros, relationships, embedded objects or remote resources. */
object OfficeText {
    val extensions = setOf("docx", "xlsx", "pptx")
    fun read(file: File): String = ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence().filter {
            when (file.extension.lowercase()) {
                "docx" -> it.name == "word/document.xml"
                "pptx" -> it.name.matches(Regex("ppt/slides/slide[0-9]+\\.xml"))
                "xlsx" -> it.name == "xl/sharedStrings.xml" || it.name.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml"))
                else -> false
            }
        }.sortedWith(compareBy({ if (it.name.contains("sharedStrings")) 0 else 1 }, { it.name.filter(Char::isDigit).toIntOrNull() ?: 0 })).take(40).toList()
        var remaining = 4 * 1024 * 1024
        val shared = mutableListOf<String>()
        buildString {
            for (entry in entries) {
                val bytes = zip.getInputStream(entry).use { input ->
                    val limit = minOf(remaining, 1024 * 1024)
                    val data = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break
                        check(data.size() + count <= limit) { "Office 文档展开后过大，请拆分文档" }; data.write(buffer, 0, count)
                    }; data.toByteArray()
                }
                remaining -= bytes.size
                val xml = bytes.toString(Charsets.UTF_8)
                check(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "不读取包含外部声明的文档" }
                val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
                var cellType = ""; var inShared = false; var sharedText = StringBuilder()
                append("\n[${entry.name}]\n")
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) when (parser.name.substringAfter(':')) {
                        "si" -> { inShared = true; sharedText = StringBuilder() }
                        "c" -> { cellType = parser.getAttributeValue(null, "t").orEmpty() }
                        "t", "v" -> {
                            val value = parser.nextText()
                            if (inShared) sharedText.append(value)
                            else append(if (cellType == "s" && parser.name.substringAfter(':') == "v") shared.getOrNull(value.toIntOrNull() ?: -1) ?: "[未知单元格]" else value).append(' ')
                        }
                    }
                    if (parser.eventType == XmlPullParser.END_TAG) when (parser.name.substringAfter(':')) {
                        "si" -> { shared += sharedText.toString(); inShared = false }
                        "p", "row" -> append('\n')
                    }
                    parser.next()
                }
            }
            append("\n[仅提取正文、幻灯片文本或单元格值；不运行公式/宏，不读取图片，最多40部分]")
        }.take(120000)
    }
}
