package com.miniichat.rp

import android.content.Context
import com.miniichat.image.ImageGenerationConfig
import com.miniichat.image.ImageGenerationService
import java.io.File

/**
 * 人物固定头像的薄封装。当前项目没有确定的生图 Provider，因此 generator 默认为空；
 * 后续接入时只需实现 RpPortraitGenerator，不需要改 RP 世界或 UI。
 */
class RpPortraitService(
    private val context: Context,
    private val legacyGenerator: RpPortraitGenerator = OpenAiCompatibleRpPortraitGenerator(),
    private val imageGenerationService: ImageGenerationService = ImageGenerationService(context)
) {
    fun isConfigured(provider: RpPortraitProvider?): Boolean =
        provider != null && provider.baseUrl.isNotBlank() && provider.modelId.isNotBlank()

    suspend fun generate(
        world: RpWorld,
        character: RpCharacter,
        provider: RpPortraitProvider
    ): String? {
        val directory = File(context.filesDir, "rp_worlds/${world.id}/portraits")
        if (!directory.exists() && !directory.mkdirs()) return null
        val target = File(directory, "${character.id}.png")
        val prompt = buildRpPortraitPrompt(world, character)
        val result = if (provider.useUnifiedService) {
            imageGenerationService.generateToPath(
                config = ImageGenerationConfig(
                    enabled = true,
                    baseUrl = provider.baseUrl,
                    model = provider.modelId,
                    width = provider.width,
                    height = provider.height,
                    steps = provider.steps,
                    guidanceScale = provider.guidanceScale,
                    timeoutSeconds = provider.timeoutSeconds
                ),
                originalPrompt = prompt,
                outputPath = target.absolutePath
            ).localPath
        } else {
            legacyGenerator.generateFixedPortrait(provider, prompt, target.absolutePath)
        }
        return result.takeIf { File(it).isFile } ?: target.absolutePath.takeIf { target.isFile }
    }

    fun close() {
        legacyGenerator.close()
        imageGenerationService.close()
    }
}

fun buildRpPortraitPrompt(world: RpWorld, character: RpCharacter): String = buildString {
    append("固定人物头像；世界：${world.name}；世界视觉风格：${world.visualStyle}；")
    append("时代、地区文化与背景：${world.publicBackground.take(320)}；")
    append("世界规则与时代限制：${world.rules.take(180)}；时间规则：${world.timeRules.take(120)}；")
    append("人物：${character.name}；身份：${character.knownIdentity}；")
    append("种族：${character.species}；性别：${character.gender}；年龄：${character.age}；")
    append("发型：${character.hair}；外貌：${character.appearance}；服饰：${character.clothing}；")
    append("人物气质与公开印象：${character.knownDescription}；当前气质：${character.mood}；")
    append("半身人物肖像，主体居中，面部清晰，不添加文字与标志；严格继承同一世界的时代、文化、服装与整体美术风格。")
}
