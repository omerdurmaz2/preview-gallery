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

    /** [stale] is the coarse flag [markStale]/[markAllStale] set for an event too broad to compare against a PSI
     *  count — a VFS change, a project reload. [psiStamp] is what catches the event those miss, an ordinary
     *  edit: it is [PsiModificationTracker.getModificationCount] as it stood when the run was captured, and
     *  [isStale] reads true the moment that count moves on, with no listener of its own to keep in step. */
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

    fun markStale(moduleName: String) {
        runs.computeIfPresent(moduleName) { _, run -> if (run.stale) run else run.copy(stale = true) }
    }

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
        fun getInstance(project: Project): SnapshotVerifyStore = project.service()
    }
}
