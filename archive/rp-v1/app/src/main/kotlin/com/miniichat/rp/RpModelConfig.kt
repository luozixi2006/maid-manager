package com.miniichat.rp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.rpModelsDataStore: DataStore<Preferences> by preferencesDataStore(name = "rp_models")

@Serializable
enum class RpModelTask {
    CREATION, CHARACTER, NARRATION, WORLD, MEMORY, STATE, RULE
}

@Serializable
enum class RpModelProfile { SAVER, BALANCED, HIGH_QUALITY, CUSTOM }

@Serializable
data class RpCatalogModel(
    val id: String,
    val name: String,
    val description: String = "",
    val provider: String = "",
    val contextLength: Long? = null,
    val promptPricePerToken: Double? = null,
    val completionPricePerToken: Double? = null,
    val inputModalities: List<String> = emptyList(),
    val outputModalities: List<String> = emptyList(),
    val supportedParameters: List<String> = emptyList(),
    val supportsTextGeneration: Boolean = true,
    val supportsTools: Boolean? = null,
    val supportsStructuredOutput: Boolean? = null,
    val supportsImageInput: Boolean? = null
) {
    fun priceLabel(): String {
        val prices = listOfNotNull(promptPricePerToken, completionPricePerToken)
        if (prices.isEmpty()) return "价格未知"
        val perMillion = prices.maxOrNull().orEmptyPrice() * 1_000_000
        return when {
            perMillion <= 0.2 -> "很便宜"
            perMillion <= 1.0 -> "便宜"
            perMillion <= 6.0 -> "中等"
            else -> "较贵"
        }
    }

    fun priceDetail(): String {
        fun Double?.formatPrice(): String = this?.let { "\$${"%.3f".format(it * 1_000_000)}/M" }
            ?: "未知"
        return "输入 ${promptPricePerToken.formatPrice()} · 输出 ${completionPricePerToken.formatPrice()}"
    }

    fun contextLabel(): String = when (val length = contextLength) {
        null -> "上下文未知"
        in 0..999 -> "$length tokens"
        else -> "${length / 1000}K 上下文"
    }
}

@Serializable
data class RpTaskModels(
    val candidates: List<String> = emptyList(),
    val defaultModel: String = ""
)

@Serializable
data class RpModelReliability(
    val creationSuccessCount: Int = 0,
    val creationFormatFailureCount: Int = 0,
    val creationTimeoutCount: Int = 0,
    val lastCreationAt: Long = 0L
)

@Serializable
data class RpProviderModelState(
    val profile: RpModelProfile = RpModelProfile.BALANCED,
    val catalog: List<RpCatalogModel> = emptyList(),
    val catalogUpdatedAt: Long = 0L,
    val myModels: List<String> = emptyList(),
    val recommendations: Map<RpModelProfile, Map<RpModelTask, RpTaskModels>> = emptyMap(),
    val pendingRecommendations: Map<RpModelProfile, Map<RpModelTask, RpTaskModels>> = emptyMap(),
    val recommendationUpdatedAt: Long = 0L,
    val modelReliability: Map<String, RpModelReliability> = emptyMap(),
    val tasks: Map<RpModelTask, RpTaskModels> = emptyMap()
)

@Serializable
data class RpImageGenerationSettings(
    val providerId: String = "",
    val modelId: String = ""
)

@Serializable
data class RpModelSettings(
    val activeProviderId: String = RpAiServices.SILICONFLOW,
    val providerStates: Map<String, RpProviderModelState> = emptyMap(),
    val providerIsolationInitialized: Boolean = false,
    val profile: RpModelProfile = RpModelProfile.BALANCED,
    val catalog: List<RpCatalogModel> = emptyList(),
    val catalogUpdatedAt: Long = 0L,
    val myModels: List<String> = emptyList(),
    val recommendations: Map<RpModelProfile, Map<RpModelTask, RpTaskModels>> = emptyMap(),
    val pendingRecommendations: Map<RpModelProfile, Map<RpModelTask, RpTaskModels>> = emptyMap(),
    val recommendationUpdatedAt: Long = 0L,
    val useRecommendedParameters: Boolean = true,
    val temperature: Float = 0.7f,
    val creationTimeoutSeconds: Int = 300,
    val creationMaxOutputTokens: Int = 6000,
    val imageGeneration: RpImageGenerationSettings = RpImageGenerationSettings(),
    val modelReliability: Map<String, RpModelReliability> = emptyMap(),
    val tasks: Map<RpModelTask, RpTaskModels> = emptyMap()
)

fun RpModelSettings.toProviderState() = RpProviderModelState(
    profile = profile,
    catalog = catalog,
    catalogUpdatedAt = catalogUpdatedAt,
    myModels = myModels,
    recommendations = recommendations,
    pendingRecommendations = pendingRecommendations,
    recommendationUpdatedAt = recommendationUpdatedAt,
    modelReliability = modelReliability,
    tasks = tasks
)

fun RpModelSettings.withProviderState(providerId: String, state: RpProviderModelState) = copy(
    activeProviderId = providerId,
    profile = state.profile,
    catalog = state.catalog,
    catalogUpdatedAt = state.catalogUpdatedAt,
    myModels = state.myModels,
    recommendations = state.recommendations,
    pendingRecommendations = state.pendingRecommendations,
    recommendationUpdatedAt = state.recommendationUpdatedAt,
    modelReliability = state.modelReliability,
    tasks = state.tasks
)

class RpModelStore(private val context: Context) {
    private val key = stringPreferencesKey("rp_model_settings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val flow: Flow<RpModelSettings> = context.rpModelsDataStore.data.map { preferences ->
        preferences[key]?.let { raw ->
            runCatching { json.decodeFromString(RpModelSettings.serializer(), raw) }.getOrNull()
        } ?: RpModelSettings()
    }

    suspend fun snapshot(): RpModelSettings = flow.first()

    suspend fun save(value: RpModelSettings) {
        context.rpModelsDataStore.edit { it[key] = json.encodeToString(RpModelSettings.serializer(), value) }
    }
}

private fun Double?.orEmptyPrice(): Double = this ?: 0.0

fun RpModelTask.label(): String = when (this) {
    RpModelTask.CREATION -> "RP 创建"
    RpModelTask.CHARACTER -> "角色对白"
    RpModelTask.NARRATION -> "旁白"
    RpModelTask.WORLD -> "世界推进"
    RpModelTask.MEMORY -> "记忆整理"
    RpModelTask.STATE -> "状态更新"
    RpModelTask.RULE -> "世界规则检测"
}

fun RpModelProfile.label(): String = when (this) {
    RpModelProfile.SAVER -> "省钱"
    RpModelProfile.BALANCED -> "平衡"
    RpModelProfile.HIGH_QUALITY -> "高质量"
    RpModelProfile.CUSTOM -> "自定义"
}
