package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.DeviceOption
import com.devomer.previewgallery.model.ThemeOption
import com.devomer.previewgallery.model.ViewConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewTitleTest {

    private val pixel7 = DeviceOption("pixel_7", "Pixel 7")

    @Test fun `the original view is titled Original`() {
        assertEquals("Original", ViewTitle.of(ComparisonView(ComparisonViewList.ORIGINAL_ID, ViewConfig()), 0))
    }

    @Test fun `an unconfigured copy is titled by its tab position`() {
        assertEquals("View 2", ViewTitle.of(ComparisonView(1, ViewConfig()), 1))
        assertEquals("View 3", ViewTitle.of(ComparisonView(2, ViewConfig()), 2))
    }

    @Test fun `a single axis titles just that axis`() {
        assertEquals("Pixel 7", ViewTitle.of(ComparisonView(1, ViewConfig(device = pixel7)), 1))
        assertEquals("Dark", ViewTitle.of(ComparisonView(1, ViewConfig(theme = ThemeOption.DARK)), 1))
        assertEquals("1.3×", ViewTitle.of(ComparisonView(1, ViewConfig(fontScale = 1.3f)), 1))
    }

    @Test fun `whole-number font scales drop the decimal`() {
        assertEquals("1×", ViewTitle.of(ComparisonView(1, ViewConfig(fontScale = 1.0f)), 1))
        assertEquals("2×", ViewTitle.of(ComparisonView(1, ViewConfig(fontScale = 2.0f)), 1))
        assertEquals("0.85×", ViewTitle.of(ComparisonView(1, ViewConfig(fontScale = 0.85f)), 1))
    }

    @Test fun `several axes are joined in device theme scale order`() {
        val config = ViewConfig(device = pixel7, theme = ThemeOption.DARK, fontScale = 1.3f)
        assertEquals("Pixel 7 · Dark · 1.3×", ViewTitle.of(ComparisonView(1, config), 1))
    }
}
