package com.devomer.previewgallery.service

import com.devomer.previewgallery.render.ModuleFreshness
import com.devomer.previewgallery.render.SnapshotVerifyRunner
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * The last verify run per module.
 *
 * Editing the module marks its run **stale** rather than dropping it (spec D4). "It was green" and "it is
 * green" are different facts, and this project has repeatedly chosen to keep that distinction visible instead of
 * silent; deleting also throws away minutes of Gradle over one keystroke.
 */
@Service(Service.Level.PROJECT)
class SnapshotVerifyStore(private val project: Project) {

    /** [stale] is the coarse flag [markAllStale] sets for an event too broad to attribute to one module — a VFS
     *  change, a project reload. [ranAtMillis] is what catches the event that one misses, an ordinary edit:
     *  [isStale] compares it against when the module's own sources were last written, with no listener of its
     *  own to keep in step. It is when the Gradle task was *launched*, not when it finished, which is what makes
     *  an edit landing mid-run read stale the moment the run publishes. */
    data class Run(
        val moduleName: String,
        val outcome: SnapshotVerifyRunner.Outcome,
        val results: List<SnapshotVerifyResults.SnapshotResult>,
        val ranAtMillis: Long,
        val stale: Boolean = false,
    )

    private val runs = ConcurrentHashMap<String, Run>()

    fun put(run: Run) {
        runs[run.moduleName] = run
    }

    fun forModule(moduleName: String): Run? = runs[moduleName]

    /** The result for one snapshot function and variant, or null when this module has no run, or the run has no
     *  entry for it — a snapshot added since the run is exactly that case, and it must read as "unknown" rather
     *  than as "passed". */
    fun resultFor(
        moduleName: String,
        methodName: String,
        variant: String,
    ): SnapshotVerifyResults.SnapshotResult? =
        runs[moduleName]?.results?.firstOrNull { it.methodName == methodName && it.variant == variant }

    /** Used when a change cannot be attributed to one module — a broad VFS event, or a project reload. Marking
     *  everything is the safe direction: a stale badge understates confidence, a fresh one overstates it. */
    fun markAllStale() {
        runs.replaceAll { _, run -> if (run.stale) run else run.copy(stale = true) }
    }

    /**
     * Whether [run]'s verdict no longer describes the code on disk (spec D4): either a coarse event marked it
     * explicitly, or the module's own sources were written after the run that measured them started.
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
    fun isStale(run: Run): Boolean = isStale(run, newestSourceMillis(run.moduleName))

    private fun newestSourceMillis(moduleName: String): Long? =
        ModuleManager.getInstance(project).findModuleByName(moduleName)
            ?.let { ModuleFreshness.newestModuleSourceMtime(it) }

    companion object {
        /**
         * The rule [isStale] applies, separated from the project-model lookup that feeds it so it can be tested
         * without one. [newestSourceMillis] is null when the module cannot be resolved at all.
         *
         * Strictly newer, not "newer or equal": a file written in the very millisecond the run was launched was
         * written before Gradle got as far as reading it, and treating that as an edit would mark a module stale
         * for the save that started the verify.
         */
        fun isStale(run: Run, newestSourceMillis: Long?): Boolean =
            run.stale || newestSourceMillis == null || newestSourceMillis > run.ranAtMillis

        /**
         * What a finished run should store for [moduleName], or null when it must publish nothing at all and
         * leave [previous] standing.
         *
         * A run that produced no results measured nothing, whatever Gradle's exit status said: a compile failure
         * and a clean pass are both "the task returned", and only the results tell them apart (spec D8). A task
         * name Gradle does not have lands here too — the spec's error table lists it separately, but telling it
         * from a compile failure would mean parsing Gradle's output, and both answer the user the same way.
         *
         * "Measured nothing" is not evidence that [previous] is wrong, though, and that is why an empty result
         * set never overwrites one — *unless* [explicit]. Two ordinary paths reach it with a perfectly good
         * earlier run on record: the user pressing Stop (the spec's error table: *run cancelled → nothing
         * published, the previous result stays*), and Gradle passing the task UP-TO-DATE on a second run over
         * unchanged sources, which rewrites no XML and so fails [SnapshotVerifyResults]' timestamp guard.
         * Publishing [SnapshotVerifyRunner.Outcome.BUILD_FAILED] for either would drop a green module's badge on
         * its normal second verify.
         *
         * [previous] going stale does not change that: a stale verdict is still the best one there is, and
         * [isStale] is what says so at the point it is shown.
         *
         * [explicit] — the user pressing Verify rather than the selection triggering one — inverts it, and must.
         * Silence is an acceptable answer to a question nobody asked; it is never an acceptable answer to a
         * button press. An explicit run that measured nothing publishes exactly that, so the pane can say
         * "Not verified" or "Verify did not complete" instead of leaving the previous verdict standing as though
         * the button had done nothing.
         */
        fun resolve(
            moduleName: String,
            outcome: SnapshotVerifyRunner.Outcome,
            results: List<SnapshotVerifyResults.SnapshotResult>,
            ranAtMillis: Long,
            previous: Run?,
            explicit: Boolean,
        ): Run? {
            if (results.isEmpty() && previous != null && !explicit) return null
            val resolved = when {
                results.isNotEmpty() -> SnapshotVerifyRunner.Outcome.RAN
                outcome == SnapshotVerifyRunner.Outcome.NOT_RUN -> SnapshotVerifyRunner.Outcome.NOT_RUN
                else -> SnapshotVerifyRunner.Outcome.BUILD_FAILED
            }
            return Run(
                moduleName = moduleName,
                outcome = resolved,
                results = results,
                ranAtMillis = ranAtMillis,
            )
        }

        fun getInstance(project: Project): SnapshotVerifyStore = project.service()
    }
}
