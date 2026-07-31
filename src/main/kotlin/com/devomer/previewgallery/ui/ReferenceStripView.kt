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

    /**
     * The size the strip occupies at [scale]. The gaps between the images and the label row under them are
     * **chrome**: they are laid out at the display's scale and do not grow with the zoom — only the images do.
     * [fitScale] has to invert exactly this, or Fit would not fit.
     */
    fun preferredStripSize(scale: Double): Dimension {
        if (images.isEmpty()) return Dimension(0, scaledLabelHeight())
        val width = images.sumOf { (it.image.width * scale).toInt() } + scaledGap() * (images.size - 1)
        val height = images.maxOf { (it.image.height * scale).toInt() } + scaledLabelHeight()
        return Dimension(width, height)
    }

    /**
     * The largest scale at which the whole strip fits [viewportWidth] x [viewportHeight].
     *
     * The chrome is **subtracted from the viewport**, never folded into the denominator: it does not scale (see
     * [preferredStripSize]), so dividing by `sum(width) + gaps` would return a scale whose own preferred size is
     * still larger than the viewport by up to one label row and one set of gaps — pressing Fit would grow
     * scrollbars, the exact defect PG12 existed to remove. The inverse holds exactly, truncation included:
     * `sum(trunc(w * s)) <= trunc(sum(w) * s)`.
     *
     * A viewport smaller than the chrome alone has no scale that fits; the floor is returned rather than a zero
     * or negative one, and the caller's own [ZoomMath.MIN]/[ZoomMath.MAX] clamp agrees with it.
     */
    fun fitScale(viewportWidth: Int, viewportHeight: Int): Double {
        if (images.isEmpty()) return 1.0
        val naturalWidth = images.sumOf { it.image.width }
        val naturalHeight = images.maxOf { it.image.height }
        if (naturalWidth <= 0 || naturalHeight <= 0) return 1.0
        val availableWidth = viewportWidth - scaledGap() * (images.size - 1)
        val availableHeight = viewportHeight - scaledLabelHeight()
        if (availableWidth <= 0 || availableHeight <= 0) return ZoomMath.MIN
        return minOf(availableWidth.toDouble() / naturalWidth, availableHeight.toDouble() / naturalHeight)
    }

    override fun paintComponent(g: Graphics) {
        if (images.isEmpty()) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            // One shared baseline for every label, at the tallest image's foot: [preferredStripSize] reserves a
            // single label row there, so drawing each label at its own image's height would leave a short
            // variant's name floating in the middle of the strip instead of on the row that was reserved for it.
            val baseline = images.maxOf { (it.image.height * scale).toInt() } + JBUI.scale(LABEL_BASELINE)
            var x = 0
            for (labelled in images) {
                val width = (labelled.image.width * scale).toInt()
                val height = (labelled.image.height * scale).toInt()
                g2.drawImage(labelled.image, x, 0, width, height, null)
                g2.color = JBColor.GRAY
                g2.drawString(labelled.variant, x, baseline)
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
