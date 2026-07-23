package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.service.PreviewIndexService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

class RefreshAction(
    private val project: Project,
    private val onRefresh: () -> Unit,
) : AnAction(
    PreviewGalleryBundle.message("action.refresh.text"),
    PreviewGalleryBundle.message("action.refresh.text"),
    AllIcons.Actions.Refresh,
),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        PreviewIndexService.getInstance(project).refresh()
        onRefresh()
    }
}
