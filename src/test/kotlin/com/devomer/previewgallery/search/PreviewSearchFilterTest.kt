package com.devomer.previewgallery.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSearchFilterTest {

    @Test
    fun `an empty query matches everything`() {
        assertTrue(PreviewSearchFilter.matches(testRow(), ""))
        assertTrue(PreviewSearchFilter.matches(testRow(), "   "))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(PreviewSearchFilter.matches(testRow(displayName = "PrimusTabs"), "primustabs"))
        assertTrue(PreviewSearchFilter.matches(testRow(displayName = "primustabs"), "PRIMUSTABS"))
    }

    @Test
    fun `matching is substring, not prefix`() {
        assertTrue(PreviewSearchFilter.matches(testRow(displayName = "PrimusTabsPreview"), "Tabs"))
    }

    @Test
    fun `matches the function name when the display name differs`() {
        assertTrue(PreviewSearchFilter.matches(testRow(displayName = "Dark", functionName = "TabsPreview"), "tabs"))
    }

    @Test
    fun `matches the package name`() {
        assertTrue(PreviewSearchFilter.matches(testRow(packageName = "com.example.tabs"), "tabs"))
    }

    @Test
    fun `does not match unrelated text`() {
        assertFalse(PreviewSearchFilter.matches(testRow(), "zzz"))
    }

    @Test
    fun `the query is trimmed`() {
        assertTrue(PreviewSearchFilter.matches(testRow(displayName = "PrimusTabs"), "  Tabs  "))
    }

    @Test
    fun `filter keeps only matching rows`() {
        val rows = listOf(testRow(displayName = "TabsPreview"), testRow(displayName = "ButtonPreview"))
        assertEquals(listOf(rows.first()), PreviewSearchFilter.filter(rows, "tabs"))
    }

    @Test
    fun `module filter is a no-op when disabled`() {
        val rows = listOf(testRow(moduleName = "app"), testRow(moduleName = "design"))
        assertEquals(rows, PreviewModuleFilter.apply(rows, "app", enabled = false))
    }

    @Test
    fun `module filter keeps only the active module`() {
        val rows = listOf(testRow(moduleName = "app"), testRow(moduleName = "design"))
        assertEquals(listOf(rows.first()), PreviewModuleFilter.apply(rows, "app", enabled = true))
    }

    @Test
    fun `module filter with no active module yields nothing`() {
        val rows = listOf(testRow(moduleName = "app"))
        assertTrue(PreviewModuleFilter.apply(rows, null, enabled = true).isEmpty())
    }
}
