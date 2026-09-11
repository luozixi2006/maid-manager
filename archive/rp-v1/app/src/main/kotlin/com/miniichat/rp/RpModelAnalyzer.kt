package com.miniichat.rp

import kotlin.math.ln

/** Provider-neutral RP model analysis. Unknown metadata is never treated as free or best. */
object RpModelAnalyzer {
    private data class ProfileWeights(
        val ability: Double,
        val cost: Double,
        val speed: Double,
        val confidence: Double,
        val reuseTolerance: Double = 0.0
    )

    private data class TaskWeights(
        val chinese: Double = 0.0,
        val creative: Double = 0.0,
        val reasoning: Double = 0.0,
        val instruction: Double = 0.0,
        val structured: Double = 0.0,
        val context: Double = 0.0,
        val quality: Double = 0.0,
        val history: Double = 0.0
    )

    private val saverWeights = ProfileWeights(0.35, 0.43, 0.15, 0.07, reuseTolerance = 0.07)
    private val balancedWeights = ProfileWeights(0.64, 0.19, 0.07, 0.10)
    private val highQualityWeights = ProfileWeights(0.78, 0.06, 0.03, 0.13)
    private val highQualityBackgroundWeights = ProfileWeights(0.58, 0.25, 0.07, 0.10)

    private val taskWeights = mapOf(
        RpModelTask.CREATION to TaskWeights(
            chinese = 0.10, reasoning = 0.12, instruction = 0.22, structured = 0.24,
            context = 0.14, quality = 0.08, history = 0.10
        ),
        RpModelTask.CHARACTER to TaskWeights(
            chinese = 0.25, creative = 0.30, instruction = 0.17, context = 0.13, quality = 0.15
        ),
        RpModelTask.NARRATION to TaskWeights(
            creative = 0.36, chinese = 0.20, context = 0.17, quality = 0.20, instruction = 0.07
        ),
        RpModelTask.WORLD to TaskWeights(
            reasoning = 0.28, instruction = 0.25, structured = 0.20, context = 0.17, quality = 0.10
        ),
        RpModelTask.MEMORY to TaskWeights(
            instruction = 0.28, structured = 0.30, context = 0.17, reasoning = 0.10, quality = 0.15
        ),
        RpModelTask.STATE to TaskWeights(
            structured = 0.36, instruction = 0.32, reasoning = 0.12, context = 0.08, quality = 0.12
        ),
        RpModelTask.RULE to TaskWeights(
            reasoning = 0.35, context = 0.24, instruction = 0.17, structured = 0.12, quality = 0.12
        )
    )

    fun analyze(
        catalog: List<RpCatalogModel>,
        reliability: Map<String, RpModelReliability> = emptyMap()
    ): Map<RpModelProfile, Map<RpModelTask, RpTaskModels>> {
        val available = catalog.filter { it.supportsTextGeneration }.distinctBy { it.id }
        if (available.isEmpty()) return emptyMap()
        val metrics = available.associateWith { model -> metrics(model, reliability[model.id]) }

        fun ranked(task: RpModelTask, profile: RpModelProfile): List<RpCatalogModel> =
            available.sortedWith(
                compareByDescending<RpCatalogModel> {
                    profileScore(profile, task, metrics.getValue(it))
                }.thenBy { it.id }
            )

        fun build(profile: RpModelProfile): Map<RpModelTask, RpTaskModels> =
            RpModelTask.entries.associateWith { task ->
                val candidates = ranked(task, profile).take(4).map { it.id }
                RpTaskModels(candidates, candidates.firstOrNull().orEmpty())
            }

        // Saver can reuse one cheap capable model, but never blindly copies it to every task.
        val saverAnchor = available.maxByOrNull { model ->
            val value = metrics.getValue(model)
            val averageAbility = RpModelTask.entries.map { taskScore(it, value) }.average()
            averageAbility * saverWeights.ability + value.cost * saverWeights.cost +
                value.speed * saverWeights.speed + value.confidence * saverWeights.confidence
        }
        val saver = RpModelTask.entries.associateWith { task ->
            val ranked = ranked(task, RpModelProfile.SAVER)
            val best = ranked.first()
            val bestScore = profileScore(RpModelProfile.SAVER, task, metrics.getValue(best))
            val anchorScore = saverAnchor?.let {
                profileScore(RpModelProfile.SAVER, task, metrics.getValue(it))
            } ?: Double.NEGATIVE_INFINITY
            val chosen = saverAnchor?.takeIf {
                bestScore - anchorScore <= saverWeights.reuseTolerance &&
                    taskScore(task, metrics.getValue(it)) >= 0.46
            } ?: best
            val candidates = (listOf(chosen.id) + ranked.map { it.id }).distinct().take(4)
            RpTaskModels(candidates, chosen.id)
        }

        return mapOf(
            RpModelProfile.SAVER to saver,
            RpModelProfile.BALANCED to build(RpModelProfile.BALANCED),
            RpModelProfile.HIGH_QUALITY to build(RpModelProfile.HIGH_QUALITY)
        )
    }

