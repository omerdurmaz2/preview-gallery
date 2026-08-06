package com.devomer.previewgallery.service

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

/**
 * The directories the Compose Preview Screenshot Testing plugin commits its reference PNGs to, for one module.
 *
 * They are **discovered**, never hardcoded: the plugin writes to `src/screenshotTest<Variant>/reference`, and a
 * flavoured module therefore has one such directory per variant (`screenshotTestGoogleDebug`,
 * `screenshotTestHuaweiDebug`) rather than the single `screenshotTestDebug` a library module has. Reading the
 * variant off the directory name is what lets the no-reference message name a Gradle task the module actually
 * has, and needs no build model — the same posture [SnapshotSourceScanner] takes for the source set.
 *
 * Requiring a `reference` child is what keeps `src/screenshotTest` — the *source* directory, which matches the
 * same prefix — from being mistaken for a root.
 */
object ReferenceRoots {

    private const val SRC = "src"
    private const val SCREENSHOT_TEST = "screenshotTest"
    private const val REFERENCE = "reference"

    /**
     * One committed-reference directory. [directory] is the `reference` directory itself, so a caller resolves a
     * package path against it directly; [buildVariant] is null when the source-set name carries no suffix to read
     * one from, which is the only case that yields no Gradle task name.
     */
    data class Root(val sourceSetName: String, val buildVariant: String?, val directory: VirtualFile) {

        /** The label token for this root, used only when more than one root contributes to a strip. */
        val token: String
            get() = buildVariant?.replaceFirstChar { it.lowercaseChar() } ?: sourceSetName
    }

    /**
     * Brings [moduleDirectory]'s reference directories up to date with what is actually on disk.
     *
     * **Must not run under a read lock** — the platform rejects a synchronous refresh there ("Do not perform a
     * synchronous refresh under read lock") — so this is its own step between the caller's two read actions,
     * never inside one.
     *
     * Two passes, and one cannot do the job of the other. The shallow pass reloads `src`'s own children, which is
     * what makes a `screenshotTestGoogleDebug` directory created since the last sync appear at all; the recursive
     * pass reloads each source set's subtree, which is what makes a PNG added to an already-listed `reference`
     * directory appear. A single recursive pass over `src` would walk every source file in the module instead of
     * the snapshot corpus alone.
     *
     * The shallow pass can itself discover that `src` no longer exists at all — a branch switch, a `git clean`,
     * a removed module — which invalidates the very directory this function was asked about. Reading its
     * children afterwards is safe only by accident of which thread calls it: `VirtualDirectoryImpl` throws
     * `InvalidVirtualFileAccessException` for an invalidated directory only when the caller holds read access,
     * and silently returns no children otherwise. Left unguarded, a background caller would see an empty `src`
     * while an EDT caller crashed on that very same deletion; checking validity right here, before either that
     * read or the recursive pass depending on it can run, makes both paths take the same early return instead of
     * only one of them.
     */
    fun refresh(moduleDirectory: VirtualFile) {
        val src = moduleDirectory.findChild(SRC)?.takeIf { it.isDirectory } ?: return
        VfsUtil.markDirtyAndRefresh(false, false, true, src)
        if (!src.isValid) return
        val sourceSets = sourceSetDirectories(moduleDirectory)
        if (sourceSets.isEmpty()) return
        VfsUtil.markDirtyAndRefresh(false, true, true, *sourceSets.toTypedArray())
    }

    /**
     * Every reference directory under [moduleDirectory], sorted by source-set name so a strip's left-to-right
     * order is stable across selections.
     *
     * Reads the VFS as it stands; call [refresh] first if the answer must include what another process just
     * wrote. [moduleDirectory] is checked for validity first because that same [refresh] call is exactly what
     * can discover it was deleted, and reading a deleted directory under read access throws
     * `InvalidVirtualFileAccessException` rather than returning the empty list a deletion deserves.
     */
    fun of(moduleDirectory: VirtualFile): List<Root> {
        if (!moduleDirectory.isValid) return emptyList()
        return sourceSetDirectories(moduleDirectory)
            .mapNotNull { sourceSet ->
                val reference = sourceSet.findChild(REFERENCE)?.takeIf { it.isDirectory } ?: return@mapNotNull null
                Root(
                    sourceSetName = sourceSet.name,
                    buildVariant = sourceSet.name.removePrefix(SCREENSHOT_TEST).ifEmpty { null },
                    directory = reference,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.sourceSetName })
    }

    /** The `screenshotTest*` source-set directories under [moduleDirectory]'s `src`, or empty when it has none —
     *  the walk [refresh] and [of] both need, kept in one place so their filters cannot drift apart. */
    private fun sourceSetDirectories(moduleDirectory: VirtualFile): List<VirtualFile> {
        val src = moduleDirectory.findChild(SRC)?.takeIf { it.isDirectory } ?: return emptyList()
        return src.children.orEmpty().filter { it.isDirectory && it.name.startsWith(SCREENSHOT_TEST) }
    }

    /** The Gradle task that regenerates [buildVariant]'s references, or null when the build variant could not be
     *  read. */
    fun updateTask(buildVariant: String?): String? = buildVariant?.let { "update${it}ScreenshotTest" }
}
