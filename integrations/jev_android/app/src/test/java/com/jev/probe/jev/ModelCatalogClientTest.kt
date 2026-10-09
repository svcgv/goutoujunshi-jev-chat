package com.jev.probe.jev

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelCatalogClientTest {
    @Test fun parsesStandardListAndDeduplicatesIds() {
        val root = JSONObject("""
            {"data":[
              {"id":"model-a"},
              {"id":"model-a"},
              {"id":"model-b","capabilities":{"vision":true}}
            ]}
        """.trimIndent())
        val rows = ModelCatalogClient.parseEntries(root)
        assertEquals(listOf("model-a", "model-b"), rows.map { it.id })
        assertNull(rows[0].vision)
        assertEquals(true, rows[1].vision)
    }

    @Test fun parsesOpenRouterInputModalities() {
        val root = JSONObject("""
            {"data":[
              {"id":"vl","architecture":{"input_modalities":["text","image"]}},
              {"id":"text","architecture":{"input_modalities":["text"]}}
            ]}
        """.trimIndent())
        val rows = ModelCatalogClient.parseEntries(root)
        assertEquals(true, rows[0].vision)
        assertEquals(false, rows[1].vision)
    }

    @Test fun supportsModelsAliasAndSkipsInvalidRows() {
        val rows = ModelCatalogClient.parseEntries(JSONObject(
            """{"models":[{"id":"one"},{"name":"fallback"},{}]}"""))
        assertEquals(listOf("one", "fallback"), rows.map { it.id })
    }
}
