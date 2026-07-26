package com.devomer.previewgallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCatalogTest {

    @Test fun `default catalog is non-empty`() {
        assertTrue(DeviceCatalog.DEFAULT.isNotEmpty())
    }

    @Test fun `default catalog ids are unique`() {
        val ids = DeviceCatalog.DEFAULT.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `default catalog entries have non-blank id and label`() {
        assertTrue(DeviceCatalog.DEFAULT.all { it.id.isNotBlank() && it.label.isNotBlank() })
    }
}
