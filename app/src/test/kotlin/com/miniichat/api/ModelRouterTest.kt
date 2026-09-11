package com.miniichat.api

import com.miniichat.data.ProviderAuthMode
import com.miniichat.data.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ModelRouterTest {
    @Test
    fun preferredProviderAndModelRemainAnAtomicPair() {
        val routes = ModelRouter.buildRoutes(
            providers = listOf(provider("a", listOf("a1")), provider("b", listOf("b1"))),
            activeProviderId = "a",
            activeModel = "a1",
            preferredProviderId = "missing",
            preferredModel = "model-from-missing-provider"
        )
        assertEquals("a", routes.first().provider.id)
        assertEquals("a1", routes.first().modelId)
    }

    @Test
    fun createsOrderedIndependentFallbackRoutes() {
        val routes = ModelRouter.buildRoutes(
            providers = listOf(
                provider("primary", listOf("main", "same-provider-backup"), priority = 10),
                provider("backup", listOf("remote-backup"), priority = 20)
            ),
            activeProviderId = "primary",
            activeModel = "main"
        )
        assertEquals(
            listOf("primary::main", "primary::same-provider-backup", "backup::remote-backup"),
            routes.map { it.key }
        )
        assertTrue(routes.first().primary)
        assertFalse(routes.drop(1).any { it.primary })
    }

    @Test
    fun unauthenticatedBearerFallbackIsSkippedButLocalNoneIsAllowed() {
        val routes = ModelRouter.buildRoutes(
            providers = listOf(
                provider("primary", listOf("main")),
                provider("missing-key", listOf("x"), key = ""),
                provider("local", listOf("local-model"), key = "", authMode = ProviderAuthMode.NONE)
            ),
            activeProviderId = "primary",
            activeModel = "main"
        )
        assertFalse(routes.any { it.provider.id == "missing-key" })
        assertTrue(routes.any { it.provider.id == "local" })
    }

    @Test
    fun emptyPreferredAndActiveServicesDoNotHideAUsableProvider() {
        val routes = ModelRouter.buildRoutes(
            providers = listOf(
                provider("empty", emptyList(), priority = 10),
                provider("ready", listOf("working-model"), priority = 20)
            ),
            activeProviderId = "empty",
            activeModel = "",
            preferredProviderId = "empty",
            preferredModel = ""
        )

        assertEquals("ready::working-model", routes.first().key)
    }

    @Test
    fun personaRouteKeepsTheGloballySelectedProviderModelPairAsFallback() {
        val routes = ModelRouter.buildRoutes(
            providers = listOf(
                provider("global", listOf("first-model", "selected-model"), priority = 10),
                provider("persona", listOf("persona-model"), priority = 20)
            ),
            activeProviderId = "global",
            activeModel = "selected-model",
            preferredProviderId = "persona",
            preferredModel = "persona-model"
        )

        assertEquals("persona::persona-model", routes.first().key)
        assertTrue(routes.any { it.key == "global::selected-model" })
        assertFalse(routes.any { it.key == "global::first-model" })
    }

    @Test
    fun partialStreamNeverFallsBack() {
        assertFalse(ModelFallbackPolicy.mayTryNext(LlmHttpException(503, ""), true))
        assertTrue(ModelFallbackPolicy.mayTryNext(LlmHttpException(503, ""), false))
        assertTrue(ModelFallbackPolicy.mayTryNext(IOException("connection closed"), false))
        assertFalse(ModelFallbackPolicy.mayTryNext(IllegalArgumentException("bad request"), false))
    }

    @Test
    fun endpointResolverHandlesRootAndFullEndpoint() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            OpenAiEndpointResolver.resolve("https://example.com/v1").chatCompletions
        )
        assertEquals(
            "https://example.com/v1/models",
            OpenAiEndpointResolver.resolve("https://example.com/v1/chat/completions").models
        )
    }

    private fun provider(
        id: String,
        models: List<String>,
        priority: Int = 100,
        key: String = "key",
        authMode: ProviderAuthMode = ProviderAuthMode.BEARER
    ) = ProviderConfig(
        id = id,
        name = id,
        baseUrl = "https://$id.example/v1",
        apiKey = key,
        models = models,
        authMode = authMode,
        priority = priority
    )
}
