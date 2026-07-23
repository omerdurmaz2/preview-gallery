package com.devomer.previewgallery.searcheverywhere

import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.openapi.actionSystem.AnActionEvent

class PreviewSearchEverywhereContributorFactory : SearchEverywhereContributorFactory<PreviewEntry> {

    override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<PreviewEntry> {
        val project = requireNotNull(initEvent.project) { "Preview gallery search requires an open project" }
        return PreviewSearchEverywhereContributor(project)
    }
}
