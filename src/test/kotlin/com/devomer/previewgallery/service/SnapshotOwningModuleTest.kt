package com.devomer.previewgallery.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotOwningModuleTest {

    @Test
    fun `the main source-set module wins`() {
        assertEquals(
            "app.features.favorites.ui.main",
            SnapshotSourceScanner.pickOwningModule(
                listOf("app.features.favorites.ui", "app.features.favorites.ui.main"),
            ),
        )
    }

    @Test
    fun `the shortest name wins when no module is a main source set`() {
        assertEquals(
            "app.features.favorites.ui",
            SnapshotSourceScanner.pickOwningModule(
                listOf("app.features.favorites.ui.unitTest", "app.features.favorites.ui"),
            ),
        )
    }

    @Test
    fun `a single module is its own owner`() {
        assertEquals("app", SnapshotSourceScanner.pickOwningModule(listOf("app")))
    }

    @Test
    fun `no modules means no owner`() {
        assertNull(SnapshotSourceScanner.pickOwningModule(emptyList()))
    }
}
