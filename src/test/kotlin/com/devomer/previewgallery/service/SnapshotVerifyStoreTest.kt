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

    private fun run(
        moduleName: String,
        methodName: String = "Widget_Default_Snapshot",
        variant: String = "phone",
        status: SnapshotVerifyResults.Status = SnapshotVerifyResults.Status.PASSED,
        ranAtMillis: Long = 0L,
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
        ranAtMillis = ranAtMillis,
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

    fun `test isStale is false for a run no source has been written since, with the flag unset`() {
        val fresh = run(moduleName = "app.freshSources", ranAtMillis = 2_000L, stale = false)

        assertFalse(SnapshotVerifyStore.isStale(fresh, newestSourceMillis = 1_000L))
    }

    fun `test isStale reads true once a source is newer than the run, even with the flag false`() {
        // The flag stays false on purpose: this is exactly the case markStale/markAllStale never see, an ordinary
        // edit landing while the Gradle task the run measures is still going.
        val edited = run(moduleName = "app.editedSources", ranAtMillis = 2_000L, stale = false)

        assertTrue(SnapshotVerifyStore.isStale(edited, newestSourceMillis = 2_001L))
    }

    fun `test isStale treats a source written in the very millisecond the run launched as measured`() {
        val started = run(moduleName = "app.sameMillisecond", ranAtMillis = 2_000L, stale = false)

        assertFalse(SnapshotVerifyStore.isStale(started, newestSourceMillis = 2_000L))
    }

    fun `test isStale reads true when the flag is set even though no source is newer`() {
        val flagged = run(moduleName = "app.staleFlag", ranAtMillis = 2_000L, stale = true)

        assertTrue(SnapshotVerifyStore.isStale(flagged, newestSourceMillis = 1_000L))
    }

    fun `test isStale reads true when the module is gone and staleness cannot be determined`() {
        val orphaned = run(moduleName = "app.noSuchModule", ranAtMillis = 2_000L, stale = false)

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
        val fresh = run(moduleName = module.name, ranAtMillis = RUN_LAUNCHED_AT)

        myFixture.tempDirFixture.createFile("build/outputs/screenshotTest-results/rendered.png", "not a png")

        assertTrue("expected the PSI counter to move on an event that is not an edit", tracker.modificationCount > before)
        assertFalse(store().isStale(fresh))
    }

    fun `test an edit to the module's sources after the run makes it stale`() {
        File(moduleDirectory, "src/main/kotlin/Widget.kt").setLastModified(RUN_LAUNCHED_AT + 60_000)
        ModuleFreshness.invalidate(module)

        assertTrue(store().isStale(run(moduleName = module.name, ranAtMillis = RUN_LAUNCHED_AT)))
    }

    private fun resolve(
        outcome: SnapshotVerifyRunner.Outcome,
        results: List<SnapshotVerifyResults.SnapshotResult>,
        previous: SnapshotVerifyStore.Run? = null,
        explicit: Boolean = false,
    ) = SnapshotVerifyStore.resolve(
        moduleName = "app.resolve",
        outcome = outcome,
        results = results,
        ranAtMillis = 1_000L,
        previous = previous,
        explicit = explicit,
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

    fun `test resolve publishes an explicit run that measured nothing over an earlier one`() {
        // The toolbar's own Verify: silence is an acceptable answer to a question nobody asked, never to a
        // button press, so this outcome replaces the earlier run rather than leaving it standing.
        val resolved = requireNotNull(
            resolve(
                SnapshotVerifyRunner.Outcome.RAN,
                emptyList(),
                previous = run(moduleName = "app.resolve"),
                explicit = true,
            ),
        )

        assertEquals(SnapshotVerifyRunner.Outcome.BUILD_FAILED, resolved.outcome)
        assertTrue(resolved.results.isEmpty())
    }

    fun `test resolve publishes an explicit run that never started over an earlier one`() {
        val resolved = requireNotNull(
            resolve(
                SnapshotVerifyRunner.Outcome.NOT_RUN,
                emptyList(),
                previous = run(moduleName = "app.resolve"),
                explicit = true,
            ),
        )

        assertEquals(SnapshotVerifyRunner.Outcome.NOT_RUN, resolved.outcome)
    }

    fun `test resolve treats an explicit and an automatic run that measured nothing differently`() {
        val previous = run(moduleName = "app.resolve")

        val automatic = resolve(SnapshotVerifyRunner.Outcome.RAN, emptyList(), previous = previous, explicit = false)
        val explicit = resolve(SnapshotVerifyRunner.Outcome.RAN, emptyList(), previous = previous, explicit = true)

        assertNull(automatic)
        assertNotNull(explicit)
    }

    fun `test resolve stores the same run for an explicit and an automatic run that measured something`() {
        val previous = run(moduleName = "app.resolve", methodName = "Widget_Old_Snapshot")

        val automatic = resolve(SnapshotVerifyRunner.Outcome.RAN, oneResult(), previous = previous, explicit = false)
        val explicit = resolve(SnapshotVerifyRunner.Outcome.RAN, oneResult(), previous = previous, explicit = true)

        assertEquals(automatic, explicit)
    }

    fun `test validateTask names the sibling of ReferenceRoots#updateTask`() {
        assertEquals("validateDebugScreenshotTest", SnapshotVerifyRunner.validateTask("Debug"))
        assertEquals("updateDebugScreenshotTest", ReferenceRoots.updateTask("Debug"))
    }

    private companion object {
        /** A fixed point rather than the wall clock, so the source files this fixture writes sit on a known side
         *  of it: far enough in the future that a real directory mtime created during the run is older. */
        const val RUN_LAUNCHED_AT = 2_000_000_000_000L
    }
}
