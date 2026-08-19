package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader

/**
 * Hides every preview that already has a snapshot, leaving the work queue.
 *
 * A different icon from the module filter beside it: two toggles carrying `AllIcons.General.Filter` would be
 * told apart only by position.
 *
 * The icon is a dashed, empty frame — a preview whose golden does not exist yet. It replaced
 * `AllIcons.General.InspectionsWarning`, which said the wrong thing: a preview without a snapshot is a state, not a
 * defect, and a warning triangle beside 854 of them reads as 854 problems.
 */
class CoverageFilterToggleAction(
    project: Project,
    onToggle: () -> Unit,
) : PersistentToggleAction(
    project,
    KEY,
    PreviewGalleryBundle.message("action.coverageFilter.text"),
    UNCOVERED_ICON,
    onToggle,
) {

    companion object {
        private const val KEY = "com.devomer.previewgallery.coverageFilter"

        private val UNCOVERED_ICON = IconLoader.getIcon("/icons/uncovered.svg", CoverageFilterToggleAction::class.java)

        fun isEnabled(project: Project): Boolean = PersistentToggleAction.isEnabled(project, KEY)
    }
}
