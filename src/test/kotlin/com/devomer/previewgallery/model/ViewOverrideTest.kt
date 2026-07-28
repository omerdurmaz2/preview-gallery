package com.devomer.previewgallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewOverrideTest {

    @Test fun `an empty override is default`() {
        assertTrue(ViewOverride().isDefault)
    }

    @Test fun `any value makes it non-default`() {
        assertFalse(ViewOverride(mapOf("device" to "id:pixel_7")).isDefault)
    }

    @Test fun `with adds a value without mutating the original`() {
        val base = ViewOverride()
        val next = base.with("fontScale", "1.3")
        assertTrue(base.isDefault)
        assertEquals(mapOf("fontScale" to "1.3"), next.values)
    }

    @Test fun `with replaces an existing value`() {
        val override = ViewOverride().with("fontScale", "1.3").with("fontScale", "2.0")
        assertEquals(mapOf("fontScale" to "2.0"), override.values)
    }

    @Test fun `with keeps the other values`() {
        val override = ViewOverride().with("device", "id:pixel_7").with("fontScale", "1.3")
        assertEquals(2, override.values.size)
        assertEquals("id:pixel_7", override.values["device"])
    }
}
