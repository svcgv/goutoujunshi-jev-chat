package com.jev.probe.core

import com.jev.probe.coach.CoachTask
import java.net.URI

/** The request shape a model speaks. A service may expose one or both. */
enum class ModelProtocol(val wire: String, val label: String) {
    JEV("jev", "Jev 判断"),
    CHAT("chat", "OpenAI 兼容聊天");

    companion object {
        fun fromWire(value: String?): ModelProtocol? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * Provider presets. The base URL is normalized per preset so a service can
 * safely own both a Jev endpoint and a chat endpoint without duplicating keys.
 */
enum class ServiceKind(
    val wire: String,
    val label: String,
    val protocols: Set<ModelProtocol>,
    val defaultBaseUrl: String,
    val supportsModelList: Boolean
) {
    OPENROUTER("openrouter", "OpenRouter", setOf(ModelProtocol.JEV, ModelProtocol.CHAT),
        "https://openrouter.ai/api", true),
    TYPESAFE("typesafe", "TypeSafe", setOf(ModelProtocol.JEV),
        "https://api.typesafe.ai", false),
    DEEPSEEK("deepseek", "DeepSeek 官方", setOf(ModelProtocol.CHAT),
        "https://api.deepseek.com/v1", true),
    DASHSCOPE("dashscope", "通义兼容", setOf(ModelProtocol.CHAT),
        "https://dashscope.aliyuncs.com/compatible-mode/v1", true),
    COMPATIBLE("compatible", "CLI-Proxy-API / 兼容", setOf(ModelProtocol.CHAT),
        "http://127.0.0.1:8317/v1", true),
    CUSTOM_JEV("custom_jev", "自定义 Jev", setOf(ModelProtocol.JEV), "", false);

    companion object {
        fun fromWire(value: String?): ServiceKind? =
            entries.firstOrNull { it.wire == value }
    }
}

data class ServiceConfig(
    val id: String,
    val name: String,
    val kind: ServiceKind,
    val baseUrl: String,
    val key: String = ""
) {
    fun endpoint(protocol: ModelProtocol): String {
        require(protocol in kind.protocols) { "${kind.label} 不支持 ${protocol.label} 协议" }
        val base = normalizeBase(kind, baseUrl)
        return when (kind) {
            ServiceKind.OPENROUTER -> if (protocol == ModelProtocol.JEV)
                if (base.endsWith("/alpha/decisions")) base else "$base/alpha/decisions"
            else StrategyRoute.endpoint(if (base.endsWith("/v1")) "$base" else "$base/v1")
            ServiceKind.TYPESAFE -> if (base.endsWith("/v1/systemone")) base
                else "$base/v1/systemone"
            ServiceKind.DEEPSEEK, ServiceKind.DASHSCOPE, ServiceKind.COMPATIBLE ->
                StrategyRoute.endpoint(base)
            ServiceKind.CUSTOM_JEV -> validateFullEndpoint(base, "Jev 接口")
        }
    }

    /** Standard OpenAI-compatible `/models` endpoint, when the preset has one. */
    fun modelsEndpoint(): String? {
        if (!kind.supportsModelList) return null
        val base = normalizeBase(kind, baseUrl).trimEnd('/')
        return when (kind) {
            ServiceKind.OPENROUTER -> "$base/v1/models"
            ServiceKind.DEEPSEEK, ServiceKind.DASHSCOPE, ServiceKind.COMPATIBLE ->
                "${base.removeSuffix("/chat/completions")}/models"
            else -> null
        }
    }

    fun withBase(raw: String): ServiceConfig = copy(baseUrl = normalizeBase(kind, raw))

    companion object {
        fun normalizeBase(kind: ServiceKind, raw: String): String {
            val value = raw.trim().trimEnd('/')
            if (value.isBlank()) return ""
            var base = when (kind) {
                ServiceKind.OPENROUTER -> value.removeSuffix("/v1/chat/completions")
                    .removeSuffix("/v1")
                ServiceKind.COMPATIBLE -> value.removeSuffix("/chat/completions")
                else -> value
            }.trimEnd('/')
            if (kind == ServiceKind.OPENROUTER) {
                val uri = runCatching { URI(base) }.getOrNull()
                if (uri?.host?.equals("openrouter.ai", ignoreCase = true) == true &&
                    uri.path.orEmpty().trim('/').isBlank()) base = "$base/api"
            }
            validateBase(base, kind.label)
            return base
        }

        private fun validateBase(value: String, label: String) {
            val uri = URI(value)
            require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank())
                { "$label 地址必须是有效的 HTTP(S) 地址" }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null)
                { "$label 地址不能包含用户名、查询参数或片段" }
        }

        private fun validateFullEndpoint(value: String, label: String): String {
            validateBase(value, label)
            return value
        }
    }
}

data class ModelConfig(
    val id: String,
    val serviceId: String,
    val modelId: String,
    val displayName: String = modelId,
    val protocol: ModelProtocol,
    val vision: Boolean = false
) {
    val label: String get() = displayName.trim().ifBlank { modelId }
}

data class InterfaceBindings(
    val judgeModelId: String = "",
    val replyModelId: String = "",
    val visionModelId: String = ""
)

/** Blank means "inherit the global reply model" for the three coach tasks. */
data class FeatureModelOverrides(
    val openModelId: String = "",
    val endModelId: String = "",
    val consultModelId: String = ""
) {
    fun forTask(task: CoachTask): String = when (task) {
        CoachTask.OPEN -> openModelId
        CoachTask.END -> endModelId
        CoachTask.CONSULT -> consultModelId
        CoachTask.REPLY -> ""
    }

    fun withTask(task: CoachTask, modelId: String): FeatureModelOverrides = when (task) {
        CoachTask.OPEN -> copy(openModelId = modelId)
        CoachTask.END -> copy(endModelId = modelId)
        CoachTask.CONSULT -> copy(consultModelId = modelId)
        CoachTask.REPLY -> this
    }
}

