package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project

/**
 * Hides every preview that already has a snapshot, leaving the work queue.
 *
 * A different icon from the module filter beside it: two toggles carrying `AllIcons.General.Filter` would be
 * told apart only by position.
 */
class CoverageFilterToggleAction(
    project: Project,
    onToggle: () -> Unit,
) : PersistentToggleAction(
    project,
    KEY,
    PreviewGalleryBundle.message("action.coverageFilter.text"),
    AllIcons.General.InspectionsWarning,
    onToggle,
) {

    companion object {
        private const val KEY = "com.devomer.previewgallery.coverageFilter"

        fun isEnabled(project: Project): Boolean = PersistentToggleAction.isEnabled(project, KEY)
    }
}
