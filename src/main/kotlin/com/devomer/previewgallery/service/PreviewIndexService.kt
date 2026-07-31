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
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
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
                // Probed once and threaded through both halves: the walk visits every module in the project, and
                // the reference project has 1371 of them.
                val sources = SnapshotSourceScanner.directories(project)
                CachedValueProvider.Result.create(
                    resolve(sorted(compute(sources) + SnapshotSourceScanner.scan(project, sources)), sources),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    refreshTracker,
                )
            },
            false,
        )
    }

    /**
     * Joins coverage onto [entries]. A module is applicable when [SnapshotSourceScanner] found a `screenshotTest`
     * directory for it, **or** when a snapshot row survived [compute] for it — a layout the probe does not
     * recognise still badges its rows off whatever the index managed to see, which is what Phase 13 did and what
     * dropping the whole index channel would have taken away.
     */
    private fun resolve(entries: List<PreviewEntry>, sources: List<SnapshotSourceScanner.Source>): Rows {
        val modules = sources.mapTo(HashSet()) { it.moduleName }
        entries.forEach { if (it.indexed.isSnapshotTest) modules += it.moduleName }
        val resolved = SnapshotCoverageResolver.resolve(entries, modules) { row, coverage, snapshots ->
            row.copy(coverage = coverage, snapshots = snapshots)
        }
        return Rows(resolved.previews, resolved.orphans)
    }

    /**
     * The index's own rows. A snapshot row is dropped only when its file is inside a directory
     * [SnapshotSourceScanner] already walked — there the scanner's copy is the better-attributed one and keeping
     * both would double every snapshot. Elsewhere the index is the only channel that can see the file at all, so
     * its row is kept rather than discarded as a class.
     */
    private fun compute(sources: List<SnapshotSourceScanner.Source>): List<PreviewEntry> {
        val index = FileBasedIndex.getInstance()
        val fileIndex = ProjectFileIndex.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val entries = mutableListOf<PreviewEntry>()

        index.processAllKeys(PreviewIndex.NAME, { key ->
            index.processValues(PreviewIndex.NAME, key, null, { file, values ->
                val module = fileIndex.getModuleForFile(file)
                if (module != null) {
                    val alreadyScanned = values.any { it.isSnapshotTest } && wasScanned(sources, file)
                    values.filterNot { it.isSnapshotTest && alreadyScanned }
                        .forEach { entries += PreviewEntry(it, module.name, file) }
                }
                true
            }, scope)
            true
        }, project)

        return entries
    }

    private fun wasScanned(sources: List<SnapshotSourceScanner.Source>, file: VirtualFile): Boolean =
        sources.any { VfsUtilCore.isAncestor(it.directory, file, false) }

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
