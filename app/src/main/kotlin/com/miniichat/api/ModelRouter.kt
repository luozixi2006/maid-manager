package com.miniichat.api

import com.miniichat.data.ProviderAuthMode
import com.miniichat.data.ProviderConfig
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class ModelRoute(
    val provider: ProviderConfig,
    val modelId: String,
    val primary: Boolean
) {
    val key: String = "${provider.id}::$modelId"
}

object ModelRouter {
    fun buildRoutes(
        providers: List<ProviderConfig>,
        activeProviderId: String,
        activeModel: String,
        preferredProviderId: String? = null,
        preferredModel: String? = null,
        automaticFallback: Boolean = true
    ): List<ModelRoute> {
        val available = providers.filter { it.enabled }
        if (available.isEmpty()) return emptyList()

        val preferredProvider = preferredProviderId
            ?.let { id -> available.firstOrNull { it.id == id } }
        val activeProvider = available.firstOrNull { it.id == activeProviderId }
        val primary = buildList {
            preferredProvider?.let { provider ->
                add(provider to preferredModel.orEmpty().ifBlank { provider.models.firstOrNull().orEmpty() })
            }
            activeProvider?.let { provider ->
                add(provider to activeModel.ifBlank { provider.models.firstOrNull().orEmpty() })
            }
            available.sortedWith(compareBy<ProviderConfig> { it.priority }.thenBy { it.createdAt })
                .forEach { provider -> add(provider to provider.models.firstOrNull().orEmpty()) }
        }.firstOrNull { (_, model) -> model.isNotBlank() } ?: return emptyList()
        val (primaryProvider, primaryModel) = primary

        val routes = mutableListOf(ModelRoute(primaryProvider, primaryModel, primary = true))
        if (!automaticFallback) return routes

        val orderedProviders = available.sortedWith(
            compareBy<ProviderConfig> { if (it.id == primaryProvider.id) 0 else 1 }
                .thenBy { it.priority }
                .thenBy { it.createdAt }
        )
        orderedProviders.forEach { provider ->
            if (provider.id != primaryProvider.id && !provider.allowFallback) return@forEach
            if (!provider.isUsableWithoutPrompt()) return@forEach
            val candidates = buildList {
                // If a persona-specific route is primary, preserve the globally selected
                // provider/model as an atomic fallback pair. Taking models.first() here can
                // silently route the request to a model the user did not select.
                if (provider.id == activeProvider?.id) {
                    add(activeModel.ifBlank { provider.models.firstOrNull().orEmpty() })
                }
                when {
                    provider.fallbackModels.isNotEmpty() -> addAll(provider.fallbackModels)
                    provider.id == primaryProvider.id -> addAll(provider.models)
                    provider.id == activeProvider?.id -> Unit
                    else -> addAll(provider.models.take(1))
                }
            }
            candidates.filter { it.isNotBlank() }.forEach { model ->
                routes += ModelRoute(provider, model, primary = false)
            }
        }
        return routes.distinctBy { it.key }
    }

    private fun ProviderConfig.isUsableWithoutPrompt(): Boolean = when (authMode) {
        ProviderAuthMode.NONE -> true
        ProviderAuthMode.BEARER -> apiKey.isNotBlank()
    }
}

object ModelFallbackPolicy {
    fun mayTryNext(error: Throwable, emittedAnyOutput: Boolean): Boolean {
        if (emittedAnyOutput) return false
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        val http = causes.filterIsInstance<LlmHttpException>().firstOrNull()
        if (http != null) {
            return http.statusCode in setOf(401, 403, 404, 408, 409, 425, 429) ||
                http.statusCode in 500..599
        }
        return causes.any {
            it is LlmEmptyResponseException ||
                it is LlmProtocolException ||
                it is HttpRequestTimeoutException ||
                it is ConnectTimeoutException ||
                it is SocketTimeoutException ||
                it is UnknownHostException ||
                it is ConnectException ||
                it is NoRouteToHostException ||
                it is SocketException ||
                it is SSLException ||
                it is IOException
        }
    }
}
