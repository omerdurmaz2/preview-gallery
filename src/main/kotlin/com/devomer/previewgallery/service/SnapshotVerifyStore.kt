package com.devomer.previewgallery.service

import com.devomer.previewgallery.render.SnapshotVerifyRunner
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiModificationTracker
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

    /** [stale] is the coarse flag [markAllStale] sets for an event too broad to compare against a PSI count — a
     *  VFS change, a project reload. [psiStamp] is what catches the event that one misses, an ordinary edit: it
     *  is [PsiModificationTracker.getModificationCount] as it stood when the run was captured, and [isStale]
     *  reads true the moment that count moves on, with no listener of its own to keep in step. */
    data class Run(
        val moduleName: String,
        val outcome: SnapshotVerifyRunner.Outcome,
        val results: List<SnapshotVerifyResults.SnapshotResult>,
        val ranAtMillis: Long,
        val psiStamp: Long,
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

    /** Whether [run]'s verdict no longer describes the code on disk (spec D4): either a coarse event marked it
     *  explicitly, or an ordinary edit moved [PsiModificationTracker] past [Run.psiStamp] — the same counter
     *  [PreviewIndexService] already keys its own cache on, so this reads a volatile field and needs no read
     *  action, cheap enough to call once per visible row. */
    fun isStale(run: Run): Boolean =
        run.stale || run.psiStamp != PsiModificationTracker.getInstance(project).modificationCount

    companion object {
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
            psiStamp: Long,
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
                psiStamp = psiStamp,
            )
        }

        fun getInstance(project: Project): SnapshotVerifyStore = project.service()
    }
}
