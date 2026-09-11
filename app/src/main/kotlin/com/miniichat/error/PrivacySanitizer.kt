package com.miniichat.error

import java.net.URI

object PrivacySanitizer {
    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val namedSecret = Regex(
        "(?i)(api[-_ ]?key|authorization|access[-_ ]?token|secret|password)\\s*[:=]\\s*[^\\s,;]+"
    )
    private val commonApiToken = Regex("(?i)\\b(sk|or|sf|hf)[-_][A-Za-z0-9_-]{8,}\\b")

    /** Sanitizes a short identifier without ever accepting arbitrary diagnostic payloads. */
    fun safeIdentifier(value: String): String? {
        val clean = redact(value).replace(Regex("[\\r\\n\\t]"), " ").trim().take(160)
        return clean.takeIf { it.isNotBlank() }
    }

    fun redact(value: String): String = value
        .replace(bearer, "Bearer [已隐藏]")
        .replace(namedSecret) { match -> "${match.groupValues[1]}=[已隐藏]" }
        .replace(commonApiToken, "[已隐藏]")

    /**
     * Returns only scheme, host and explicit port. User info, path, query and
     * fragment are discarded so URLs cannot leak keys or private request data.
     */
    fun originOnly(value: String): String? = runCatching {
        val normalized = if (value.contains("://")) value else "https://$value"
        val uri = URI(normalized)
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()

    fun sanitized(error: AppError): AppError = error.copy(
        id = safeIdentifier(error.id) ?: "ERR-UNKNOWN",
        title = redact(error.title).take(120),
        userMessage = redact(error.userMessage).take(500),
        explanation = redact(error.explanation).take(1_000),
        suggestions = error.suggestions.map { redact(it).take(300) }.take(8),
        providerHost = error.providerHost?.let(::safeIdentifier),
        modelId = error.modelId?.let(::safeIdentifier),
        causeChain = error.causeChain.map(::safeCauseName).distinct().take(8)
    )

    private fun safeCauseName(value: String): String = value
        .substringBefore(':')
        .replace(Regex("[^A-Za-z0-9_.$]"), "")
        .take(180)
        .ifBlank { "UnknownException" }
}
