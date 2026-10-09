package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigMigrationTest {

    @Test fun openRouterRoutesMergeAndReuseSameOriginKey() {
        val migrated = ConfigMigration.migrate(LegacyConfig(
            hasJudge = true,
            judgeProvider = Prefs.PROVIDER_OPENROUTER,
            judgeBaseUrl = Prefs.DEFAULT_JUDGE_BASE_OPENROUTER,
            judgeKey = "shared",
            judgeModel = "typesafe/jev-1.13",
            hasStrategy = false,
            strategyProvider = "jev",
            hasReply = true,
            replyBaseUrl = Prefs.DEFAULT_REPLY_BASE,
            replyKey = "",
            replyModel = "deepseek/chat",
            hasVision = true,
            visionBaseUrl = Prefs.DEFAULT_VISION_BASE,
            visionKey = "",
            visionModel = "qwen/vl"
        ))
        assertEquals(1, migrated.services.size)
        assertEquals(ServiceKind.OPENROUTER, migrated.services.single().kind)
        assertEquals("shared", migrated.services.single().key)
        assertEquals(3, migrated.models.size)
        assertNotNull(migrated.judgeRoute())
        assertEquals("deepseek/chat", migrated.replyRoute()?.modelId)
        assertEquals("qwen/vl", migrated.visionRoute()?.modelId)
        assertTrue(migrated.visionRoute()!!.vision)
    }

    @Test fun activeCompatibleStrategyBecomesJudgeAndOldJevModelRemains() {
        val migrated = ConfigMigration.migrate(LegacyConfig(
            hasJudge = true,
            judgeProvider = Prefs.PROVIDER_OPENROUTER,
            judgeBaseUrl = Prefs.DEFAULT_JUDGE_BASE_OPENROUTER,
            judgeKey = "jev-key",
            judgeModel = "typesafe/jev-1.13",
            hasStrategy = true,
            strategyProvider = Prefs.STRATEGY_COMPATIBLE,
            strategyBaseUrl = "http://127.0.0.1:8317/v1",
            strategyKey = "proxy-key",
            strategyModel = "gpt-test",
            hasReply = true,
            replyBaseUrl = Prefs.DEFAULT_REPLY_BASE,
            replyKey = "reply-key",
            replyModel = "deepseek/chat"
        ))
        val judge = migrated.judgeRoute()
        assertEquals(ModelProtocol.CHAT, judge?.protocol)
        assertEquals("gpt-test", judge?.modelId)
        assertTrue(migrated.models.any { it.modelId == "typesafe/jev-1.13" })
        assertEquals(3, migrated.services.size)
    }

    @Test fun officialDeepSeekKeepsOfficialFlag() {
        val migrated = ConfigMigration.migrate(LegacyConfig(
            hasStrategy = true,
            strategyProvider = "deepseek",
            strategyBaseUrl = Prefs.DEEPSEEK_BASE,
            strategyKey = "deepseek-key",
            strategyModel = Prefs.DEEPSEEK_MODEL
        ))
        assertEquals(ServiceKind.DEEPSEEK, migrated.services.single().kind)
        assertTrue(migrated.judgeRoute()?.officialDeepSeek == true)
    }

    @Test fun emptyLegacyConfigDoesNotCreateDefaults() {
        val migrated = ConfigMigration.migrate(LegacyConfig())
        assertTrue(migrated.services.isEmpty())
        assertTrue(migrated.models.isEmpty())
        assertTrue(migrated.bindings.judgeModelId.isBlank())
    }
}
