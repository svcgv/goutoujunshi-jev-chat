package com.jev.probe.core.skill

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillManifestTest {
    private val dir = File("src/main/assets/skill")

    @Test fun manifestCoversEveryBundledFileWithMatchingHash() {
        val manifest = JSONObject(File(dir, "manifest.json").readText())
        assertEquals("2026-10-10", manifest.getString("version"))
        val rows = manifest.getJSONArray("files")
        val names = (0 until rows.length()).map { rows.getJSONObject(it).getString("name") }.toSet()
        assertEquals(SkillLibrary.FILES.toSet(), names)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val bytes = File(dir, row.getString("name")).readBytes()
            assertEquals(row.getLong("bytes"), bytes.size.toLong())
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals(row.getString("sha256"), digest)
            assertTrue(digest.length == 64)
        }
    }
}
