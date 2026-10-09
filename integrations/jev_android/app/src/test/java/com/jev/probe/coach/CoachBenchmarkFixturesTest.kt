package com.jev.probe.coach

import org.json.JSONObject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachBenchmarkFixturesTest {
    private val file = File("src/test/resources/coach_benchmark_cases.json")

    @Test fun benchmarkHasThirtyCompleteSyntheticCases() {
        val root = JSONObject(file.readText())
        val cases = root.getJSONArray("cases")
        assertTrue(cases.length() >= 30)
        val ids = mutableSetOf<String>()
        val categories = mutableSetOf<String>()
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i)
            assertTrue(row.getString("id").isNotBlank())
            assertTrue(ids.add(row.getString("id")))
            categories.add(row.getString("category"))
            assertTrue(row.getString("task") in setOf("reply", "open", "end", "consult"))
            assertTrue(row.getString("user_goal").isNotBlank())
            assertTrue(row.getJSONArray("expected_flags").length() > 0)
        }
        assertEquals(setOf("open", "end", "reply", "consult", "safety"), categories)
    }
}
