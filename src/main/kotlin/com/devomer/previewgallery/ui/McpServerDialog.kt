package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.service.McpServerService
import com.devomer.previewgallery.service.McpServerStartup
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.awt.Toolkit
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The whole MCP surface in one dialog: whether the server is up, one button to change that, and the client
 * configuration snippets.
 *
 * One toolbar button rather than two: the toggle and the configuration are the same question the first time
 * ("how do I point Claude at this?") and the toolbar already carries six controls.
 */
class McpServerDialog(project: Project) : DialogWrapper(project) {

    private val service = McpServerService.getInstance()
    private val status = JBLabel()
    private val toggle = JButton()

    init {
        title = PreviewGalleryBundle.message("mcp.dialog.title")
        init()
        refresh()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(360))

        val header = JPanel(BorderLayout(JBUI.scale(8), 0))
        header.add(status, BorderLayout.CENTER)
        toggle.addActionListener { onToggle() }
        header.add(toggle, BorderLayout.EAST)
        panel.add(header, BorderLayout.NORTH)

        val tabs = JBTabbedPane()
        tabs.addTab("Claude Code", snippetPanel(REMOTE_CONFIG))
        tabs.addTab("Cursor", snippetPanel(REMOTE_CONFIG))
        tabs.addTab("Raw URL", snippetPanel("http://localhost:${service.port}/mcp"))
        panel.add(tabs, BorderLayout.CENTER)
        panel.add(JBLabel(PreviewGalleryBundle.message("mcp.configHint")), BorderLayout.SOUTH)
        return panel
    }

    override fun createActions() = arrayOf(okAction)

    private fun onToggle() {
        if (service.isRunning) {
            service.stop()
            PropertiesComponent.getInstance().setValue(McpServerStartup.ENABLED_KEY, false)
        } else {
            when (val result = service.start()) {
                is McpServerService.StartResult.PortInUse -> Messages.showWarningDialog(
                    contentPanel,
                    PreviewGalleryBundle.message("mcp.portInUse", result.port.toString()),
                    title,
                )
                else -> PropertiesComponent.getInstance().setValue(McpServerStartup.ENABLED_KEY, true)
            }
        }
        refresh()
    }

    private fun refresh() {
        status.text = if (service.isRunning) {
            PreviewGalleryBundle.message("mcp.status.running", service.port.toString())
        } else {
            PreviewGalleryBundle.message("mcp.status.stopped")
        }
        toggle.text = PreviewGalleryBundle.message(if (service.isRunning) "mcp.stop" else "mcp.start")
    }

    private fun snippetPanel(snippet: String): JComponent {
        val text = JBTextArea(snippet)
        text.isEditable = false
        text.lineWrap = false
        val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        panel.add(JBScrollPane(text), BorderLayout.CENTER)
        val copy = JButton("Copy")
        copy.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(snippet), null)
        }
        panel.add(copy, BorderLayout.SOUTH)
        return panel
    }

    private val REMOTE_CONFIG: String
        get() = """
        {
          "mcpServers": {
            "preview-gallery": {
              "command": "npx",
              "args": ["-y", "mcp-remote", "http://localhost:${service.port}/mcp"]
            }
          }
        }
        """.trimIndent()
}
