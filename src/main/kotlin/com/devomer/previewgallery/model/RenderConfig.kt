package com.devomer.previewgallery.model

/** A fixed default in the MVP; the seam the later device/theme slice widens. */
data class RenderConfig(
    val deviceSpec: String = "spec:width=411dp,height=891dp",
    val themeName: String? = null,
    val uiMode: Int = 0,
    val apiLevel: Int = -1,
    val fontScale: Float = 1.0f,
) {
    companion object { val DEFAULT = RenderConfig() }
}
