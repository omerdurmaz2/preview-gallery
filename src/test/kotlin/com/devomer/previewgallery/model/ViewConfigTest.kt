package com.devomer.previewgallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewConfigTest {

    @Test fun `an empty config is default`() {
        assertTrue(ViewConfig().isDefault)
    }

    @Test fun `any set axis makes it non-default`() {
        assertFalse(ViewConfig(device = DeviceOption("pixel_7", "Pixel 7")).isDefault)
        assertFalse(ViewConfig(theme = ThemeOption.DARK).isDefault)
        assertFalse(ViewConfig(fontScale = 1.3f).isDefault)
    }

    @Test fun `catalog devices are the curated list and font scales are sane`() {
        assertEquals(DeviceCatalog.DEFAULT, ViewSettingsCatalog.DEVICES)
        assertTrue(ViewSettingsCatalog.FONT_SCALES.isNotEmpty())
        assertTrue(ViewSettingsCatalog.FONT_SCALES.all { it > 0f })
        assertTrue(ViewSettingsCatalog.FONT_SCALES.contains(1.0f))
        assertEquals(ViewSettingsCatalog.FONT_SCALES.size, ViewSettingsCatalog.FONT_SCALES.toSet().size)
    }

    @Test fun `theme options carry display labels`() {
        assertEquals("Light", ThemeOption.LIGHT.label)
        assertEquals("Dark", ThemeOption.DARK.label)
    }
}
