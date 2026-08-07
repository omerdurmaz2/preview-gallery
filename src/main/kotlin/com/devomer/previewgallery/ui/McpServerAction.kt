package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.service.McpServerService
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import javax.swing.Icon

/**
 * Opens the MCP dialog, and carries whether the server is up in its own icon.
 *
 * The state belongs on the button rather than behind it: a socket that agents depend on should not need a click
 * to find out it is down.
 *
 * `DumbAware` because the dialog only reports and toggles the socket — it reads no index, and an agent that
 * connects during indexing is told so by the tools themselves (spec D10).
 */
class McpServerAction(private val project: Project) : DumbAwareAction(
    PreviewGalleryBundle.message("action.mcpServer.text"),
    PreviewGalleryBundle.message("action.mcpServer.text"),
    AllIcons.General.Web,
) {

    /** Reads one `@Volatile` field and touches no PSI, so the toolbar's polling need not take the EDT for it. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val service = McpServerService.getInstance()
        val running = service.isRunning
        event.presentation.icon = iconFor(running)
        event.presentation.description = if (running) {
            PreviewGalleryBundle.message("action.mcpServer.running", service.port.toString())
        } else {
            PreviewGalleryBundle.message("action.mcpServer.text")
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        McpServerDialog(project).show()
    }

    companion object {
        /**
         * The platform's own live badge — the green dot a running run configuration carries — rather than an
         * icon of our own to keep in step. "This thing is up right now" is exactly what it means elsewhere in
         * the IDE, so it needs no explaining here.
         *
         * Built lazily: an `Icon` composed at class-initialisation time would load before the icon subsystem is
         * ready in a headless test.
         */
        private val RUNNING: Icon by lazy { ExecutionUtil.withLiveIndicator(AllIcons.General.Web) }

        internal fun iconFor(running: Boolean): Icon = if (running) RUNNING else AllIcons.General.Web
    }
}
