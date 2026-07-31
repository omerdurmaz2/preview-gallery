package com.devomer.previewgallery.service

import com.devomer.previewgallery.index.PreviewIndex
import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.indexing.FileBasedIndex

/**
 * Reads [PreviewIndex] for gallery previews and [SnapshotSourceScanner] for snapshot rows, and joins each with
 * the module and file it belongs to.
 *
 * Callers must invoke [findAll] and [findOrphanSnapshots] under a read action and off the EDT — both share the
 * same cached computation, which touches the index, the VFS and the project model.
 */
@Service(Service.Level.PROJECT)
class PreviewIndexService(private val project: Project) {

    private val refreshTracker = SimpleModificationTracker()

    /** Gallery previews only — snapshot rows are matched off and excluded; see [findOrphanSnapshots]. */
    fun findAll(): List<PreviewEntry> = rows().previews

    /** Snapshots that match no preview in their module; the tree shows them under their own branch. */
    fun findOrphanSnapshots(): List<PreviewEntry> = rows().orphans

    /** Forces the next [findAll] to recompute, for project-model changes that raise no PSI event. */
    fun refresh() {
        refreshTracker.incModificationCount()
    }

    private fun rows(): Rows {
        if (DumbService.isDumb(project)) return Rows(emptyList(), emptyList())
        return CachedValuesManager.getManager(project).getCachedValue(
            project,
            CACHE_KEY,
            {
                CachedValueProvider.Result.create(
                    resolve(sorted(compute() + SnapshotSourceScanner.scan(project))),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    refreshTracker,
                )
            },
            false,
        )
    }

    /**
     * Joins coverage onto [entries]. A module is applicable when [SnapshotSourceScanner] found a `screenshotTest`
     * directory for it — the same walk that produced the snapshot rows, so the two can no longer disagree.
     */
    private fun resolve(entries: List<PreviewEntry>): Rows {
        val modules = SnapshotSourceScanner.directories(project).mapTo(HashSet()) { it.moduleName }
        val resolved = SnapshotCoverageResolver.resolve(entries, modules) { row, coverage, snapshots ->
            row.copy(coverage = coverage, snapshots = snapshots)
        }
        return Rows(resolved.previews, resolved.orphans)
    }

    private fun compute(): List<PreviewEntry> {
        val index = FileBasedIndex.getInstance()
        val fileIndex = ProjectFileIndex.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val entries = mutableListOf<PreviewEntry>()

        index.processAllKeys(PreviewIndex.NAME, { key ->
            index.processValues(PreviewIndex.NAME, key, null, { file, values ->
                val module = fileIndex.getModuleForFile(file)
                if (module != null) {
                    // Snapshots come from SnapshotSourceScanner, which sees them whether or not the source set
                    // reached the project model. Keeping the index's copy too would double every snapshot in a
                    // project where it did.
                    values.filterNot { it.isSnapshotTest }
                        .forEach { entries += PreviewEntry(it, module.name, file) }
                }
                true
            }, scope)
            true
        }, project)

        return entries
    }

    private fun sorted(entries: List<PreviewEntry>): List<PreviewEntry> = entries.sortedWith(
        compareBy<PreviewEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.moduleName }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.indexed.packageName }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.indexed.displayName },
    )

    /** The two halves [SnapshotCoverageResolver] splits a computation into, cached together so one PSI change
     *  recomputes both instead of caching them under separate, possibly-inconsistent keys. */
    private data class Rows(val previews: List<PreviewEntry>, val orphans: List<PreviewEntry>)

    companion object {
        private val CACHE_KEY = Key.create<CachedValue<Rows>>("com.devomer.previewgallery.entries")

        fun getInstance(project: Project): PreviewIndexService = project.service()
    }
}
