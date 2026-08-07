package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.service.McpServerService
import com.devomer.previewgallery.service.McpServerStartup
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The whole MCP surface in one dialog: whether the server is up, one button to change that, and the client
 * configuration — snippet, and the client's own config file opened in the editor.
 *
 * One toolbar button rather than two: the toggle and the configuration are the same question the first time
 * ("how do I point Claude at this?") and the toolbar already carries six controls.
 *
 * The file is opened rather than described because a path in a label is a path the user then has to go find,
 * and every one of these clients hides its config somewhere different.
 */
class McpServerDialog(private val project: Project) : DialogWrapper(project) {

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
        panel.preferredSize = Dimension(JBUI.scale(620), JBUI.scale(400))

        val header = JPanel(BorderLayout(JBUI.scale(8), 0))
        header.add(status, BorderLayout.CENTER)
        toggle.addActionListener { onToggle() }
        header.add(toggle, BorderLayout.EAST)
        panel.add(header, BorderLayout.NORTH)

        val tabs = JBTabbedPane()
        McpClientConfigs.all(service.port).forEach { tabs.addTab(it.label, clientPanel(it)) }
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

    private fun clientPanel(client: McpClientConfig): JComponent {
        val text = JBTextArea(client.snippet)
        text.isEditable = false
        text.lineWrap = false

        val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        val configFile = client.configFile
        if (configFile != null) {
            panel.add(JBLabel(configFile.toString()), BorderLayout.NORTH)
        }
        panel.add(JBScrollPane(text), BorderLayout.CENTER)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        val copy = JButton(PreviewGalleryBundle.message("mcp.copy"))
        copy.addActionListener { copyToClipboard(client.snippet) }
        buttons.add(copy)
        if (configFile != null) {
            val exists = Files.exists(configFile)
            val open = JButton(
                PreviewGalleryBundle.message(if (exists) "mcp.openConfig" else "mcp.createConfig"),
            )
            open.toolTipText = configFile.toString()
            open.addActionListener { openConfig(client, configFile) }
            buttons.add(open)
        }
        panel.add(buttons, BorderLayout.SOUTH)
        return panel
    }

    /**
     * Opens the client's config, creating it from the snippet when it does not exist yet.
     *
     * An existing file is never rewritten — merging into someone's editor config is not something a plugin
     * should attempt unasked, and a botched merge costs more than a paste. The snippet goes to the clipboard on
     * the way out instead, so the file opens with the text already waiting to be pasted.
     */
    private fun openConfig(client: McpClientConfig, configFile: Path) {
        copyToClipboard(client.snippet)
        try {
            if (!Files.exists(configFile)) {
                configFile.parent?.let { Files.createDirectories(it) }
                Files.writeString(configFile, client.snippet + "\n")
            }
        } catch (e: IOException) {
            thisLogger().warn("Failed to create the MCP config file at $configFile", e)
            Messages.showWarningDialog(
                contentPanel,
                PreviewGalleryBundle.message("mcp.configFailed", configFile.toString()),
                title,
            )
            return
        }
        val virtualFile = VfsUtil.findFile(configFile, true)
        if (virtualFile == null) {
            Messages.showWarningDialog(
                contentPanel,
                PreviewGalleryBundle.message("mcp.configFailed", configFile.toString()),
                title,
            )
            return
        }
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
        close(OK_EXIT_CODE)
    }

    private fun copyToClipboard(snippet: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(snippet), null)
    }
}
