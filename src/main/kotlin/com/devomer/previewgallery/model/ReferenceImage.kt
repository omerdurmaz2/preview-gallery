package com.devomer.previewgallery.model

import com.intellij.openapi.vfs.VirtualFile

/**
 * One committed reference PNG of a snapshot: the image the build actually compares against.
 *
 * [sourceSet] is the label token of the root it came from. It only reaches the screen when a strip spans more
 * than one root — a flavoured module commits a full set per variant, and two identical-looking images with no
 * way to tell which flavour each belongs to would be worse than one.
 */
data class ReferenceImage(val sourceSet: String, val variant: String, val file: VirtualFile)
