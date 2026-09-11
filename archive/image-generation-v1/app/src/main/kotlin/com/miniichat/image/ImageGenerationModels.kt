package com.miniichat.image

import com.miniichat.data.AppSettings

const val FLUX2_KLEIN_MODEL_ID = "black-forest-labs/FLUX.2-klein-4B"

data class ImageGenerationConfig(
    val enabled: Boolean,
    val baseUrl: String,
    val model: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val guidanceScale: Float,
    val timeoutSeconds: Int
)

fun AppSettings.imageGenerationConfig() = ImageGenerationConfig(
    enabled = imageGenerationEnabled,
    baseUrl = imageGenerationBaseUrl,
    model = imageGenerationModel,
    width = imageGenerationWidth,
    height = imageGenerationHeight,
    steps = imageGenerationSteps,
    guidanceScale = imageGenerationGuidance,
    timeoutSeconds = imageGenerationTimeoutSeconds
)

enum class ImageGenerationStatus {
    IDLE, WAITING, LOADING_MODEL, GENERATING, SUCCEEDED, FAILED, CANCELLED
}

data class ImageGenerationUiState(
    val status: ImageGenerationStatus = ImageGenerationStatus.IDLE,
    val jobId: String? = null,
    val originalPrompt: String = "",
    val effectivePrompt: String = "",
    val localPath: String? = null,
    val model: String = FLUX2_KLEIN_MODEL_ID,
    val width: Int = 0,
    val height: Int = 0,
    val seed: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    val isBusy: Boolean
        get() = status in setOf(
            ImageGenerationStatus.WAITING,
            ImageGenerationStatus.LOADING_MODEL,
            ImageGenerationStatus.GENERATING
        )
}

data class ImageServiceHealth(
    val serviceReady: Boolean,
    val modelStatus: String,
    val model: String,
    val variant: String,
    val gpuName: String?,
    val freeVramMb: Int?,
    val modelError: String?
)

data class GeneratedImageResult(
    val jobId: String,
    val localPath: String,
    val originalPrompt: String,
    val effectivePrompt: String,
    val model: String,
    val width: Int,
    val height: Int,
    val seed: Long
)

class ImageGenerationException(
    val code: String,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause)
