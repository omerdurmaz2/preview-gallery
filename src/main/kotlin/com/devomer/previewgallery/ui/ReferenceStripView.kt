package com.devomer.previewgallery.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JComponent

/**
 * Every reference image of one snapshot, laid out left to right at a single shared scale with its variant name
 * underneath (spec D7).
 *
 * One scale for the whole strip is the point: the narrow variant exists to catch horizontal overflow, and
 * scaling each image to its own box would render a 320 dp and a 411 dp snapshot at the same apparent width and
 * hide exactly the difference the reader is looking for.
 */
class ReferenceStripView(private val images: List<LabelledImage>) : JComponent() {

    data class LabelledImage(val variant: String, val image: BufferedImage)

    private var scale: Double = 1.0

    fun setScale(scale: Double) {
        this.scale = scale
        preferredSize = preferredStripSize(scale)
        revalidate()
        repaint()
    }

    fun preferredStripSize(scale: Double): Dimension {
        if (images.isEmpty()) return Dimension(0, scaledLabelHeight())
        val width = images.sumOf { (it.image.width * scale).toInt() } + scaledGap() * (images.size - 1)
        val height = images.maxOf { (it.image.height * scale).toInt() } + scaledLabelHeight()
        return Dimension(width, height)
    }

    /** The largest scale at which the whole strip fits [viewportWidth] x [viewportHeight]. */
    fun fitScale(viewportWidth: Int, viewportHeight: Int): Double {
        if (images.isEmpty()) return 1.0
        val naturalWidth = images.sumOf { it.image.width } + scaledGap() * (images.size - 1)
        val naturalHeight = images.maxOf { it.image.height } + scaledLabelHeight()
        if (naturalWidth <= 0 || naturalHeight <= 0) return 1.0
        return minOf(viewportWidth.toDouble() / naturalWidth, viewportHeight.toDouble() / naturalHeight)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            var x = 0
            for (labelled in images) {
                val width = (labelled.image.width * scale).toInt()
                val height = (labelled.image.height * scale).toInt()
                g2.drawImage(labelled.image, x, 0, width, height, null)
                g2.color = JBColor.GRAY
                g2.drawString(labelled.variant, x, height + JBUI.scale(LABEL_BASELINE))
                x += width + scaledGap()
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val GAP = 16
        private const val LABEL_HEIGHT = 20
        private const val LABEL_BASELINE = 14

        /** Horizontal space between two variants, at the display's scale. */
        fun scaledGap(): Int = JBUI.scale(GAP)

        /** Height of the variant-label row under the images, at the display's scale. */
        fun scaledLabelHeight(): Int = JBUI.scale(LABEL_HEIGHT)
    }
}
