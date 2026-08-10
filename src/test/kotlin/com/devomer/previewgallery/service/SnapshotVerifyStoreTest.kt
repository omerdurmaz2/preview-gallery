package com.devomer.previewgallery.service

import com.devomer.previewgallery.render.SnapshotVerifyRunner
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Needs a real [com.intellij.openapi.project.Project]: [SnapshotVerifyStore.isStale] reads
 * [PsiModificationTracker], not just the coarse `stale` flag [SnapshotVerifyStore.markAllStale] sets, so the
 * store cannot be constructed bare the way the brief for this phase first assumed.
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

    fun `test markAllStale sets the flag and keeps the results`() {
        val original = run(moduleName = "app.markStale")
        store().put(original)

        store().markAllStale()

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
        store().markAllStale()

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

    private fun resolve(
        outcome: SnapshotVerifyRunner.Outcome,
        results: List<SnapshotVerifyResults.SnapshotResult>,
        previous: SnapshotVerifyStore.Run? = null,
    ) = SnapshotVerifyStore.resolve(
        moduleName = "app.resolve",
        outcome = outcome,
        results = results,
        ranAtMillis = 1_000L,
        psiStamp = 7L,
        previous = previous,
    )

    private fun oneResult(status: SnapshotVerifyResults.Status = SnapshotVerifyResults.Status.PASSED) = listOf(
        SnapshotVerifyResults.SnapshotResult(
            methodName = "Widget_Default_Snapshot",
            variant = "phone",
            status = status,
            goldenPath = "/golden/widget.png",
            renderedPath = "/rendered/widget.png",
            diffPath = null,
        ),
    )

    fun `test resolve stores RAN with the results the run produced`() {
        val resolved = requireNotNull(resolve(SnapshotVerifyRunner.Outcome.RAN, oneResult()))

        assertEquals(SnapshotVerifyRunner.Outcome.RAN, resolved.outcome)
        assertEquals(oneResult(), resolved.results)
        assertEquals(1_000L, resolved.ranAtMillis)
        assertEquals(7L, resolved.psiStamp)
        assertFalse(resolved.stale)
    }

    fun `test resolve stores RAN even when Gradle failed, as long as results were produced`() {
        // A differing snapshot fails the Gradle build: failure alone must never read as "could not run".
        val resolved = requireNotNull(
            resolve(SnapshotVerifyRunner.Outcome.RAN, oneResult(SnapshotVerifyResults.Status.FAILED)),
        )

        assertEquals(SnapshotVerifyRunner.Outcome.RAN, resolved.outcome)
        assertEquals(SnapshotVerifyResults.Status.FAILED, resolved.results.single().status)
    }

    fun `test resolve stores BUILD_FAILED for a run that produced nothing and had no earlier run`() {
        val resolved = requireNotNull(resolve(SnapshotVerifyRunner.Outcome.RAN, emptyList()))

        assertEquals(SnapshotVerifyRunner.Outcome.BUILD_FAILED, resolved.outcome)
        assertTrue(resolved.results.isEmpty())
    }

    fun `test resolve stores NOT_RUN for a run that never started and had no earlier run`() {
        val resolved = requireNotNull(resolve(SnapshotVerifyRunner.Outcome.NOT_RUN, emptyList()))

        assertEquals(SnapshotVerifyRunner.Outcome.NOT_RUN, resolved.outcome)
    }

    fun `test resolve keeps the three outcomes distinguishable`() {
        val ran = requireNotNull(resolve(SnapshotVerifyRunner.Outcome.RAN, oneResult())).outcome
        val buildFailed = requireNotNull(resolve(SnapshotVerifyRunner.Outcome.RAN, emptyList())).outcome
        val notRun = requireNotNull(resolve(SnapshotVerifyRunner.Outcome.NOT_RUN, emptyList())).outcome

        assertEquals(3, setOf(ran, buildFailed, notRun).size)
    }

    fun `test resolve publishes nothing when a cancelled run would clobber an earlier one`() {
        // What Stop in the Run window looks like from here: the task reports back having written no XML.
        assertNull(resolve(SnapshotVerifyRunner.Outcome.RAN, emptyList(), previous = run(moduleName = "app.resolve")))
    }

    fun `test resolve publishes nothing when an UP-TO-DATE run rewrites no results`() {
        val previous = run(moduleName = "app.resolve", methodName = "Widget_Default_Snapshot")

        // Second verify, sources unchanged: Gradle passes the task UP-TO-DATE, the old XML fails the timestamp
        // guard, and nothing is read. The module is clean, so it must keep its verdict rather than lose it.
        assertNull(resolve(SnapshotVerifyRunner.Outcome.RAN, emptyList(), previous = previous))
    }

    fun `test resolve publishes nothing when a run that never started would clobber an earlier one`() {
        assertNull(resolve(SnapshotVerifyRunner.Outcome.NOT_RUN, emptyList(), previous = run(moduleName = "app.resolve")))
    }

    fun `test resolve replaces an earlier run once there are results to replace it with`() {
        val previous = run(moduleName = "app.resolve", methodName = "Widget_Old_Snapshot")

        val resolved = requireNotNull(resolve(SnapshotVerifyRunner.Outcome.RAN, oneResult(), previous = previous))

        assertEquals("Widget_Default_Snapshot", resolved.results.single().methodName)
    }

    fun `test resolve keeps a stale earlier run rather than replacing it with nothing measured`() {
        val previous = run(moduleName = "app.resolve", stale = true)

        assertNull(resolve(SnapshotVerifyRunner.Outcome.RAN, emptyList(), previous = previous))
    }

    fun `test validateTask names the sibling of ReferenceRoots#updateTask`() {
        assertEquals("validateDebugScreenshotTest", SnapshotVerifyRunner.validateTask("Debug"))
        assertEquals("updateDebugScreenshotTest", ReferenceRoots.updateTask("Debug"))
    }
}
