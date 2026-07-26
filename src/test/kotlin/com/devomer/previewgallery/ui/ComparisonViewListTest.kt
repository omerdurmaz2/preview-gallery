package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.DeviceOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ComparisonViewListTest {

    private val pixel7 = DeviceOption("pixel_7", "Pixel 7")
    private val tablet = DeviceOption("pixel_tablet", "Pixel Tablet")

    @Test fun `starts with only Original at index 0 with no device`() {
        val list = ComparisonViewList()
        assertEquals(1, list.views.size)
        assertEquals(ComparisonViewList.ORIGINAL_ID, list.views[0].id)
        assertNull(list.views[0].device)
    }

    @Test fun `add appends an extra view carrying the given device`() {
        val list = ComparisonViewList()
        val v = list.add(pixel7)
        assertNotNull(v)
        assertEquals(2, list.views.size)
        assertEquals(pixel7, list.views[1].device)
    }

    @Test fun `add returns null once the extras cap is reached`() {
        val list = ComparisonViewList(maxExtras = 2)
        assertNotNull(list.add(pixel7))
        assertNotNull(list.add(tablet))
        assertNull(list.add(pixel7))          // third extra rejected
        assertEquals(3, list.views.size)       // Original + 2 extras
    }

    @Test fun `close removes an extra but never Original`() {
        val list = ComparisonViewList()
        val v = list.add(pixel7)
        val id = checkNotNull(v).id
        list.close(id)
        assertEquals(1, list.views.size)
        list.close(ComparisonViewList.ORIGINAL_ID)   // no-op
        assertEquals(1, list.views.size)
    }

    @Test fun `setDevice changes an extra's device and ignores Original`() {
        val list = ComparisonViewList()
        val id = checkNotNull(list.add(pixel7)).id
        list.setDevice(id, tablet)
        assertEquals(tablet, list.views[1].device)
        list.setDevice(ComparisonViewList.ORIGINAL_ID, tablet)   // ignored
        assertNull(list.views[0].device)
    }

    @Test fun `clearExtras returns to Original only`() {
        val list = ComparisonViewList()
        list.add(pixel7)
        list.add(tablet)
        list.clearExtras()
        assertEquals(1, list.views.size)
        assertEquals(ComparisonViewList.ORIGINAL_ID, list.views[0].id)
        assertNull(list.views[0].device)
    }

    @Test fun `extra view ids are distinct and not reused after close`() {
        val list = ComparisonViewList()
        val first = checkNotNull(list.add(pixel7)).id
        list.close(first)
        val second = checkNotNull(list.add(tablet)).id
        assertEquals(2, list.views.size)          // Original + the new extra
        org.junit.Assert.assertNotEquals(first, second)
    }
}
