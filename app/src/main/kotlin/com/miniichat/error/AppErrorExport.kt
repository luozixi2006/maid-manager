package com.miniichat.error

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppErrorExportDocument(
    val schema: String = SCHEMA,
    val schemaVersion: Int = SCHEMA_VERSION,
    val generatedAt: Long,
    val privacy: String = "不含 API 密钥、Authorization、请求正文、回复正文和聊天内容；服务地址仅保留主机名。",
    val statistics: AppErrorStatistics,
    val errors: List<AppError>
) {
    companion object {
        const val SCHEMA = "maid-manager.error-report"
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class AppErrorStatistics(
    val total: Int,
    val byType: Map<String, Int>,
    val byArea: Map<String, Int>,
    val byHttpStatus: Map<String, Int>
)

object AppErrorExport {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(errors: List<AppError>, generatedAt: Long = System.currentTimeMillis()): String {
        val safe = ErrorRing.normalize(errors).map(PrivacySanitizer::sanitized)
        val document = AppErrorExportDocument(
            generatedAt = generatedAt,
            statistics = AppErrorStatistics(
                total = safe.size,
                byType = safe.groupingBy { it.type.code }.eachCount().toSortedMap(),
                byArea = safe.groupingBy { it.area.name }.eachCount().toSortedMap(),
                byHttpStatus = safe.mapNotNull { it.httpStatus }
                    .groupingBy { it.toString() }.eachCount().toSortedMap()
            ),
            errors = safe
        )
        return json.encodeToString(AppErrorExportDocument.serializer(), document)
    }

    fun decode(raw: String): AppErrorExportDocument? = runCatching {
        json.decodeFromString(AppErrorExportDocument.serializer(), raw)
    }.getOrNull()?.takeIf {
        it.schema == AppErrorExportDocument.SCHEMA &&
            it.schemaVersion == AppErrorExportDocument.SCHEMA_VERSION
    }

    fun decodeErrors(raw: String): List<AppError>? = decode(raw)?.errors
}

object ErrorRing {
    const val MAX_ENTRIES = 100

    fun normalize(errors: List<AppError>): List<AppError> = errors
        .asSequence()
        .map(PrivacySanitizer::sanitized)
        .distinctBy { it.id }
        .sortedByDescending { it.occurredAt }
        .take(MAX_ENTRIES)
        .toList()
}
