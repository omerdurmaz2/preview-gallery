package com.devomer.previewgallery.service

import com.devomer.previewgallery.render.SnapshotVerifyRunner
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Needs a real [com.intellij.openapi.project.Project]: [SnapshotVerifyStore.isStale] reads
 * [PsiModificationTracker], not just the coarse `stale` flag [SnapshotVerifyStore.markStale] sets, so the store
 * cannot be constructed bare the way the brief for this phase first assumed.
 *
 * Each test uses its own module name, deliberately not shared with any other test in this class: the store is a
 * project-level service, and the light project fixture this test case runs on can be reused across test methods
 * and even across other test classes, so a shared module name would let one test observe another's leftovers.
 */
class SnapshotVerifyStoreTest : BasePlatformTestCase() {

    private fun store() = SnapshotVerifyStore.getInstance(project)

    private fun currentPsiStamp(): Long = PsiModificationTracker.getInstance(project).modificationCount

    private fun run(
        moduleName: String,
        methodName: String = "Widget_Default_Snapshot",
        variant: String = "phone",
        status: SnapshotVerifyResults.Status = SnapshotVerifyResults.Status.PASSED,
        psiStamp: Long = currentPsiStamp(),
        stale: Boolean = false,
    ) = SnapshotVerifyStore.Run(
        moduleName = moduleName,
        outcome = SnapshotVerifyRunner.Outcome.RAN,
        results = listOf(
            SnapshotVerifyResults.SnapshotResult(
                methodName = methodName,
                variant = variant,
                status = status,
                goldenPath = "/golden/widget.png",
                renderedPath = "/rendered/widget.png",
                diffPath = null,
            ),
        ),
        ranAtMillis = 0L,
        psiStamp = psiStamp,
        stale = stale,
    )

    fun `test put then forModule round-trips`() {
        val original = run(moduleName = "app.roundTrip")

        store().put(original)

        assertEquals(original, store().forModule("app.roundTrip"))
    }

    fun `test markStale sets the flag and keeps the results`() {
        val original = run(moduleName = "app.markStale")
        store().put(original)

        store().markStale("app.markStale")

        val marked = requireNotNull(store().forModule("app.markStale")) { "expected the run to still be stored" }
        assertTrue(marked.stale)
        assertEquals(original.results, marked.results)
        assertTrue(store().isStale(marked))
    }

    fun `test resultFor returns null for a method the run did not include`() {
        store().put(run(moduleName = "app.missingMethod", methodName = "Widget_Default_Snapshot"))

        assertNull(store().resultFor("app.missingMethod", "Widget_Added_Since_Run", "phone"))
    }

    fun `test a second put for the same module replaces the first, stale flag included`() {
        store().put(run(moduleName = "app.replace", methodName = "Widget_Old_Snapshot"))
        store().markStale("app.replace")

        val replacement = run(moduleName = "app.replace", methodName = "Widget_New_Snapshot")
        store().put(replacement)

        val current = requireNotNull(store().forModule("app.replace")) { "expected the replacement run" }
        assertEquals("Widget_New_Snapshot", current.results.single().methodName)
        assertFalse(current.stale)
    }

    fun `test isStale is false for a fresh run whose psiStamp matches and the flag is unset`() {
        val fresh = run(moduleName = "app.freshStamp", psiStamp = currentPsiStamp(), stale = false)

        assertFalse(store().isStale(fresh))
    }

    fun `test isStale reads true once the psiStamp no longer matches, even with the flag false`() {
        // The flag stays false on purpose: this is exactly the case markStale/markAllStale never see, an
        // ordinary edit that only PsiModificationTracker knows happened.
        val edited = run(moduleName = "app.staleStamp", psiStamp = currentPsiStamp() - 1, stale = false)

        assertTrue(store().isStale(edited))
    }

    fun `test isStale reads true when the flag is set even though the psiStamp still matches`() {
        val flagged = run(moduleName = "app.staleFlag", psiStamp = currentPsiStamp(), stale = true)

        assertTrue(store().isStale(flagged))
    }

    fun `test validateTask names the sibling of ReferenceRoots#updateTask`() {
        assertEquals("validateDebugScreenshotTest", SnapshotVerifyRunner.validateTask("Debug"))
        assertEquals("updateDebugScreenshotTest", ReferenceRoots.updateTask("Debug"))
    }
}
