package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

/**
 * Opens the MCP dialog. `DumbAware` because the dialog only reports and toggles the socket — it reads no index,
 * and an agent that connects during indexing is told so by the tools themselves (spec D10).
 */
class McpServerAction(private val project: Project) : DumbAwareAction(
    PreviewGalleryBundle.message("action.mcpServer.text"),
    PreviewGalleryBundle.message("action.mcpServer.text"),
    AllIcons.General.Web,
) {

    override fun actionPerformed(event: AnActionEvent) {
        McpServerDialog(project).show()
    }
}
