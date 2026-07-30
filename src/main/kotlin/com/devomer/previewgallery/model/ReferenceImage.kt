package com.devomer.previewgallery.model

import com.intellij.openapi.vfs.VirtualFile

/** One committed reference PNG of a snapshot: the image the build actually compares against. */
data class ReferenceImage(val variant: String, val file: VirtualFile)