    fun profilesEquivalent(
        recommendations: Map<RpModelProfile, Map<RpModelTask, RpTaskModels>>
    ): Boolean {
        val profiles = listOf(
            RpModelProfile.SAVER, RpModelProfile.BALANCED, RpModelProfile.HIGH_QUALITY
        ).map { profile ->
            RpModelTask.entries.map { recommendations[profile]?.get(it)?.defaultModel.orEmpty() }
        }
        return profiles.distinct().size <= 1
    }

    fun differentiationNotice(
        catalog: List<RpCatalogModel>,
        recommendations: Map<RpModelProfile, Map<RpModelTask, RpTaskModels>>
    ): String? = if (profilesEquivalent(recommendations)) {
        if (catalog.count { it.supportsTextGeneration } <= 1) {
            "当前可用模型较少，三种方案暂时使用相同模型。"
        } else {
            "当前模型池中未发现更合适的差异化组合。"
        }
    } else null

    fun profileReason(profile: RpModelProfile): String = when (profile) {
        RpModelProfile.SAVER -> "优先选择已知低价且能力足够的模型，适合长时间 RP。"
        RpModelProfile.BALANCED -> "角色质量、任务能力、上下文与后台成本综合选择。"
        RpModelProfile.HIGH_QUALITY -> "角色和旁白优先质量，记忆与状态任务仍兼顾成本。"
        RpModelProfile.CUSTOM -> "由用户为每项职责手动选择模型。"
    }

    fun debugReport(
        catalog: List<RpCatalogModel>,
        recommendations: Map<RpModelProfile, Map<RpModelTask, RpTaskModels>>,
        reliability: Map<String, RpModelReliability> = emptyMap()
    ): String = buildString {
        val available = catalog.filter { it.supportsTextGeneration }.distinctBy { it.id }
        appendLine("detected models=${available.size}")
        available.forEach { model ->
            val value = metrics(model, reliability[model.id])
            appendLine(
                "model=${model.id} context=${model.contextLength ?: "unknown"} " +
                    "promptPrice=${model.promptPricePerToken ?: "unknown"} " +
                    "completionPrice=${model.completionPricePerToken ?: "unknown"} " +
                    "confidence=${"%.3f".format(value.confidence)}"
            )
            RpModelTask.entries.forEach { task ->
                appendLine(
                    "  task=${task.name} saver=${"%.3f".format(profileScore(RpModelProfile.SAVER, task, value))} " +
                        "balanced=${"%.3f".format(profileScore(RpModelProfile.BALANCED, task, value))} " +
                        "quality=${"%.3f".format(profileScore(RpModelProfile.HIGH_QUALITY, task, value))}"
                )
            }
        }
        listOf(RpModelProfile.SAVER, RpModelProfile.BALANCED, RpModelProfile.HIGH_QUALITY).forEach { profile ->
            appendLine("profile=${profile.name}")
            RpModelTask.entries.forEach { task ->
                appendLine("  ${task.name}=${recommendations[profile]?.get(task)?.defaultModel.orEmpty()}")
            }
        }
    }

    fun labels(model: RpCatalogModel, task: RpModelTask? = null): List<String> {
        val value = metrics(model)
        val labels = mutableListOf<String>()
        if (value.chinese >= 0.68) labels += "中文适配"
        if (value.creative >= 0.70) labels += if (task == RpModelTask.CHARACTER) "适合对白" else "适合旁白"
        if (value.reasoning >= 0.72) labels += "推理强"
        if (value.structured >= 0.75) labels += "结构化输出"
        if ((model.contextLength ?: 0L) >= 128_000L) labels += "长上下文"
        if (value.costKnown && value.cost >= 0.72) labels += "低成本"
        if (value.speed >= 0.76) labels += "响应倾向快"
        if (model.supportsImageInput == true) labels += "支持图片输入"
        if (value.confidence < 0.5) labels += "能力信息不足"
        if (task == RpModelTask.CREATION && value.creationHistory >= 0.72) labels += "已成功创建 RP"
        return labels.distinct().take(3)
    }

    fun isRecommendedFor(settings: RpModelSettings, task: RpModelTask, modelId: String): Boolean =
        settings.recommendations[settings.profile]?.get(task)?.candidates?.contains(modelId) == true

    private fun profileScore(profile: RpModelProfile, task: RpModelTask, value: Metrics): Double {
        val weights = when (profile) {
            RpModelProfile.SAVER -> saverWeights
            RpModelProfile.BALANCED -> balancedWeights
            RpModelProfile.HIGH_QUALITY -> if (task in setOf(RpModelTask.MEMORY, RpModelTask.STATE)) {
                highQualityBackgroundWeights
            } else highQualityWeights
            RpModelProfile.CUSTOM -> ProfileWeights(1.0, 0.0, 0.0, 0.0)
        }
        return taskScore(task, value) * weights.ability + value.cost * weights.cost +
            value.speed * weights.speed + value.confidence * weights.confidence
    }

