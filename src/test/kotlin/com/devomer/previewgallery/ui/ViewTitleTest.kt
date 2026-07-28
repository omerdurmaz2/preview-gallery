package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.ViewOverride
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewTitleTest {

    @Test fun `the original view is titled Original`() {
        assertEquals("Original", ViewTitle.of(ComparisonView(ComparisonViewList.ORIGINAL_ID, ViewOverride()), 0))
    }

    @Test fun `an untouched copy is titled by its tab position`() {
        assertEquals("View 2", ViewTitle.of(ComparisonView(1, ViewOverride()), 1))
        assertEquals("View 3", ViewTitle.of(ComparisonView(2, ViewOverride()), 2))
    }

    @Test fun `a single override is summarised as name and value`() {
        val view = ComparisonView(1, ViewOverride(mapOf("fontScale" to "1.3")))
        assertEquals("fontScale 1.3", ViewTitle.of(view, 1))
    }

    @Test fun `several overrides are joined in insertion order`() {
        val override = ViewOverride().with("device", "Pixel 7").with("fontScale", "1.3")
        assertEquals("device Pixel 7 · fontScale 1.3", ViewTitle.of(ComparisonView(1, override), 1))
    }
}
