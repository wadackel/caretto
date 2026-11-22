package dev.wadackel.caretto.ui.about

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourceNoticesTest {

    @Test
    fun theVersionNameCarriesTheCommitAndWhetherTheTreeWasDirty() {
        assertEquals(BuildInfo("0.1.0", "e984f34", dirty = false), BuildInfo.parse("0.1.0+e984f34"))
        assertEquals(BuildInfo("0.1.0", "e984f34", dirty = true), BuildInfo.parse("0.1.0+e984f34-dirty"))
        assertNull(BuildInfo.parse("0.1.0").commit)
        assertEquals("", BuildInfo.parse(null).version)
    }

    @Test
    fun aParagraphJoinsWhileHeadingsAndListItemsKeepTheirLines() {
        val text = "TERMS AND CONDITIONS\n\nThe first line of a paragraph\ngoes on here.\n- an item\n- another"
        assertEquals(
            "TERMS AND CONDITIONS\n\nThe first line of a paragraph goes on here.\n- an item\n- another",
            reflowLicense(text),
        )
    }

    @Test
    fun groupsAreNamedForReaders() {
        assertEquals("Compose UI", groupName("androidx.compose.ui", listOf("UI")))
        assertEquals("AndroidX Arch Core", groupName("androidx.arch.core", listOf("Core")))
        assertEquals("Kotlin Coroutines", groupName("org.jetbrains.kotlinx", listOf("a", "b")))
        assertEquals("Thing", groupName("com.example.thing", listOf("Thing")))
    }

    @Test
    fun aGroupKeepsEveryLicenceItsArtifactsCarry() {
        val json = """{"licenses":{"MIT":{"name":"MIT License","content":"m"},"Apache-2.0":{"name":"Apache License 2.0","content":"a"}},
            "libraries":[{"uniqueId":"g.x:one","licenses":["Apache-2.0"]},{"uniqueId":"g.x:two","licenses":["MIT","Apache-2.0"]},{"uniqueId":"g.x:three","licenses":[]}]}"""
        val notice = parseLibraryDefinitions(json).single()
        assertEquals(listOf("Apache License 2.0", "MIT License"), notice.licenses.map { it.name })
        assertEquals("Apache 2.0 / MIT", notice.label)
    }

    /**
     * Reads the list this build actually generated, so a dependency whose licence has no text in
     * app/aboutlibraries/licenses fails here instead of shipping an empty notice.
     */
    @Test
    fun everyLibraryTheBuildShipsHasItsLicenceText() {
        val path = System.getProperty("caretto.libraryDefinitions")
        val notices = parseLibraryDefinitions(File(path).readText())
        assertTrue(notices.isNotEmpty())
        for (notice in notices) {
            assertTrue("${notice.name} has no licence", notice.licenses.isNotEmpty())
            notice.licenses.forEach { assertTrue("${notice.name}: ${it.name} has no text", it.text.isNotEmpty()) }
        }
        assertTrue(notices.any { it.name == "AndroidX Lifecycle" })
        assertFalse(notices.any { it.parts.any { part -> part.contains("-bom") } })
    }
}
