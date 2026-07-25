package com.devomer.previewgallery.ui

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Exports a raw render [BufferedImage] — no overlay, native resolution. The save path takes a [File] (the caller
 * supplies it from a file chooser) so it is unit-testable without UI; the clipboard path is AWT-only.
 */
object RenderImageExporter {

    /** Writes [image] as PNG to [file]. Returns whether it succeeded; the caller logs/notifies on false. */
    fun savePng(image: BufferedImage, file: File): Boolean =
        try {
            ImageIO.write(image, "png", file)
        } catch (e: IOException) {
            false
        }

    /** Puts [image] on the system clipboard as an AWT image transferable. */
    fun copyToClipboard(image: BufferedImage) {
        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any =
                if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
    }
}
