package com.devomer.previewgallery.service

import com.devomer.previewgallery.render.SnapshotVerifyRunner
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
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
class SnapshotVerifyStore {

    /** [stale] means the module's source changed after [ranAtMillis] — the results still describe a real run,
     *  just not the code on disk now. */
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

    fun markStale(moduleName: String) {
        runs.computeIfPresent(moduleName) { _, run -> if (run.stale) run else run.copy(stale = true) }
    }

    /** Used when a change cannot be attributed to one module — a broad VFS event, or a project reload. Marking
     *  everything is the safe direction: a stale badge understates confidence, a fresh one overstates it. */
    fun markAllStale() {
        runs.replaceAll { _, run -> if (run.stale) run else run.copy(stale = true) }
    }

    companion object {
        fun getInstance(project: Project): SnapshotVerifyStore = project.service()
    }
}
