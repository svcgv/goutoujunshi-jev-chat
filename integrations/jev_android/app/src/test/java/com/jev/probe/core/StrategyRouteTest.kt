package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StrategyRouteTest {
    @Test fun appendsToProxyBase() {
        assertEquals("http://127.0.0.1:8317/v1/chat/completions",
            StrategyRoute.endpoint(" http://127.0.0.1:8317/v1/ "))
    }
    @Test fun preservesCustomPathAndFullEndpoint() {
        assertEquals("https://proxy.example/api/v1/chat/completions",
            StrategyRoute.endpoint("https://proxy.example/api/v1"))
        assertEquals("https://proxy.example/v1/chat/completions",
            StrategyRoute.endpoint("https://proxy.example/v1/chat/completions/"))
    }
    @Test fun officialEndpointIsNotDoubled() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
            StrategyRoute.endpoint("https://api.deepseek.com/v1"))
    }
    @Test fun rejectsInvalidOrCredentialBearingUrls() {
        for (url in listOf("", "localhost:8317/v1", "file:///tmp/model", "https://u:p@proxy.example/v1",
                           "https://proxy.example/v1?key=secret", "https://proxy.example/v1#fragment")) {
            try {
                StrategyRoute.endpoint(url)
                org.junit.Assert.fail("accepted invalid URL")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
