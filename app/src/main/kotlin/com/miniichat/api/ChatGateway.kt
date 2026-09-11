package com.miniichat.api

import com.miniichat.data.AppSettings

data class RouteAttempt(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val errorType: String? = null,
    val httpStatus: Int? = null
) {
    val displayName: String = "$providerName · $modelId"
}

class ChatRouteException(
    val attempts: List<RouteAttempt>,
    cause: Throwable
) : RuntimeException(cause.message, cause)

class ChatGateway(private val client: LlmClient) {
    suspend fun stream(
        routes: List<ModelRoute>,
        settings: AppSettings,
        reasoningEnabled: Boolean,
        reasoningEffort: String,
        messagesForRoute: (ModelRoute) -> List<ChatMessage>,
        onAttempt: suspend (ModelRoute, Int, Int) -> Unit = { _, _, _ -> },
        onFailure: suspend (RouteAttempt, Boolean, Throwable) -> Unit = { _, _, _ -> },
        onEvent: suspend (ModelRoute, ChatStreamEvent) -> Unit
    ): Pair<ModelRoute, List<RouteAttempt>> {
        require(routes.isNotEmpty()) { "没有可用的模型路由" }
        val attempts = mutableListOf<RouteAttempt>()
        var lastError: Throwable? = null

        routes.forEachIndexed { index, route ->
            onAttempt(route, index, routes.size)
            var emitted = false
            try {
                client.chatStream(
                    provider = route.provider,
                    settings = settings,
                    modelId = route.modelId,
                    messages = messagesForRoute(route),
                    reasoningEnabled = reasoningEnabled,
                    reasoningEffort = reasoningEffort
                ).collect { event ->
                    if (!event.content.isNullOrEmpty() || !event.reasoning.isNullOrEmpty()) emitted = true
                    onEvent(route, event)
                }
                attempts += RouteAttempt(
                    providerId = route.provider.id,
                    providerName = route.provider.name,
                    modelId = route.modelId
                )
                return route to attempts
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                lastError = error
                val httpStatus = generateSequence(error as Throwable?) { it.cause }
                    .filterIsInstance<LlmHttpException>()
                    .firstOrNull()?.statusCode
                val attempt = RouteAttempt(
                    providerId = route.provider.id,
                    providerName = route.provider.name,
                    modelId = route.modelId,
                    errorType = error::class.java.simpleName,
                    httpStatus = httpStatus
                )
                attempts += attempt

                val remaining = routes.drop(index + 1)
                // 401 means the credential is unusable for this whole service. A 403 can be
                // model-specific, so another model on the same service may still be permitted.
                val authFailure = httpStatus == 401
                val hasEligibleNext = remaining.any {
                    !authFailure || it.provider.id != route.provider.id
                }
                val retryable = ModelFallbackPolicy.mayTryNext(error, emitted) && hasEligibleNext
                onFailure(attempt, retryable, error)
                if (!retryable) throw ChatRouteException(attempts, error)

                if (authFailure) {
                    // Invalid credentials cannot be fixed by trying another model
                    // on the same service. The loop skips those routes below.
                    val nextDifferentProvider = routes.indexOfFirstFrom(index + 1) {
                        it.provider.id != route.provider.id
                    }
                    if (nextDifferentProvider < 0) throw ChatRouteException(attempts, error)
                    return streamRemaining(
                        routes = routes.drop(nextDifferentProvider),
                        settings = settings,
                        reasoningEnabled = reasoningEnabled,
                        reasoningEffort = reasoningEffort,
                        messagesForRoute = messagesForRoute,
                        attempts = attempts,
                        onAttempt = onAttempt,
                        onFailure = onFailure,
                        onEvent = onEvent
                    )
                }
            }
        }
        throw ChatRouteException(attempts, lastError ?: IllegalStateException("所有模型均不可用"))
    }

    private suspend fun streamRemaining(
        routes: List<ModelRoute>,
        settings: AppSettings,
        reasoningEnabled: Boolean,
        reasoningEffort: String,
        messagesForRoute: (ModelRoute) -> List<ChatMessage>,
        attempts: MutableList<RouteAttempt>,
        onAttempt: suspend (ModelRoute, Int, Int) -> Unit,
        onFailure: suspend (RouteAttempt, Boolean, Throwable) -> Unit,
        onEvent: suspend (ModelRoute, ChatStreamEvent) -> Unit
    ): Pair<ModelRoute, List<RouteAttempt>> {
        return try {
            val (route, laterAttempts) = stream(
                routes,
                settings,
                reasoningEnabled,
                reasoningEffort,
                messagesForRoute,
                onAttempt,
                onFailure,
                onEvent
            )
            route to (attempts + laterAttempts)
        } catch (error: ChatRouteException) {
            throw ChatRouteException(attempts + error.attempts, error.cause ?: error)
        }
    }
}

private inline fun <T> List<T>.indexOfFirstFrom(startIndex: Int, predicate: (T) -> Boolean): Int {
    for (index in startIndex until size) if (predicate(this[index])) return index
    return -1
}
