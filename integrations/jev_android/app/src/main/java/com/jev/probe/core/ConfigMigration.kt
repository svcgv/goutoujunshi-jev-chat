package com.jev.probe.core

import java.util.UUID

/** Plain snapshot of the old route fields, isolated so migration is unit-testable. */
data class LegacyConfig(
    val hasJudge: Boolean = false,
    val judgeProvider: String = Prefs.PROVIDER_OPENROUTER,
    val judgeBaseUrl: String = Prefs.DEFAULT_JUDGE_BASE_OPENROUTER,
    val judgeKey: String = "",
    val judgeModel: String = Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER,
    val hasStrategy: Boolean = false,
    val strategyProvider: String = Prefs.STRATEGY_COMPATIBLE,
    val strategyBaseUrl: String = Prefs.DEFAULT_PROXY_BASE,
    val strategyKey: String = "",
    val strategyModel: String = "",
    val hasReply: Boolean = false,
    val replyBaseUrl: String = Prefs.DEFAULT_REPLY_BASE,
    val replyKey: String = "",
    val replyModel: String = Prefs.DEFAULT_REPLY_MODEL,
    val hasVision: Boolean = false,
    val visionBaseUrl: String = Prefs.DEFAULT_VISION_BASE,
    val visionKey: String = "",
    val visionModel: String = Prefs.DEFAULT_VISION_MODEL
) {
    val configured: Boolean
        get() = hasJudge || hasStrategy || hasReply || hasVision
}

/** One-time conversion from the three independent legacy routes. */
object ConfigMigration {

    fun migrate(legacy: LegacyConfig): ModelConfigSnapshot {
        if (!legacy.configured) return ModelConfigSnapshot()
        val builder = Builder()

        val judgeEndpoint = legacyJudgeEndpoint(legacy)
        val strategyEndpoint = legacyStrategyEndpoint(legacy)
        val replyEndpoint = legacyChatEndpoint(legacy.replyBaseUrl, Prefs.DEFAULT_REPLY_BASE)
        val visionEndpoint = legacyChatEndpoint(legacy.visionBaseUrl, Prefs.DEFAULT_VISION_BASE)
        val replyEffectiveKey = RouteKeys.reply(
            legacy.replyKey, legacy.judgeKey, replyEndpoint, judgeEndpoint)
        val strategyEffectiveKey = RouteKeys.strategy(
            legacy.strategyKey, legacy.replyKey, replyEndpoint, strategyEndpoint)
        val visionEffectiveKey = RouteKeys.vision(
            legacy.visionKey, legacy.replyKey, legacy.judgeKey,
            visionEndpoint, replyEndpoint, judgeEndpoint).ifBlank {
            if (legacy.strategyProvider != "jev")
                RouteKeys.strategy("", legacy.strategyKey, strategyEndpoint, visionEndpoint)
            else ""
        }

        val judgeKind = when (legacy.judgeProvider) {
            Prefs.PROVIDER_TYPESAFE -> ServiceKind.TYPESAFE
            Prefs.PROVIDER_CUSTOM -> ServiceKind.CUSTOM_JEV
            else -> ServiceKind.OPENROUTER
        }
        val judgeBase = when (judgeKind) {
            ServiceKind.OPENROUTER -> Prefs.DEFAULT_JUDGE_BASE_OPENROUTER
            ServiceKind.TYPESAFE -> legacy.judgeBaseUrl.ifBlank { Prefs.DEFAULT_JUDGE_BASE_TYPESAFE }
            else -> legacy.judgeBaseUrl
        }
        val judgeRef = if (legacy.hasJudge) {
            builder.add(kind = judgeKind, name = judgeKind.label, base = judgeBase,
                key = legacy.judgeKey, protocol = ModelProtocol.JEV,
                modelId = legacy.judgeModel, vision = false)
        } else null

        val strategyKind = if (legacy.strategyProvider == "deepseek") ServiceKind.DEEPSEEK
            else classifyChat(legacy.strategyBaseUrl)
        val strategyBase = if (strategyKind == ServiceKind.DEEPSEEK) Prefs.DEEPSEEK_BASE
            else legacy.strategyBaseUrl
        val strategyRef = if (legacy.hasStrategy) {
            builder.add(kind = strategyKind, name = strategyKind.label, base = strategyBase,
                key = strategyEffectiveKey, protocol = ModelProtocol.CHAT,
                modelId = legacy.strategyModel, vision = false)
        } else null

        val replyKind = classifyChat(legacy.replyBaseUrl)
        val replyRef = if (legacy.hasReply) {
            builder.add(kind = replyKind, name = replyKind.label, base = legacy.replyBaseUrl,
                key = replyEffectiveKey, protocol = ModelProtocol.CHAT,
                modelId = legacy.replyModel, vision = false)
        } else null

        val visionKind = classifyChat(legacy.visionBaseUrl)
        val visionRef = if (legacy.hasVision) {
            builder.add(kind = visionKind, name = visionKind.label, base = legacy.visionBaseUrl,
                key = visionEffectiveKey, protocol = ModelProtocol.CHAT,
                modelId = legacy.visionModel, vision = true)
        } else null

        val activeJudge = if (legacy.strategyProvider != "jev" && strategyRef != null)
            strategyRef else judgeRef
        return ModelConfigSnapshot(
            services = builder.services,
            models = builder.models,
            bindings = InterfaceBindings(
                judgeModelId = activeJudge.orEmpty(),
                replyModelId = replyRef.orEmpty(),
                visionModelId = visionRef.orEmpty()
            )
        )
    }

