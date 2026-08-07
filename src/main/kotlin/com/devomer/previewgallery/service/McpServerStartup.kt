package com.devomer.previewgallery.service

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Brings the server back on IDE start when the user left it on.
 *
 * A project activity for an application-level server: there is no application-level "started" hook a plugin
 * can use, and the first project to open is the earliest point an agent could be waiting. Starting twice is
 * a no-op ([McpServerService.StartResult.AlreadyRunning]), so the second project changes nothing.
 */
class McpServerStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!PropertiesComponent.getInstance().getBoolean(ENABLED_KEY, false)) return
        McpServerService.getInstance().start()
    }

    companion object {
        const val ENABLED_KEY = "com.devomer.previewgallery.mcpServerEnabled"
    }
}
