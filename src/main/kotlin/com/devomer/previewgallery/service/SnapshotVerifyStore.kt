package com.devomer.previewgallery.service

import com.devomer.previewgallery.render.ModuleFreshness
import com.devomer.previewgallery.render.SnapshotVerifyRunner
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * Two facts per module, kept apart because they are independently true: the last verify that actually **measured**
 * something, and how the most recent **attempt** ended.
 *
 * They used to be one record, and every bug this feature has had at the gate came from that. "The last real
 * measurement found three differing snapshots" and "the attempt a moment ago measured nothing because the project
 * was indexing" are both true at once, and the user needs both; storing them in one slot meant every write of the
 * second erased the first. Three guards were bolted on in turn — never publish an empty run, then publish it
 * anyway when the user asked explicitly — and each one only chose which of the two facts to lose. The second
 * verify of a clean module is the ordinary case that broke: Gradle passes the task UP-TO-DATE, rewrites no XML,
 * [SnapshotVerifyResults]' timestamp guard filters everything, and the module's good verdict was replaced by
 * "nothing was measured".
 *
 * So [record] writes the attempt every time and the measurement only when there is one. Nothing else can replace a
 * measurement — not a cancellation, not UP-TO-DATE, not a compile failure, not an indexing refusal — and no caller
 * needs to tell this store whether the user asked for the run, because both kinds of run answer both questions the
 * same way.
 *
 * Editing the module marks its measurement **stale** rather than dropping it (spec D4). "It was green" and "it is
 * green" are different facts, and this project has repeatedly chosen to keep that distinction visible instead of
 * silent; deleting also throws away minutes of Gradle over one keystroke.
 */
@Service(Service.Level.PROJECT)
class SnapshotVerifyStore(private val project: Project) {

    /** What a run found, when there was something to find. [results] is never empty — a record with no results is
     *  not a measurement, it is an attempt.
     *
     *  [stale] is the coarse flag [markAllStale] sets for an event too broad to attribute to one module — a VFS
     *  change, a project reload. [ranAtMillis] is what catches the event that one misses, an ordinary edit:
     *  [isStale] compares it against when the module's own sources were last written, with no listener of its
     *  own to keep in step. It is when the Gradle task was *launched*, not when it finished, which is what makes
     *  an edit landing mid-run read stale the moment the run publishes. */
    data class Measurement(
        val moduleName: String,
        val results: List<SnapshotVerifyResults.SnapshotResult>,
        val ranAtMillis: Long,
        val stale: Boolean = false,
    )

    /** How the most recent run ended, whatever it did or did not measure. [SnapshotVerifyRunner.Outcome.RAN] means
     *  it produced results and so also replaced the [Measurement]; the other two mean it did not, and the display
     *  owes the user that sentence next to whatever older measurement still stands.
     *
     *  [atMillis] is when the run **ended**, deliberately unlike [Measurement.ranAtMillis], which is when its run
     *  was launched. A measurement's stamp is compared against source mtimes and has to be the launch time to be
     *  correct; an attempt's is only ever rendered, and a three-minute run reported as having happened three
     *  minutes ago is simply a wrong sentence. The two must not converge. */
    data class Attempt(
        val outcome: SnapshotVerifyRunner.Outcome,
        val atMillis: Long,
    )

    private val measurements = ConcurrentHashMap<String, Measurement>()

    private val attempts = ConcurrentHashMap<String, Attempt>()

    /**
     * Records how a finished run ended, and — only if it produced [results] — what it measured.
     *
     * A run that produced no results measured nothing, whatever Gradle's exit status said: a compile failure and a
     * clean pass are both "the task returned", and only the results tell them apart (spec D8). A task name Gradle
     * does not have lands here too — the spec's error table lists it separately, but telling it from a compile
     * failure would mean parsing Gradle's output, and both answer the user the same way.
     *
     * "Measured nothing" is never evidence that an earlier measurement is wrong, which is why it cannot replace
     * one. The two ordinary paths that reach here with a perfectly good measurement on record are the user pressing
     * Stop (the spec's error table: *run cancelled → nothing published, the previous result stays*) and Gradle
     * passing the task UP-TO-DATE on a second run over unchanged sources. An earlier measurement going stale does
     * not change that either: a stale verdict is still the best one there is, and [isStale] is what says so at the
     * point it is shown.
     *
     * The two stamps are separate arguments because they are separate instants and the run between them takes
     * minutes: [launchedAtMillis] is what [isStale] compares source mtimes against and is only ever read from the
     * measurement, [finishedAtMillis] is what the display prints about the attempt. Passing one value for both
     * makes the pane say a run that ended just now happened when it started.
     */
    fun record(
        moduleName: String,
        outcome: SnapshotVerifyRunner.Outcome,
        results: List<SnapshotVerifyResults.SnapshotResult>,
        launchedAtMillis: Long,
        finishedAtMillis: Long,
    ) {
        if (results.isEmpty()) {
            val ended = if (outcome == SnapshotVerifyRunner.Outcome.NOT_RUN) {
                SnapshotVerifyRunner.Outcome.NOT_RUN
            } else {
                SnapshotVerifyRunner.Outcome.BUILD_FAILED
            }
            attempts[moduleName] = Attempt(ended, finishedAtMillis)
            return
        }
        attempts[moduleName] = Attempt(SnapshotVerifyRunner.Outcome.RAN, finishedAtMillis)
        measurements[moduleName] = Measurement(moduleName, results, launchedAtMillis)
    }

    fun measurementFor(moduleName: String): Measurement? = measurements[moduleName]