    private fun classifyChat(base: String): ServiceKind = when {
        base.contains("openrouter.ai", ignoreCase = true) -> ServiceKind.OPENROUTER
        base.contains("api.deepseek.com", ignoreCase = true) -> ServiceKind.DEEPSEEK
        base.contains("dashscope.aliyuncs.com", ignoreCase = true) -> ServiceKind.DASHSCOPE
        else -> ServiceKind.COMPATIBLE
    }

    private fun legacyJudgeEndpoint(legacy: LegacyConfig): String = when (legacy.judgeProvider) {
        Prefs.PROVIDER_TYPESAFE ->
            legacy.judgeBaseUrl.trim().trimEnd('/') + "/v1/systemone"
        Prefs.PROVIDER_CUSTOM -> legacy.judgeBaseUrl.trim()
        else -> Prefs.DEFAULT_JUDGE_BASE_OPENROUTER + "/alpha/decisions"
    }

    private fun legacyStrategyEndpoint(legacy: LegacyConfig): String =
        StrategyRoute.endpoint(if (legacy.strategyProvider == "deepseek")
            Prefs.DEEPSEEK_BASE else legacy.strategyBaseUrl)

    private fun legacyChatEndpoint(base: String, fallback: String): String =
        "${base.trim().ifBlank { fallback }.trimEnd('/')}/chat/completions"

    private class Builder {
        val services = mutableListOf<ServiceConfig>()
        val models = mutableListOf<ModelConfig>()

        fun add(kind: ServiceKind, name: String, base: String, key: String,
                protocol: ModelProtocol, modelId: String, vision: Boolean): String? {
            val cleanModel = modelId.trim()
            if (cleanModel.isBlank()) return null
            val normalized = runCatching { ServiceConfig.normalizeBase(kind, base) }
                .getOrElse { base.trim() }
            if (normalized.isBlank()) return null
            val service = services.firstOrNull {
                it.kind == kind && it.baseUrl == normalized && it.key == key
            } ?: ServiceConfig(
                id = stableId("svc", "${kind.wire}|$normalized|$key"),
                name = uniqueServiceName(name),
                kind = kind,
                baseUrl = normalized,
                key = key
            ).also { services.add(it) }
            val existing = models.firstOrNull {
                it.serviceId == service.id && it.protocol == protocol && it.modelId == cleanModel
            }
            if (existing != null) {
                if (vision && !existing.vision) {
                    val index = models.indexOf(existing)
                    models[index] = existing.copy(vision = true)
                }
                return existing.id
            }
            val model = ModelConfig(
                id = stableId("model", "${service.id}|${protocol.wire}|$cleanModel"),
                serviceId = service.id,
                modelId = cleanModel,
                displayName = cleanModel,
                protocol = protocol,
                vision = vision
            )
            models.add(model)
            return model.id
        }

        private fun uniqueServiceName(base: String): String {
            if (services.none { it.name == base }) return base
            var index = 2
            while (services.any { it.name == "$base $index" }) index++
            return "$base $index"
        }

        private fun stableId(prefix: String, value: String): String =
            "${prefix}_${UUID.nameUUIDFromBytes(value.toByteArray()).toString().replace("-", "")}"
    }
}
