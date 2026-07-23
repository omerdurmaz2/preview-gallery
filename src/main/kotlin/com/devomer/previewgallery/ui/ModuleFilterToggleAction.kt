package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.ide.util.PropertiesComponent

class ModuleFilterToggleAction(
    private val project: Project,
    private val onToggle: () -> Unit,
) : ToggleAction(
    PreviewGalleryBundle.message("action.moduleFilter.text"),
    PreviewGalleryBundle.message("action.moduleFilter.text"),
    AllIcons.General.Filter,
),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(event: AnActionEvent): Boolean = isEnabled(project)

    override fun setSelected(event: AnActionEvent, selected: Boolean) {
        PropertiesComponent.getInstance(project).setValue(KEY, selected)
        onToggle()
    }

    companion object {
        private const val KEY = "com.devomer.previewgallery.moduleFilter"

        fun isEnabled(project: Project): Boolean = PropertiesComponent.getInstance(project).getBoolean(KEY, false)
    }
}
