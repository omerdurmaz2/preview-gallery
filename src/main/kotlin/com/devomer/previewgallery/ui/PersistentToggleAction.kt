package com.devomer.previewgallery.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import javax.swing.Icon

/**
 * A toolbar toggle whose state lives in [PropertiesComponent], so it is per project and survives a restart.
 *
 * The two filters in this window differ only in their storage key, their text and their icon. Keeping the
 * persistence in one place is what stops them from drifting apart — a change to how one is remembered cannot
 * apply to only one of them.
 */
abstract class PersistentToggleAction(
    private val project: Project,
    private val storageKey: String,
    text: String,
    icon: Icon,
    private val onToggle: () -> Unit,
) : ToggleAction(text, text, icon),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(event: AnActionEvent): Boolean = isEnabled(project, storageKey)

    override fun setSelected(event: AnActionEvent, selected: Boolean) {
        PropertiesComponent.getInstance(project).setValue(storageKey, selected)
        onToggle()
    }

    companion object {
        /** Read without constructing the action: the panel asks for the state on every filter pass. */
        fun isEnabled(project: Project, storageKey: String): Boolean =
            PropertiesComponent.getInstance(project).getBoolean(storageKey, false)
    }
}
