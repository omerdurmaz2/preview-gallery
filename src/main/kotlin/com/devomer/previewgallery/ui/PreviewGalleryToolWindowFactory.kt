package com.devomer.previewgallery.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * `DumbAware` on purpose: the tool window opens during indexing and shows the INDEXING state, then reloads once
 * the IDE is smart again.
 */
class PreviewGalleryToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PreviewGalleryPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val ID = "Compose Gallery"
    }
}
