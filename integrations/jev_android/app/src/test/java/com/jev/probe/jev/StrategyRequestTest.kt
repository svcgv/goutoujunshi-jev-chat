package com.jev.probe.jev

import org.junit.Assert.*
import org.junit.Test

class StrategyRequestTest {
    @Test fun proxyDeepSeekAndGptUseOnlyPortableFields() {
        for (model in listOf("deepseek-chat", "gpt-test-alias", "custom-reasoning-alias")) {
            val body = StrategyRequest.body(model, "system", "user", false, json = true)
            assertEquals(setOf("model", "messages", "stream"), body.keySet())
            assertEquals(model, body.getString("model"))
            assertFalse(body.getBoolean("stream"))
            assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
            assertEquals("user", body.getJSONArray("messages").getJSONObject(1).getString("content"))
        }
    }
    @Test fun officialDeepSeekRetainsJsonAndThinkingOptions() {
        val body = StrategyRequest.body("deepseek-flash", "system", "user", true, json = true)
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertFalse(body.has("logprobs"))
        assertEquals(900, body.getInt("max_tokens"))
    }
    @Test fun officialTokenChoiceDoesNotRequestJson() {
        val body = StrategyRequest.body("deepseek-flash", "system", "user", true, choice = true)
        assertTrue(body.getBoolean("logprobs"))
        assertEquals(20, body.getInt("top_logprobs"))
        assertFalse(body.has("response_format"))
    }
    @Test(expected = IllegalArgumentException::class) fun blankModelIsRejected() {
        StrategyRequest.body(" ", "system", "user", false)
    }
}
