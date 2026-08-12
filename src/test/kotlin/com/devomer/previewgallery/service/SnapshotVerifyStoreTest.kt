package com.devomer.previewgallery.service

import com.devomer.previewgallery.render.ModuleFreshness
import com.devomer.previewgallery.render.SnapshotVerifyRunner
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Needs a real [com.intellij.openapi.project.Project]: [SnapshotVerifyStore.isStale] resolves the run's module
 * through the project model, not just the coarse `stale` flag [SnapshotVerifyStore.markAllStale] sets, so the
 * store cannot be constructed bare the way the brief for this phase first assumed.
 *
 * Each test uses its own module name, deliberately not shared with any other test in this class: the store is a
 * project-level service, and the light project fixture this test case runs on can be reused across test methods
 * and even across other test classes, so a shared module name would let one test observe another's leftovers.
 */
class SnapshotVerifyStoreTest : BasePlatformTestCase() {

    private lateinit var moduleDirectory: File
    private lateinit var mainSourceRoot: VirtualFile

    override fun setUp() {
        super.setUp()
        moduleDirectory = FileUtil.createTempDirectory("preview-gallery-verify-store", null)
        val source = File(moduleDirectory, "src/main/kotlin/Widget.kt")
        FileUtil.createParentDirs(source)
        source.writeText("")
        source.setLastModified(RUN_LAUNCHED_AT - 60_000)
        mainSourceRoot = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(moduleDirectory, "src/main")),
        ) { "The temp source root must be visible in the VFS" }
        PsiTestUtil.addSourceContentToRoots(module, mainSourceRoot)
        ModuleFreshness.invalidate(module)
    }

    override fun tearDown() {
        try {
            PsiTestUtil.removeContentEntry(module, mainSourceRoot)
            ModuleFreshness.invalidate(module)
            FileUtil.delete(moduleDirectory)
        } finally {
            super.tearDown()
        }
    }

    private fun store() = SnapshotVerifyStore.getInstance(project)

    private fun results(
        methodName: String = "Widget_Default_Snapshot",
        variant: String = "phone",
        status: SnapshotVerifyResults.Status = SnapshotVerifyResults.Status.PASSED,
    ) = listOf(
        SnapshotVerifyResults.SnapshotResult(
            methodName = methodName,
            variant = variant,
            status = status,
            goldenPath = "/golden/widget.png",
            renderedPath = "/rendered/widget.png",
            diffPath = null,
        ),
    )

    private fun measurement(
        moduleName: String,
        methodName: String = "Widget_Default_Snapshot",
        variant: String = "phone",
        status: SnapshotVerifyResults.Status = SnapshotVerifyResults.Status.PASSED,
        ranAtMillis: Long = 0L,
        stale: Boolean = false,
    ) = SnapshotVerifyStore.Measurement(
        moduleName = moduleName,
        results = results(methodName, variant, status),
        ranAtMillis = ranAtMillis,
        stale = stale,
    )

    /** A run that measured something, which is the only kind that leaves a measurement behind. [finishedAtMillis]
     *  defaults far enough past [launchedAtMillis] that a test asserting one can never be satisfied by the other. */
    private fun recordMeasuring(
        moduleName: String,
        methodName: String = "Widget_Default_Snapshot",
        status: SnapshotVerifyResults.Status = SnapshotVerifyResults.Status.PASSED,
        launchedAtMillis: Long = 0L,
        finishedAtMillis: Long = launchedAtMillis + RUN_DURATION,
    ) = store().record(
        moduleName = moduleName,
        outcome = SnapshotVerifyRunner.Outcome.RAN,
        results = results(methodName, status = status),
        launchedAtMillis = launchedAtMillis,
        finishedAtMillis = finishedAtMillis,
    )

    private fun recordEmpty(
        moduleName: String,
        outcome: SnapshotVerifyRunner.Outcome = SnapshotVerifyRunner.Outcome.RAN,
        launchedAtMillis: Long = 1_000L,
        finishedAtMillis: Long = launchedAtMillis + RUN_DURATION,
    ) = store().record(moduleName, outcome, emptyList(), launchedAtMillis, finishedAtMillis)

    fun `test record then measurementFor round-trips`() {
        recordMeasuring(moduleName = "app.roundTrip")

        assertEquals(measurement(moduleName = "app.roundTrip"), store().measurementFor("app.roundTrip"))
    }

    fun `test markAllStale sets the flag and keeps the results`() {
        recordMeasuring(moduleName = "app.markStale")

        store().markAllStale()

        val marked = requireNotNull(store().measurementFor("app.markStale")) { "expected the measurement to still be stored" }
        assertTrue(marked.stale)
        assertEquals(results(), marked.results)
        assertTrue(store().isStale(marked))
    }

    fun `test resultFor returns null for a method the run did not include`() {
        recordMeasuring(moduleName = "app.missingMethod", methodName = "Widget_Default_Snapshot")

        assertNull(store().resultFor("app.missingMethod", "Widget_Added_Since_Run", "phone"))
    }

    fun `test a second measuring run for the same module replaces the first, stale flag included`() {
        recordMeasuring(moduleName = "app.replace", methodName = "Widget_Old_Snapshot")
        store().markAllStale()

        recordMeasuring(moduleName = "app.replace", methodName = "Widget_New_Snapshot")

        val current = requireNotNull(store().measurementFor("app.replace")) { "expected the replacement measurement" }
        assertEquals("Widget_New_Snapshot", current.results.single().methodName)
        assertFalse(current.stale)
    }

    fun `test isStale is false for a run no source has been written since, with the flag unset`() {
        val fresh = measurement(moduleName = "app.freshSources", ranAtMillis = 2_000L, stale = false)

        assertFalse(SnapshotVerifyStore.isStale(fresh, newestSourceMillis = 1_000L))
    }

    fun `test isStale reads true once a source is newer than the run, even with the flag false`() {
        // The flag stays false on purpose: this is exactly the case markStale/markAllStale never see, an ordinary
        // edit landing while the Gradle task the run measures is still going.
        val edited = measurement(moduleName = "app.editedSources", ranAtMillis = 2_000L, stale = false)

        assertTrue(SnapshotVerifyStore.isStale(edited, newestSourceMillis = 2_001L))
    }

    fun `test isStale treats a source written in the very millisecond the run launched as measured`() {
        val started = measurement(moduleName = "app.sameMillisecond", ranAtMillis = 2_000L, stale = false)

        assertFalse(SnapshotVerifyStore.isStale(started, newestSourceMillis = 2_000L))
    }

    fun `test isStale reads true when the flag is set even though no source is newer`() {
        val flagged = measurement(moduleName = "app.staleFlag", ranAtMillis = 2_000L, stale = true)

        assertTrue(SnapshotVerifyStore.isStale(flagged, newestSourceMillis = 1_000L))
    }

    fun `test isStale reads true when the module is gone and staleness cannot be determined`() {
        val orphaned = measurement(moduleName = "app.noSuchModule", ranAtMillis = 2_000L, stale = false)

        assertTrue(SnapshotVerifyStore.isStale(orphaned, newestSourceMillis = null))
        assertTrue(store().isStale(orphaned))
    }

    fun `test a project event that is not a source edit no longer makes a run stale`() {
        // The PG20 gate bug, end to end: PsiModificationTracker is project-wide and moves for any change that
        // can affect PSI, so a verify that writes into build/ for minutes always outran a stamp captured before
        // it launched. The module's sources here are real files on disk, older than the run — see setUp; with
        // the fixture's in-memory roots this assertion would hold no matter what the code did.
        val tracker = PsiModificationTracker.getInstance(project)
        val before = tracker.modificationCount
        val fresh = measurement(moduleName = module.name, ranAtMillis = RUN_LAUNCHED_AT)

        myFixture.tempDirFixture.createFile("build/outputs/screenshotTest-results/rendered.png", "not a png")

        assertTrue("expected the PSI counter to move on an event that is not an edit", tracker.modificationCount > before)
        assertFalse(store().isStale(fresh))
    }

    fun `test an edit to the module's sources after the run makes it stale`() {
        File(moduleDirectory, "src/main/kotlin/Widget.kt").setLastModified(RUN_LAUNCHED_AT + 60_000)
        ModuleFreshness.invalidate(module)

        assertTrue(store().isStale(measurement(moduleName = module.name, ranAtMillis = RUN_LAUNCHED_AT)))
    }

    fun `test record stores the measurement and a RAN attempt for a run that produced results`() {
        recordMeasuring(moduleName = "app.record.ran", launchedAtMillis = 1_000L)

        val stored = requireNotNull(store().measurementFor("app.record.ran"))
        assertEquals(results(), stored.results)
        assertEquals(1_000L, stored.ranAtMillis)
        assertFalse(stored.stale)
        assertEquals(SnapshotVerifyRunner.Outcome.RAN, store().lastAttempt("app.record.ran")?.outcome)
    }

    fun `test record stores RAN even when Gradle failed, as long as results were produced`() {
        // A differing snapshot fails the Gradle build: failure alone must never read as "could not run".
        recordMeasuring(moduleName = "app.record.failing", status = SnapshotVerifyResults.Status.FAILED)

        assertEquals(SnapshotVerifyRunner.Outcome.RAN, store().lastAttempt("app.record.failing")?.outcome)
        assertEquals(
            SnapshotVerifyResults.Status.FAILED,
            store().measurementFor("app.record.failing")?.results?.single()?.status,
        )
    }

    fun `test record stores a BUILD_FAILED attempt and no measurement for a run that produced nothing`() {
        recordEmpty(moduleName = "app.record.buildFailed")

        assertEquals(SnapshotVerifyRunner.Outcome.BUILD_FAILED, store().lastAttempt("app.record.buildFailed")?.outcome)
        assertNull(store().measurementFor("app.record.buildFailed"))
    }

    fun `test record stores a NOT_RUN attempt and no measurement for a run that never started`() {
        recordEmpty(moduleName = "app.record.notRun", outcome = SnapshotVerifyRunner.Outcome.NOT_RUN)

        assertEquals(SnapshotVerifyRunner.Outcome.NOT_RUN, store().lastAttempt("app.record.notRun")?.outcome)
        assertNull(store().measurementFor("app.record.notRun"))
    }

    fun `test record keeps the three outcomes distinguishable`() {
        recordMeasuring(moduleName = "app.distinct.ran")
        recordEmpty(moduleName = "app.distinct.buildFailed")
        recordEmpty(moduleName = "app.distinct.notRun", outcome = SnapshotVerifyRunner.Outcome.NOT_RUN)

        val outcomes = listOf("app.distinct.ran", "app.distinct.buildFailed", "app.distinct.notRun")
            .mapNotNull { store().lastAttempt(it)?.outcome }

        assertEquals(3, outcomes.toSet().size)
    }

    fun `test a cancelled run records its attempt and leaves the measurement standing`() {
        // What Stop in the Run window looks like from here: the task reports back having written no XML.
        recordMeasuring(moduleName = "app.cancelled", methodName = "Widget_Default_Snapshot")

        recordEmpty(moduleName = "app.cancelled")

        assertEquals("Widget_Default_Snapshot", store().measurementFor("app.cancelled")?.results?.single()?.methodName)
        assertEquals(SnapshotVerifyRunner.Outcome.BUILD_FAILED, store().lastAttempt("app.cancelled")?.outcome)
    }

    fun `test an UP-TO-DATE run that rewrites no results leaves the measurement intact and records the attempt`() {
        // The design flaw the split exists for. Second verify, sources unchanged: Gradle passes the task
        // UP-TO-DATE, the old XML fails the timestamp guard, and nothing is read. The module is clean, so it must
        // keep its verdict — and the attempt must still be on record, so the user is never told nothing happened.
        recordMeasuring(moduleName = "app.upToDate", launchedAtMillis = 500L)

        recordEmpty(moduleName = "app.upToDate", launchedAtMillis = 1_000L)

        val kept = requireNotNull(store().measurementFor("app.upToDate")) { "the measurement must survive" }
        assertEquals(results(), kept.results)
        assertEquals(500L, kept.ranAtMillis)
        val attempt = requireNotNull(store().lastAttempt("app.upToDate")) { "the attempt must be recorded" }
        assertEquals(SnapshotVerifyRunner.Outcome.BUILD_FAILED, attempt.outcome)
        assertEquals(1_000L + RUN_DURATION, attempt.atMillis)
    }

    fun `test the attempt is stamped when the run ended and the measurement when it launched`() {
        // A verify takes minutes. The measurement's stamp is compared against source mtimes and must stay the
        // launch time; the attempt's is only ever rendered, and printing the launch time would tell the user a run
        // that just finished happened three minutes ago. One value passed for both would satisfy neither
        // assertion here.
        recordMeasuring(moduleName = "app.stamps", launchedAtMillis = 500L, finishedAtMillis = 500L + RUN_DURATION)

        assertEquals(500L, store().measurementFor("app.stamps")?.ranAtMillis)
        assertEquals(500L + RUN_DURATION, store().lastAttempt("app.stamps")?.atMillis)
    }

    fun `test a run that measured nothing stamps its attempt when it ended, not when it launched`() {
        recordEmpty(moduleName = "app.stamps.empty", launchedAtMillis = 500L, finishedAtMillis = 500L + RUN_DURATION)

        assertEquals(500L + RUN_DURATION, store().lastAttempt("app.stamps.empty")?.atMillis)
    }

    fun `test a run that never started records its attempt and leaves the measurement standing`() {
        recordMeasuring(moduleName = "app.neverStarted")

        recordEmpty(moduleName = "app.neverStarted", outcome = SnapshotVerifyRunner.Outcome.NOT_RUN)

        assertNotNull(store().measurementFor("app.neverStarted"))
        assertEquals(SnapshotVerifyRunner.Outcome.NOT_RUN, store().lastAttempt("app.neverStarted")?.outcome)
    }

    fun `test a measuring run replaces the measurement and the attempt together`() {
        recordMeasuring(moduleName = "app.replaced", methodName = "Widget_Old_Snapshot")
        recordEmpty(moduleName = "app.replaced", outcome = SnapshotVerifyRunner.Outcome.NOT_RUN)

        recordMeasuring(moduleName = "app.replaced", methodName = "Widget_New_Snapshot", launchedAtMillis = 2_000L)

        assertEquals("Widget_New_Snapshot", store().measurementFor("app.replaced")?.results?.single()?.methodName)
        assertEquals(SnapshotVerifyRunner.Outcome.RAN, store().lastAttempt("app.replaced")?.outcome)
        assertEquals(2_000L + RUN_DURATION, store().lastAttempt("app.replaced")?.atMillis)
    }

    fun `test a stale measurement is kept rather than replaced by a run that measured nothing`() {
        recordMeasuring(moduleName = "app.staleKept")
        store().markAllStale()

        recordEmpty(moduleName = "app.staleKept")

        val kept = requireNotNull(store().measurementFor("app.staleKept")) { "a stale verdict is still the best one" }
        assertEquals(results(), kept.results)
        assertTrue(kept.stale)
    }

    fun `test an attempt that measured nothing is recorded whether or not a measurement exists`() {
        // What the deleted `explicit` flag was buying: a run that measured nothing always leaves something on
        // record, so a button press can never look like a broken button. Who asked is no longer a distinction the
        // store makes, because it no longer decides which of the two facts to lose.
        recordEmpty(moduleName = "app.attemptOnly", launchedAtMillis = 1_000L)
        recordMeasuring(moduleName = "app.attemptAfterMeasurement")
        recordEmpty(moduleName = "app.attemptAfterMeasurement", launchedAtMillis = 1_000L)

        assertEquals(SnapshotVerifyRunner.Outcome.BUILD_FAILED, store().lastAttempt("app.attemptOnly")?.outcome)
        assertEquals(SnapshotVerifyRunner.Outcome.BUILD_FAILED, store().lastAttempt("app.attemptAfterMeasurement")?.outcome)
        assertNotNull(store().measurementFor("app.attemptAfterMeasurement"))
    }

    fun `test two runs with the same inputs are stored identically now that no flag says who asked`() {
        // The `explicit` parameter used to make these two diverge, and that divergence was the bug: it decided
        // which of the two facts to lose rather than keeping both.
        recordMeasuring(moduleName = "app.sameInputs.a", launchedAtMillis = 500L)
        recordEmpty(moduleName = "app.sameInputs.a", launchedAtMillis = 1_000L)
        recordMeasuring(moduleName = "app.sameInputs.b", launchedAtMillis = 500L)
        recordEmpty(moduleName = "app.sameInputs.b", launchedAtMillis = 1_000L)

        assertEquals(
            store().measurementFor("app.sameInputs.a")?.results,
            store().measurementFor("app.sameInputs.b")?.results,
        )
        assertEquals(store().lastAttempt("app.sameInputs.a"), store().lastAttempt("app.sameInputs.b"))
    }

    fun `test a module with no run at all has neither a measurement nor an attempt`() {
        assertNull(store().measurementFor("app.neverVerified"))
        assertNull(store().lastAttempt("app.neverVerified"))
    }

    fun `test repeated runs that measure nothing never erode the measurement`() {
        recordMeasuring(moduleName = "app.repeated", methodName = "Widget_Default_Snapshot")

        recordEmpty(moduleName = "app.repeated")
        recordEmpty(moduleName = "app.repeated", outcome = SnapshotVerifyRunner.Outcome.NOT_RUN)
        recordEmpty(moduleName = "app.repeated")

        assertEquals(results(), store().measurementFor("app.repeated")?.results)
    }

    fun `test validateTask names the sibling of ReferenceRoots#updateTask`() {
        assertEquals("validateDebugScreenshotTest", SnapshotVerifyRunner.validateTask("Debug"))
        assertEquals("updateDebugScreenshotTest", ReferenceRoots.updateTask("Debug"))
    }

    private companion object {
        /** A fixed point rather than the wall clock, so the source files this fixture writes sit on a known side
         *  of it: far enough in the future that a real directory mtime created during the run is older. */
        const val RUN_LAUNCHED_AT = 2_000_000_000_000L

        /** How long the runs in this class take, so a measurement's launch stamp and its attempt's end stamp are
         *  never the same number — a fix that collapsed the two back into one value would fail here. Three
         *  minutes is what the real task takes. */
        const val RUN_DURATION = 180_000L
    }
}
