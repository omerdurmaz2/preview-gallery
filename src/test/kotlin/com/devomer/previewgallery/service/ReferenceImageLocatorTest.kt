package com.devomer.previewgallery.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferenceImageLocatorTest {

    @Test
    fun `directory mirrors the package and the facade class`() {
        assertEquals(
            "com/hepsiburada/ui/feature/favorites/component/ComponentsSnapshotsKt",
            ReferenceImageLocator.packageDirectory(
                packageName = "com.hepsiburada.ui.feature.favorites.component",
                jvmClassName = "com.hepsiburada.ui.feature.favorites.component.ComponentsSnapshotsKt",
            ),
        )
    }

    @Test
    fun `a root package yields no package directories`() {
        assertEquals(
            "SnapshotsKt",
            ReferenceImageLocator.packageDirectory(packageName = "", jvmClassName = "SnapshotsKt"),
        )
    }

    @Test
    fun `variant is read back out of the file name`() {
        assertEquals(
            "phone",
            ReferenceImageLocator.variantOf(
                "ErrorRetryRow_Default_Snapshot_phone_eee23ffd_0.png",
                "ErrorRetryRow_Default_Snapshot",
            ),
        )
        assertEquals(
            "small",
            ReferenceImageLocator.variantOf(
                "ErrorRetryRow_Default_Snapshot_small_72f29e0e_0.png",
                "ErrorRetryRow_Default_Snapshot",
            ),
        )
    }

    @Test
    fun `a name belonging to another function is rejected`() {
        assertNull(
            ReferenceImageLocator.variantOf(
                "OtherThing_Default_Snapshot_phone_eee23ffd_0.png",
                "ErrorRetryRow_Default_Snapshot",
            ),
        )
    }

    @Test
    fun `a name that does not fit the pattern is rejected rather than mis-parsed`() {
        assertNull(ReferenceImageLocator.variantOf("ErrorRetryRow_Default_Snapshot.png", "ErrorRetryRow_Default_Snapshot"))
        assertNull(ReferenceImageLocator.variantOf("ErrorRetryRow_Default_Snapshot_phone.png", "ErrorRetryRow_Default_Snapshot"))
    }

    @Test
    fun `an unnamed variant is reported as default`() {
        assertEquals(
            "default",
            ReferenceImageLocator.variantOf(
                "ErrorRetryRow_Default_Snapshot__eee23ffd_0.png",
                "ErrorRetryRow_Default_Snapshot",
            ),
        )
    }
}
