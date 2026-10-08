package com.jarves.mh.ui

import com.jarves.mh.ui.theme.LightColorRoles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Project rows show the language as a monogram; projects without one keep a plain folder. */
class ProjectBadgeTest {

    private val colors = LightColorRoles

    @Test
    fun knownLanguages_haveTheirMonogramAndColour() {
        assertEquals("Kt" to colors.purple, projectBadge("Kotlin", colors))
        assertEquals("TS" to colors.blue, projectBadge("TypeScript", colors))
        assertEquals("JS" to colors.yellow, projectBadge("JavaScript", colors))
        assertEquals("Py" to colors.green, projectBadge("python", colors))
        assertEquals("J" to colors.red, projectBadge("Java", colors))
        assertEquals("Rs" to colors.brown, projectBadge("Rust", colors))
        assertEquals("Go" to colors.cyan, projectBadge("Go", colors))
    }

    @Test
    fun noLanguage_hasNoBadge_andOthersUseTheirFirstLetters() {
        assertNull(projectBadge("", colors))
        assertNull(projectBadge("General", colors))
        assertNull(projectBadge("  ", colors))
        assertEquals("Sw" to colors.gray, projectBadge("swift", colors))
    }
}
