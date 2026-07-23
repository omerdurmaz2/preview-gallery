package com.devomer.previewgallery.searcheverywhere

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.search.PreviewSearchFilter
import com.devomer.previewgallery.service.PreviewIndexService
import com.devomer.previewgallery.ui.PreviewGalleryPanel
import com.devomer.previewgallery.ui.PreviewGalleryToolWindowFactory
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.Processor
import javax.swing.ListCellRenderer

class PreviewSearchEverywhereContributor(private val project: Project) :
    SearchEverywhereContributor<PreviewEntry> {

    override fun getSearchProviderId(): String = javaClass.name

    override fun getGroupName(): String = PreviewGalleryBundle.message("searcheverywhere.group")

    override fun getSortWeight(): Int = SORT_WEIGHT

    override fun showInFindResults(): Boolean = false

    override fun isShownInSeparateTab(): Boolean = false

    override fun fetchElements(
        pattern: String,
        indicator: ProgressIndicator,
        consumer: Processor<in PreviewEntry>,
    ) {
        if (DumbService.isDumb(project)) return
        val entries = ReadAction.compute<List<PreviewEntry>, RuntimeException> {
            PreviewIndexService.getInstance(project).findAll()
        }
        for (entry in PreviewSearchFilter.filter(entries, pattern)) {
            indicator.checkCanceled()
            if (!consumer.process(entry)) return
        }
    }

    override fun getElementsRenderer(): ListCellRenderer<in PreviewEntry> =
        SimpleListCellRenderer.create("") { entry ->
            "${entry.indexed.displayName}  —  ${entry.moduleName} · ${entry.indexed.packageName}"
        }

    override fun processSelectedItem(selected: PreviewEntry, modifiers: Int, searchText: String): Boolean {
        OpenFileDescriptor(project, selected.file, selected.indexed.offset).navigate(true)
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(PreviewGalleryToolWindowFactory.ID)
        toolWindow?.activate({
            val panel = toolWindow.contentManager.contents
                .firstNotNullOfOrNull { it.component as? PreviewGalleryPanel }
            panel?.selectEntry(selected.id)
        }, false)
        return true
    }

    override fun getDataForItem(element: PreviewEntry, dataId: String): Any? = null

    private companion object {
        const val SORT_WEIGHT = 900
    }
}