    private fun taskScore(task: RpModelTask, value: Metrics): Double {
        val weights = taskWeights.getValue(task)
        return value.chinese * weights.chinese + value.creative * weights.creative +
            value.reasoning * weights.reasoning + value.instruction * weights.instruction +
            value.structured * weights.structured + value.context * weights.context +
            value.quality * weights.quality + value.creationHistory * weights.history
    }

    private fun metrics(model: RpCatalogModel, reliability: RpModelReliability? = null): Metrics {
        val text = "${model.id} ${model.name} ${model.provider} ${model.description}".lowercase()
        val context = when (val length = model.contextLength) {
            null -> 0.42
            in 0..8_191 -> 0.36
            in 8_192..31_999 -> 0.52
            in 32_000..63_999 -> 0.66
            in 64_000..127_999 -> 0.78
            in 128_000..255_999 -> 0.90
            else -> 1.0
        }
        val structured = when {
            model.supportsStructuredOutput == true -> 1.0
            text.containsAny("json", "structured output", "function calling", "tool use") -> 0.76
            model.supportsStructuredOutput == false -> 0.42
            else -> 0.52
        }
        val reasoning = scoreFromText(
            text,
            listOf("reasoning", "complex problem", "logic", "math", "analysis", "推理"),
            listOf("deepseek", "qwen", "qwq", "glm", "kimi", "claude", "gemini")
        ) + if ("reasoning" in model.supportedParameters) 0.18 else 0.0
        val creative = scoreFromText(
            text,
            listOf("creative writing", "storytelling", "roleplay", "role-play", "narrative", "fiction", "文学", "创作"),
            listOf("claude", "mistral", "qwen", "glm", "kimi", "llama")
        )
        val chinese = scoreFromText(
            text,
            listOf("chinese", "multilingual", "中文", "多语言"),
            listOf("qwen", "deepseek", "glm", "kimi", "mimo", "yi-")
        )
        val instruction = when {
            text.containsAny("instruction following", "instruct", "chat", "assistant", "指令") -> 0.82
            model.supportedParameters.isNotEmpty() -> 0.66
            else -> 0.52
        }
        val qualitySize = Regex("(?:^|[^0-9])(\\d+(?:\\.\\d+)?)\\s*b(?:[^a-z]|$)")
            .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val speed = when {
            text.containsAny("flash", "turbo", "mini", "small", "nano", "fast", "instant", "lightweight", "haiku") -> 0.90
            qualitySize != null && qualitySize <= 8 -> 0.86
            qualitySize != null && qualitySize >= 70 -> 0.44
            text.containsAny("large", "max", "pro", "reasoning", "thinking") -> 0.46
            else -> 0.62
        }
        val quality = when {
            text.containsAny("flagship", "state-of-the-art", "most capable", "frontier") -> 0.96
            qualitySize != null && qualitySize >= 70 -> 0.86
            qualitySize != null && qualitySize >= 30 -> 0.76
            qualitySize != null && qualitySize <= 8 -> 0.50
            else -> 0.64
        }
        val prices = listOfNotNull(model.promptPricePerToken, model.completionPricePerToken)
        val perMillion = prices.takeIf { it.isNotEmpty() }?.average()?.times(1_000_000)
        val cost = perMillion?.let { (1.0 / (1.0 + ln(1.0 + it))).coerceIn(0.10, 1.0) } ?: 0.22
        val metadataSignals = listOf(
            model.description.isNotBlank(), model.contextLength != null, prices.isNotEmpty(),
            model.supportedParameters.isNotEmpty(), model.supportsStructuredOutput != null
        ).count { it }
        val confidence = (0.30 + metadataSignals * 0.13).coerceAtMost(0.95)
        val success = reliability?.creationSuccessCount ?: 0
        val failures = (reliability?.creationFormatFailureCount ?: 0) +
            (reliability?.creationTimeoutCount ?: 0)
        val creationHistory = if (success + failures == 0) 0.50
        else ((success + 1.0) / (success + failures + 2.0)).coerceIn(0.15, 0.95)
        return Metrics(
            chinese.coerceIn(0.30, 1.0), creative.coerceIn(0.30, 1.0),
            reasoning.coerceIn(0.30, 1.0), instruction, structured, context, quality,
            cost, prices.isNotEmpty(), speed, confidence, creationHistory
        )
    }

    private fun scoreFromText(text: String, strong: List<String>, auxiliary: List<String>): Double {
        val strongHits = strong.count { it in text }
        val auxiliaryHits = auxiliary.count { it in text }
        return (0.48 + strongHits.coerceAtMost(2) * 0.20 + auxiliaryHits.coerceAtMost(2) * 0.06)
            .coerceAtMost(1.0)
    }

    private fun String.containsAny(vararg values: String): Boolean = values.any { it in this }

    private data class Metrics(
        val chinese: Double,
        val creative: Double,
        val reasoning: Double,
        val instruction: Double,
        val structured: Double,
        val context: Double,
        val quality: Double,
        val cost: Double,
        val costKnown: Boolean,
        val speed: Double,
        val confidence: Double,
        val creationHistory: Double
    )
}
