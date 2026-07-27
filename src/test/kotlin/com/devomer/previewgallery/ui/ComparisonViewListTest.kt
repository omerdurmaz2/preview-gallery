package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.DeviceOption
import com.devomer.previewgallery.model.ViewConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonViewListTest {

    private val pixel7 = DeviceOption("pixel_7", "Pixel 7")
    private val tablet = DeviceOption("pixel_tablet", "Pixel Tablet")

    @Test fun `starts with only Original at index 0 with a default config`() {
        val list = ComparisonViewList()
        assertEquals(1, list.views.size)
        assertEquals(ComparisonViewList.ORIGINAL_ID, list.views[0].id)
        assertTrue(list.views[0].config.isDefault)
    }

    @Test fun `add appends an extra view carrying the given config`() {
        val list = ComparisonViewList()
        val v = list.add(ViewConfig(device = pixel7))
        assertNotNull(v)
        assertEquals(2, list.views.size)
        assertEquals(ViewConfig(device = pixel7), list.views[1].config)
    }

    @Test fun `add returns null once the extras cap is reached`() {
        val list = ComparisonViewList(maxExtras = 2)
        assertNotNull(list.add(ViewConfig(device = pixel7)))
        assertNotNull(list.add(ViewConfig(device = tablet)))
        assertNull(list.add(ViewConfig(device = pixel7)))          // third extra rejected
        assertEquals(3, list.views.size)       // Original + 2 extras
    }

    @Test fun `close removes an extra but never Original`() {
        val list = ComparisonViewList()
        val v = list.add(ViewConfig(device = pixel7))
        val id = checkNotNull(v).id
        list.close(id)
        assertEquals(1, list.views.size)
        list.close(ComparisonViewList.ORIGINAL_ID)   // no-op
        assertEquals(1, list.views.size)
    }

    @Test fun `setConfig changes an extra's config and ignores Original`() {
        val list = ComparisonViewList()
        val id = checkNotNull(list.add(ViewConfig(device = pixel7))).id
        list.setConfig(id, ViewConfig(device = tablet))
        assertEquals(ViewConfig(device = tablet), list.views[1].config)
        list.setConfig(ComparisonViewList.ORIGINAL_ID, ViewConfig(device = tablet))   // ignored
        assertTrue(list.views[0].config.isDefault)
    }

    @Test fun `clearExtras returns to Original only`() {
        val list = ComparisonViewList()
        list.add(ViewConfig(device = pixel7))
        list.add(ViewConfig(device = tablet))
        list.clearExtras()
        assertEquals(1, list.views.size)
        assertEquals(ComparisonViewList.ORIGINAL_ID, list.views[0].id)
        assertTrue(list.views[0].config.isDefault)
    }

    @Test fun `extra view ids are distinct and not reused after close`() {
        val list = ComparisonViewList()
        val first = checkNotNull(list.add(ViewConfig(device = pixel7))).id
        list.close(first)
        val second = checkNotNull(list.add(ViewConfig(device = tablet))).id
        assertEquals(2, list.views.size)          // Original + the new extra
        org.junit.Assert.assertNotEquals(first, second)
    }
}
