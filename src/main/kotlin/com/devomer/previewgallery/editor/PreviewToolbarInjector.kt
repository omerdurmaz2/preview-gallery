package com.devomer.previewgallery.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm

/**
 * Puts [ShowAllPreviewsAction] into the Compose preview's top-right toolbar.
 *
 * That toolbar has no XML group and no extension point — Android Studio builds it programmatically as a
 * `DefaultActionGroup` inside an `ActionToolbar` whose place is [RHS_TOOLBAR_PLACE] — so the button is injected at
 * runtime. It is also built lazily, only once the preview pane exists, hence the bounded retry: after
 * [MAX_ATTEMPTS] misses the injector gives up silently and the action stays reachable through Find Action.
 *
 * [RHS_TOOLBAR_PLACE] is the single point of contact with that internal layout; if a future Studio renames it, the
 * feature loses its button, not its correctness.
 */
@Service(Service.Level.PROJECT)
class PreviewToolbarInjector(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** Schedules injection attempts for [file]'s editors, restarting the retry window. */
    fun scheduleInjection(file: VirtualFile) {
        alarm.cancelAllRequests()
        scheduleAttempt(file, attempt = 0)
    }

    private fun scheduleAttempt(file: VirtualFile, attempt: Int) {
        if (attempt >= MAX_ATTEMPTS || project.isDisposed) return
        alarm.addRequest({
            if (!inject(file)) scheduleAttempt(file, attempt + 1)
        }, RETRY_DELAY_MS)
    }

    /** Returns true once the action sits in at least one of [file]'s preview toolbars. */
    private fun inject(file: VirtualFile): Boolean {
        if (project.isDisposed) return true
        val action = ActionManager.getInstance().getAction(ShowAllPreviewsAction.ID) ?: return true
        var present = false
        for (editor in FileEditorManager.getInstance(project).getAllEditors(file)) {
            for (toolbar in ToolbarLocator.findByPlace(editor.component, RHS_TOOLBAR_PLACE)) {
                val group = toolbar.actionGroup as? DefaultActionGroup ?: continue
                if (ActionGroupInjector.addOnce(group, action)) toolbar.updateActionsImmediately()
                present = true
            }
        }
        return present
    }

    override fun dispose() = Unit

    /** Re-runs the injection whenever the editor selection changes or a file is opened. */
    class Listener : FileEditorManagerListener, DumbAware {

        override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
            getInstance(source.project).scheduleInjection(file)
        }

        override fun selectionChanged(event: FileEditorManagerEvent) {
            val file = event.newFile ?: return
            getInstance(event.manager.project).scheduleInjection(file)
        }
    }

    companion object {
        /** The place `ActionsToolbar` gives the preview's north-east (top-right) group in Android Studio 253. */
        const val RHS_TOOLBAR_PLACE = "NlRhsConfigToolbar"
        private const val MAX_ATTEMPTS = 8
        private const val RETRY_DELAY_MS = 400

        fun getInstance(project: Project): PreviewToolbarInjector = project.service()
    }
}