    fun lastAttempt(moduleName: String): Attempt? = attempts[moduleName]

    /** Drops every measurement and attempt this project-level service holds, so a test that recorded one for the
     *  light fixture's real module cannot leak it into a later test — `BasePlatformTestCase` reuses one light
     *  project (and so this same service instance) for the whole run, the same reuse `resetFilterToggles` exists
     *  to undo for the toolbar toggles.
     *
     *  Called from `tearDown`, not `setUp`: sufficient only because `PreviewGalleryPanelTest` is currently the one
     *  test class that records under the real `module.name` — `SnapshotVerifyStoreTest` keys its own records by
     *  made-up per-test names, and `PreviewTreeCellRendererTest` constructs its renderer with no `Project` at all,
     *  so it never reaches this store. `tearDown` only protects tests that run *after* this class's own; a future
     *  test class that also records under the fixture module, and that can run earlier in the suite, would need
     *  to call this from its own `setUp` instead. */
    @TestOnly
    internal fun clearForTest() {
        measurements.clear()
        attempts.clear()
    }

    /** The result for one snapshot function and variant, or null when this module has no measurement, or the
     *  measurement has no entry for it — a snapshot added since the run is exactly that case, and it must read as
     *  "unknown" rather than as "passed". */
    fun resultFor(
        moduleName: String,
        methodName: String,
        variant: String,
    ): SnapshotVerifyResults.SnapshotResult? =
        measurements[moduleName]?.results?.firstOrNull { it.methodName == methodName && it.variant == variant }

    /** Used when a change cannot be attributed to one module — a broad VFS event, or a project reload. Marking
     *  everything is the safe direction: a stale badge understates confidence, a fresh one overstates it. */
    fun markAllStale() {
        measurements.replaceAll { _, measurement -> if (measurement.stale) measurement else measurement.copy(stale = true) }
    }

    /**
     * Whether [measurement]'s verdict no longer describes the code on disk (spec D4): either a coarse event marked
     * it explicitly, or the module's own sources were written after the run that measured them started.
     *
     * **Not [com.intellij.psi.util.PsiModificationTracker], which this used to stamp and compare.** That counter
     * is project-wide and moves for anything that can affect PSI at all, not for edits — measured on a light
     * fixture with no source edit whatsoever, writing one file under `build/outputs` moved it by 5 and writing
     * one generated source by another 5, against 3 for an actual edit. A verify takes minutes and writes into
     * `build/` throughout, so a stamp taken before it launched had always moved by the time it landed: every
     * completed run read stale, which is precisely what the manual gate saw. [PreviewIndexService] keys a
     * *cache* on that same counter and is right to — over-invalidating a cache costs a recompute, while
     * over-reporting staleness tells the user their code changed when it did not.
     *
     * A null [newestSourceMillis] means there is no source clock to compare against — the module is gone from
     * the project model, or nothing readable was found under it. Either way nothing can be compared, and that
     * reads stale rather than fresh, because the one thing this must never do is present an unverifiable verdict
     * as a current one. "Nothing found" and "nothing changed" are deliberately not spelled the same way.
     *
     * Scoped to the module deliberately, where the counter was project-wide: spec D4's rule is that editing *the
     * module* marks its run stale, and an edit in an unrelated module used to mark every run in the project. The
     * module's own `src/screenshotTest` counts as part of it even though no module has it as a source root — see
     * [ModuleFreshness.newestModuleSourceMtime], without which editing the snapshot test itself left its run
     * reading fresh. The accepted ceilings are the other direction: an edit in a module this one depends on does
     * not mark it, and an edit still sitting unsaved in an editor buffer does not either, since Gradle measures
     * what is on disk and the IDE saves before it launches. Upgrade path, if either ever matters: walk the
     * module's dependencies here, and consult [com.intellij.openapi.fileEditor.FileDocumentManager]'s unsaved
     * documents.
     */
    fun isStale(measurement: Measurement): Boolean = isStale(measurement, newestSourceMillis(measurement.moduleName))

    /**
     * [isStale] for a caller that must not block on a filesystem walk — the tree's per-row paint callback, and
     * nothing else. Same rule and the same "an unknown clock reads stale" direction; only where the clock comes
     * from differs. [onRefreshed] fires when the walk this call scheduled has landed, so the caller can ask again;
     * see [ModuleFreshness.cachedModuleSourceMtime] for why an expired value is served rather than an unknown.
     */
    fun isStale(measurement: Measurement, onRefreshed: () -> Unit): Boolean =
        isStale(
            measurement,
            moduleFor(measurement.moduleName)?.let { ModuleFreshness.cachedModuleSourceMtime(it, onRefreshed) },
        )

    private fun newestSourceMillis(moduleName: String): Long? =
        moduleFor(moduleName)?.let { ModuleFreshness.newestModuleSourceMtime(it) }

    private fun moduleFor(moduleName: String) = ModuleManager.getInstance(project).findModuleByName(moduleName)

    companion object {
        /**
         * The rule [isStale] applies, separated from the project-model lookup that feeds it so it can be tested
         * without one. [newestSourceMillis] is null when the module cannot be resolved at all.
         *
         * Strictly newer, not "newer or equal": a file written in the very millisecond the run was launched was
         * written before Gradle got as far as reading it, and treating that as an edit would mark a module stale
         * for the save that started the verify.
         */
        fun isStale(measurement: Measurement, newestSourceMillis: Long?): Boolean =
            measurement.stale || newestSourceMillis == null || newestSourceMillis > measurement.ranAtMillis

        fun getInstance(project: Project): SnapshotVerifyStore = project.service()
    }
}
