package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project

class ModuleFilterToggleAction(
    project: Project,
    onToggle: () -> Unit,
) : PersistentToggleAction(
    project,
    KEY,
    PreviewGalleryBundle.message("action.moduleFilter.text"),
    AllIcons.General.Filter,
    onToggle,
) {

    companion object {
        private const val KEY = "com.devomer.previewgallery.moduleFilter"

        fun isEnabled(project: Project): Boolean = PersistentToggleAction.isEnabled(project, KEY)
    }
}
