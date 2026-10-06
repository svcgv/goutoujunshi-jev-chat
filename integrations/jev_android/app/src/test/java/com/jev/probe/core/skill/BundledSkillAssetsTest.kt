package com.jev.probe.core.skill

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the bundled knowledge base itself: the files must exist at the path the
 * app reads, stay small enough to ship, and actually surface for their own topic.
 * This reads the real `assets/skill` directory so a packaging mistake fails here.
 */
class BundledSkillAssetsTest {

    private val assetsDir = File("src/main/assets/skill")

    private fun bundled(): List<Pair<String, String>> =
        SkillLibrary.FILES.mapNotNull { name ->
            val f = File(assetsDir, name)
            if (f.isFile) name to f.readText() else null
        }

    @Test fun everyAdvertisedFileIsActuallyBundled() {
        val present = bundled().map { it.first }.toSet()
        assertEquals(SkillLibrary.FILES.toSet(), present)
    }

    @Test fun bundledLibraryIsNotEmptyAndBoundedInSize() {
        val docs = bundled()
        assertFalse(docs.isEmpty())
        // Keep the shipped knowledge small; a runaway copy should fail loudly.
        assertTrue("bundled skill exceeded 200 KB", docs.sumOf { it.second.length } < 200_000)
    }

    @Test fun realDocumentsProduceRetrievableSections() {
        val lib = SkillLibrary(bundled())
        assertFalse(lib.isEmpty())
        assertTrue(lib.search("她问周末有没有空，我想约她出来见一面").isNotEmpty())
    }

    @Test fun safetyConversationSurfacesSafetyMaterial() {
        val lib = SkillLibrary(bundled())
        // A safety query must return the safety document, not generic social advice.
        val hits = lib.search("对方一直威胁我，还说要到我单位堵我", limit = 3)
        assertTrue(hits.isNotEmpty())
        val joined = hits.joinToString("\n")
        assertTrue("safety material not retrieved", joined.contains("安全") ||
            joined.contains("法律") || joined.contains("家暴") || joined.contains("危险"))
    }

    @Test fun everyBundledFileContributesAtLeastOneSection() {
        val lib = SkillLibrary(bundled())
        for ((name, _) in bundled()) {
            val sections = lib.splitSections(name, File(assetsDir, name).readText())
            assertTrue("no usable section parsed from $name", sections.isNotEmpty())
        }
    }
}
