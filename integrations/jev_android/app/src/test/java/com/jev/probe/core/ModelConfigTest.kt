package com.jev.probe.core

import com.jev.probe.coach.CoachTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigTest {

    private val openRouter = ServiceConfig(
        id = "svc-or", name = "OpenRouter", kind = ServiceKind.OPENROUTER,
        baseUrl = "https://openrouter.ai/api", key = "shared")
    private val jev = ModelConfig(
        id = "m-jev", serviceId = openRouter.id, modelId = "typesafe/jev-1.13",
        displayName = "Jev", protocol = ModelProtocol.JEV)
    private val chat = ModelConfig(
        id = "m-chat", serviceId = openRouter.id, modelId = "deepseek/chat",
        displayName = "DeepSeek", protocol = ModelProtocol.CHAT)
    private val vision = ModelConfig(
        id = "m-vision", serviceId = openRouter.id, modelId = "qwen/vl",
        displayName = "Qwen VL", protocol = ModelProtocol.CHAT, vision = true)

    @Test fun openRouterBuildsBothProtocolsFromOneBase() {
        assertEquals("https://openrouter.ai/api",
            ServiceConfig.normalizeBase(ServiceKind.OPENROUTER, "https://openrouter.ai/v1"))
        assertEquals("https://openrouter.ai/api/alpha/decisions", openRouter.endpoint(ModelProtocol.JEV))
        assertEquals("https://openrouter.ai/api/v1/chat/completions", openRouter.endpoint(ModelProtocol.CHAT))
        assertEquals("https://openrouter.ai/api/v1/models", openRouter.modelsEndpoint())
    }

    @Test fun compatibleFullEndpointDoesNotDoubleAppend() {
        val service = ServiceConfig(
            id = "svc", name = "Proxy", kind = ServiceKind.COMPATIBLE,
            baseUrl = "http://127.0.0.1:8317/v1/chat/completions/", key = "k")
        assertEquals("http://127.0.0.1:8317/v1/chat/completions",
            service.endpoint(ModelProtocol.CHAT))
        assertEquals("http://127.0.0.1:8317/v1/models", service.modelsEndpoint())
    }

    @Test fun featureOverrideFallsBackToGlobalReply() {
        val snapshot = ModelConfigSnapshot(
            services = listOf(openRouter), models = listOf(jev, chat, vision),
            bindings = InterfaceBindings(judgeModelId = jev.id, replyModelId = chat.id))
        assertEquals(chat.id, snapshot.featureRoute(CoachTask.OPEN)?.modelRefId)
        val overridden = snapshot.copy(overrides = FeatureModelOverrides(openModelId = vision.id))
        assertEquals(vision.id, overridden.featureRoute(CoachTask.OPEN)?.modelRefId)
        assertEquals(chat.id, overridden.featureRoute(CoachTask.CONSULT)?.modelRefId)
        assertNull(overridden.featureRoute(CoachTask.REPLY)?.takeIf { it.modelRefId != chat.id })
    }

    @Test fun visionBindingRequiresImageCapability() {
        val invalid = ModelConfigSnapshot(
            services = listOf(openRouter), models = listOf(chat),
            bindings = InterfaceBindings(visionModelId = chat.id))
        assertTrue(runCatching { invalid.validate() }.isFailure)
        val valid = invalid.copy(models = listOf(vision), bindings = InterfaceBindings(visionModelId = vision.id))
        runCatching { valid.validate() }.getOrThrow()
    }

    @Test fun filtersAndReferencesAreStable() {
        val snapshot = ModelConfigSnapshot(
            services = listOf(openRouter), models = listOf(jev, chat, vision),
            bindings = InterfaceBindings(judgeModelId = jev.id, replyModelId = chat.id),
            overrides = FeatureModelOverrides(openModelId = vision.id))
        assertEquals(listOf(chat.id, vision.id), snapshot.modelOptions(ModelProtocol.CHAT).map { it.id })
        assertEquals(listOf(vision.id), snapshot.modelOptions(ModelProtocol.CHAT, visionOnly = true).map { it.id })
        assertTrue(snapshot.referencesForModel(chat.id).contains("回复模型"))
        assertEquals(setOf(chat.id, jev.id, vision.id), snapshot.referencesForService(openRouter.id).toSet())
        assertFalse(snapshot.referencesForModel("missing").isNotEmpty())
    }

    @Test fun jsonRoundTripKeepsBindingsAndOverrides() {
        val original = ModelConfigSnapshot(
            services = listOf(openRouter), models = listOf(jev, chat, vision),
            bindings = InterfaceBindings(jev.id, chat.id, vision.id),
            overrides = FeatureModelOverrides(openModelId = vision.id))
        val decoded = ModelConfigStore.decode(ModelConfigStore.encode(original))
        assertEquals(original, decoded)
        decoded.validate()
    }

    @Test fun serviceProtocolMismatchIsRejected() {
        val typeSafe = ServiceConfig(
            id = "ts", name = "TypeSafe", kind = ServiceKind.TYPESAFE,
            baseUrl = Prefs.DEFAULT_JUDGE_BASE_TYPESAFE, key = "k")
        val invalid = ModelConfigSnapshot(
            services = listOf(typeSafe),
            models = listOf(ModelConfig(id = "m", serviceId = "ts", modelId = "chat",
                protocol = ModelProtocol.CHAT)))
        assertTrue(runCatching { invalid.validate() }.isFailure)
    }
}
