package com.devomer.previewgallery.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerifyFailureNotificationTextTest {

    private fun result(methodName: String, variant: String, status: SnapshotVerifyResults.Status) =
        SnapshotVerifyResults.SnapshotResult(
            methodName = methodName,
            variant = variant,
            status = status,
            goldenPath = null,
            renderedPath = null,
            diffPath = null,
        )

    private fun passed(methodName: String, variant: String = "phone") =
        result(methodName, variant, SnapshotVerifyResults.Status.PASSED)

    private fun failed(methodName: String, variant: String = "phone") =
        result(methodName, variant, SnapshotVerifyResults.Status.FAILED)

    @Test
    fun `a run with no failures stays silent`() {
        val text = VerifyFailureNotificationText.of("app", listOf(passed("WidgetPreview_Snapshot")))
        assertNull(text)
    }

    @Test
    fun `an empty result list stays silent`() {
        val text = VerifyFailureNotificationText.of("app", emptyList())
        assertNull(text)
    }

    @Test
    fun `names the module, the counts, and one failing function with both its variants`() {
        val results = (1..98).map { passed("Passing_$it") } + listOf(
            failed("DeleteSelectedProductsDialog_Default_Snapshot", "phone"),
            failed("DeleteSelectedProductsDialog_Default_Snapshot", "small"),
        )

        val text = VerifyFailureNotificationText.of("features/favorites/ui", results)

        assertEquals(
            "2 of 100 snapshots differ in features/favorites/ui — " +
                "DeleteSelectedProductsDialog_Default_Snapshot (phone, small)",
            text,
        )
    }

    @Test
    fun `bounds the function list to three and counts the rest`() {
        val results = listOf(
            failed("FuncA"),
            failed("FuncB"),
            failed("FuncC"),
            failed("FuncD"),
            failed("FuncE"),
        )

        val text = VerifyFailureNotificationText.of("app", results)

        assertEquals(
            "5 of 5 snapshots differ in app — FuncA (phone), FuncB (phone), FuncC (phone) and 2 more",
            text,
        )
    }

    @Test
    fun `exactly three failing functions carries no trailing more`() {
        val results = listOf(failed("FuncA"), failed("FuncB"), failed("FuncC"))

        val text = VerifyFailureNotificationText.of("app", results)

        assertEquals("3 of 3 snapshots differ in app — FuncA (phone), FuncB (phone), FuncC (phone)", text)
    }
}
