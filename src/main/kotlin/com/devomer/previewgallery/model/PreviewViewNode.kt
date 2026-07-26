package com.devomer.previewgallery.model

import java.awt.Rectangle

/** Where a composable is declared, resolved from a rendered node's source key. [packageHash] is AS's
 *  same-named-file disambiguator (see [SourceFileDisambiguator]); null when unavailable. */
data class PreviewSourceLocation(val fileName: String, val lineNumber: Int, val offset: Int?, val packageHash: Int?)

/**
 * One node of the rendered composable tree, in render-pixel space. Plugin-owned so the UI never touches
 * Android Studio's `ViewInfo`; [com.devomer.previewgallery.render.LiveRenderer] converts the AS tree into this.
 */
data class PreviewViewNode(
    val bounds: Rectangle,
    val sourceLocation: PreviewSourceLocation?,
    val children: List<PreviewViewNode>,
)
