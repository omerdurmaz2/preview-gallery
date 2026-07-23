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
 * Reads [PreviewIndex] and joins each value with the module and file it belongs to.
 *
 * Callers must invoke [findAll] under a read action and off the EDT — it touches the index and the project model.
 */
@Service(Service.Level.PROJECT)
class PreviewIndexService(private val project: Project) {

    private val refreshTracker = SimpleModificationTracker()

    fun findAll(): List<PreviewEntry> {
        if (DumbService.isDumb(project)) return emptyList()
        return CachedValuesManager.getManager(project).getCachedValue(
            project,
            CACHE_KEY,
            {
                CachedValueProvider.Result.create(
                    compute(),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    refreshTracker,
                )
            },
            false,
        )
    }

    /** Forces the next [findAll] to recompute, for project-model changes that raise no PSI event. */
    fun refresh() {
        refreshTracker.incModificationCount()
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
                    values.forEach { entries += PreviewEntry(it, module.name, file) }
                }
                true
            }, scope)
            true
        }, project)

        return entries.sortedWith(
            compareBy<PreviewEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.moduleName }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.indexed.packageName }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.indexed.displayName },
        )
    }

    companion object {
        private val CACHE_KEY = Key.create<CachedValue<List<PreviewEntry>>>("com.devomer.previewgallery.entries")

        fun getInstance(project: Project): PreviewIndexService = project.service()
    }
}
