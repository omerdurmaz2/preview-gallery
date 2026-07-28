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
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm

/**
 * Puts [ShowAllPreviewsAction] into the Compose preview's top-right toolbar, for Kotlin files only — see
 * [handlesFile]. [RHS_TOOLBAR_PLACE] is shared with the XML Layout Editor's north-east toolbar group, so without
 * that filter every non-Kotlin file open would pay the toolbar traversal for nothing and could get a button that
 * opens a gallery with no selection.
 *
 * That toolbar has no XML group and no extension point — Android Studio builds it programmatically as a
 * `DefaultActionGroup` inside an `ActionToolbar` whose place is [RHS_TOOLBAR_PLACE] — so the button is injected at
 * runtime. It is also built lazily, only once the preview pane exists, hence the bounded retry: attempts are spaced
 * an escalating [RETRY_DELAY_STEP_MS] apart (400 ms, 800 ms, 1200 ms, ...), covering roughly 14.4 seconds across
 * [MAX_ATTEMPTS] attempts — wide enough to outlast most cold-open indexing/Gradle-sync delays without needing more
 * attempts. After that window elapses the injector gives up silently for that schedule and the action stays
 * reachable through Find Action — except that [scheduleInjection], when it finds the project still indexing, also
 * re-runs the whole schedule once the IDE leaves dumb mode, since the preview representation can still be building
 * after indexing is what pushed the toolbar's construction past the window.
 *
 * [RHS_TOOLBAR_PLACE] is the single point of contact with that internal layout; if a future Studio renames it, the
 * feature loses its button, not its correctness.
 */
@Service(Service.Level.PROJECT)
class PreviewToolbarInjector(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /**
     * Schedules injection attempts for [file]'s editors, restarting the retry window.
     *
     * A no-op for files that cannot host a Compose preview (see [handlesFile]). If the project is currently
     * indexing, the schedule is also re-run once dumb mode ends, in case that is what pushed the toolbar's
     * construction past the retry window.
     */
    fun scheduleInjection(file: VirtualFile) {
        if (!handlesFile(file)) return
        restartAttempts(file)
        if (DumbService.isDumb(project)) {
            DumbService.getInstance(project).runWhenSmart {
                if (!project.isDisposed) restartAttempts(file)
            }
        }
    }

    private fun restartAttempts(file: VirtualFile) {
        alarm.cancelAllRequests()
        scheduleAttempt(file, attempt = 0)
    }

    private fun scheduleAttempt(file: VirtualFile, attempt: Int) {
        if (attempt >= MAX_ATTEMPTS || project.isDisposed) return
        alarm.addRequest({
            if (!inject(file)) scheduleAttempt(file, attempt + 1)
        }, RETRY_DELAY_STEP_MS * (attempt + 1))
    }

    /**
     * Returns true when the retry loop should stop: either the action now sits in at least one of [file]'s
     * preview toolbars, or retrying cannot possibly succeed (the project is disposed, or the action itself is not
     * registered).
     */
    private fun inject(file: VirtualFile): Boolean {
        if (project.isDisposed) return true
        val action = ActionManager.getInstance().getAction(ShowAllPreviewsAction.ID) ?: return true
        var present = false
        for (editor in FileEditorManager.getInstance(project).getAllEditors(file)) {
            for (toolbar in ToolbarLocator.findByPlace(editor.component, RHS_TOOLBAR_PLACE)) {
                val group = toolbar.actionGroup as? DefaultActionGroup ?: continue
                if (ActionGroupInjector.addOnce(group, action)) {
                    // Synchronous on purpose: the async update variant queues the refresh and returns immediately,
                    // which would race this loop's "stop retrying" signal (`present = true` below) if the toolbar
                    // hadn't actually finished updating yet.
                    toolbar.updateActionsImmediately()
                }
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
        private const val RETRY_DELAY_STEP_MS = 400
        private const val KOTLIN_FILE_EXTENSION = "kt"

        fun getInstance(project: Project): PreviewToolbarInjector = project.service()

        /**
         * Returns true only for files that can host a Compose `@Preview` — Kotlin source files.
         *
         * [RHS_TOOLBAR_PLACE] is shared with the XML Layout Editor: `DefaultNlToolbarActionGroups.getNorthEastGroup()`
         * also returns a plain `DefaultActionGroup` there, so an `.xml` (or any non-Kotlin) file would otherwise be
         * injected too, and clicking the button would collapse that editor and open a gallery with no selection.
         */
        internal fun handlesFile(file: VirtualFile): Boolean = file.extension == KOTLIN_FILE_EXTENSION
    }
}
