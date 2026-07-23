package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.datatransfer.StringSelection

class PreviewDetailPanel(private val project: Project) : JBPanel<PreviewDetailPanel>(GridBagLayout()) {

    private var entry: PreviewEntry? = null

    init {
        border = JBUI.Borders.empty(8)
        showEmpty()
    }

    fun show(entry: PreviewEntry?) {
        this.entry = entry
        removeAll()
        if (entry == null) showEmpty() else showEntry(entry)
        revalidate()
        repaint()
    }

    private fun showEmpty() {
        add(JBLabel(PreviewGalleryBundle.message("detail.empty")).apply { foreground = UIUtil.getInactiveTextColor() })
    }

    private fun showEntry(entry: PreviewEntry) {
        val line = runCatching {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                .getDocument(entry.file)
                ?.getLineNumber(entry.indexed.offset)
        }.getOrNull()

        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(2, 0, 2, 8)
        }
        PreviewDetailModel.fields(entry, entry.file.name, line).forEach { field ->
            constraints.gridx = 0
            add(JBLabel("${field.label}:").apply { foreground = UIUtil.getInactiveTextColor() }, constraints)
            constraints.gridx = 1
            add(JBLabel(field.value), constraints)
            constraints.gridy++
        }

        constraints.gridx = 0
        constraints.gridwidth = 2
        add(ActionLink(PreviewGalleryBundle.message("detail.openFile")) { navigate(entry) }, constraints)
        constraints.gridy++
        add(
            ActionLink(PreviewGalleryBundle.message("detail.copyFqn")) {
                CopyPasteManager.getInstance().setContents(StringSelection(entry.indexed.composableFqn))
            },
            constraints,
        )
    }

    private fun navigate(entry: PreviewEntry) {
        OpenFileDescriptor(project, entry.file, entry.indexed.offset).navigate(true)
    }
}