data class ResolvedRoute(
    val serviceId: String,
    val serviceName: String,
    val modelRefId: String,
    val modelId: String,
    val modelName: String,
    val endpoint: String,
    val key: String,
    val protocol: ModelProtocol,
    val officialDeepSeek: Boolean = false,
    val vision: Boolean = false
) {
    val ready: Boolean get() = endpoint.isNotBlank() && modelId.isNotBlank() && key.isNotBlank()
}

data class ModelConfigSnapshot(
    val version: Int = VERSION,
    val services: List<ServiceConfig> = emptyList(),
    val models: List<ModelConfig> = emptyList(),
    val bindings: InterfaceBindings = InterfaceBindings(),
    val overrides: FeatureModelOverrides = FeatureModelOverrides()
) {
    fun service(id: String?): ServiceConfig? = services.firstOrNull { it.id == id }

    fun model(id: String?): ModelConfig? = models.firstOrNull { it.id == id }

    fun resolve(modelRefId: String?): ResolvedRoute? {
        val model = model(modelRefId) ?: return null
        val service = service(model.serviceId) ?: return null
        val endpoint = runCatching { service.endpoint(model.protocol) }.getOrElse { return null }
        return ResolvedRoute(
            serviceId = service.id,
            serviceName = service.name,
            modelRefId = model.id,
            modelId = model.modelId,
            modelName = model.label,
            endpoint = endpoint,
            key = service.key,
            protocol = model.protocol,
            officialDeepSeek = service.kind == ServiceKind.DEEPSEEK,
            vision = model.vision
        )
    }

    fun judgeRoute(): ResolvedRoute? = resolve(bindings.judgeModelId)

    fun replyRoute(): ResolvedRoute? = resolve(bindings.replyModelId)

    fun visionRoute(): ResolvedRoute? = resolve(bindings.visionModelId)

    fun featureRoute(task: CoachTask): ResolvedRoute? {
        val override = overrides.forTask(task)
        return if (override.isNotBlank()) resolve(override) else replyRoute()
    }

    fun modelOptions(protocol: ModelProtocol? = null, visionOnly: Boolean = false): List<ModelConfig> =
        models.filter { (protocol == null || it.protocol == protocol) && (!visionOnly || it.vision) }
            .sortedWith(compareBy<ModelConfig> { service(it.serviceId)?.name.orEmpty() }
                .thenBy { it.label }.thenBy { it.modelId })

    fun referencesForService(serviceId: String): List<String> = buildList {
        val modelIds = models.filter { it.serviceId == serviceId }.map { it.id }.toSet()
        listOf(bindings.judgeModelId, bindings.replyModelId, bindings.visionModelId,
            overrides.openModelId, overrides.endModelId, overrides.consultModelId)
            .filter { it in modelIds }.forEach { add(it) }
    }

    fun referencesForModel(modelId: String): List<String> = buildList {
        if (bindings.judgeModelId == modelId) add("判断模型")
        if (bindings.replyModelId == modelId) add("回复模型")
        if (bindings.visionModelId == modelId) add("视觉模型")
        if (overrides.openModelId == modelId) add("发起聊天")
        if (overrides.endModelId == modelId) add("暂时离开会话")
        if (overrides.consultModelId == modelId) add("问军师")
    }

    fun validate() {
        require(version == VERSION) { "模型配置版本不受支持" }
        require(services.map { it.id }.distinct().size == services.size) { "服务 ID 重复" }
        require(models.map { it.id }.distinct().size == models.size) { "模型 ID 重复" }
        services.forEach { service ->
            require(service.id.isNotBlank() && service.name.isNotBlank()) { "服务名称不能为空" }
            require(service.baseUrl.isBlank() || runCatching {
                ServiceConfig.normalizeBase(service.kind, service.baseUrl)
            }.isSuccess) { "${service.name} 的服务地址无效" }
        }
        models.forEach { model ->
            val service = service(model.serviceId)
                ?: throw IllegalArgumentException("模型引用了不存在的服务")
            require(model.id.isNotBlank() && model.modelId.isNotBlank()) { "模型 ID 不能为空" }
            require(model.protocol in service.kind.protocols) {
                "${service.name} 不支持 ${model.protocol.label}"
            }
            require(!model.vision || model.protocol == ModelProtocol.CHAT) {
                "只有聊天模型可以标记图片能力"
            }
        }
        validateBinding(bindings.judgeModelId, "判断模型", null, false)
        validateBinding(bindings.replyModelId, "回复模型", ModelProtocol.CHAT, false)
        validateBinding(bindings.visionModelId, "视觉模型", ModelProtocol.CHAT, true)
        validateBinding(overrides.openModelId, "发起聊天", ModelProtocol.CHAT, false)
        validateBinding(overrides.endModelId, "暂时离开会话", ModelProtocol.CHAT, false)
        validateBinding(overrides.consultModelId, "问军师", ModelProtocol.CHAT, false)
    }

    private fun validateBinding(id: String, label: String,
                                protocol: ModelProtocol?, visionOnly: Boolean) {
        if (id.isBlank()) return
        val model = model(id) ?: throw IllegalArgumentException("$label 引用了不存在的模型")
        if (protocol != null) require(model.protocol == protocol) { "$label 需要聊天模型" }
        if (visionOnly) require(model.vision) { "视觉模型必须标记为支持图片" }
    }

    companion object { const val VERSION = 1 }
}
