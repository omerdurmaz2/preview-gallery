package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import java.awt.Graphics

/** The lower half of the tool window. Phase 2 replaces its contents with the rendered image. */
class PreviewRenderPlaceholder : JBPanel<PreviewRenderPlaceholder>() {

    private val emptyText = object : StatusText(this) {
        override fun isStatusVisible(): Boolean = true
    }.apply { text = PreviewGalleryBundle.message("render.placeholder") }

    init {
        border = JBUI.Borders.empty()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        emptyText.paint(this, g)
    }
}
