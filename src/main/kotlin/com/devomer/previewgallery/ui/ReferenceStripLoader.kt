package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.ReferenceImage
import com.devomer.previewgallery.service.ModuleDirectoryResolver
import com.devomer.previewgallery.service.ReferenceImageLocator
import com.devomer.previewgallery.service.ReferenceRoots
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.vfs.VirtualFile
import java.awt.image.BufferedImage
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Finds and decodes the committed reference PNGs of a snapshot row.
 *
 * Extracted from [PreviewGalleryPanel] when the preview row's reference mode gave it a second caller (PG19 spec
 * D8). The debounce, the background hop and the "is this row still selected" guard stay with the panel: they are
 * about the selection, which the panel owns. This object is about the files.
 *
 * [locate] and [decode] are separate because their threading rules differ and the callers rely on the seam:
 * [locate] takes read actions and can throw [com.intellij.openapi.progress.ProcessCanceledException], [decode]
 * must hold no read lock at all.
 */
class ReferenceStripLoader(
    private val project: Project,
    private val parentDisposable: Disposable,
    private val disposalCheck: CheckedDisposable,
) {

    /** What one lookup produced: the images to show, and the Gradle tasks to name when there are none. */
    data class Located(val images: List<ReferenceImage>, val tasks: List<String>)

    /** What [decode] found: the variants it could decode, and the ones it could not — reported in the strip's
     *  tooltip rather than dropped silently. */
    data class Decoded(
        val images: List<ReferenceStripView.LabelledImage>,
        val skipped: List<String>,
    )

    /**
     * Resolves [snapshot]'s module directory and locates its reference images under read actions, refreshing the
     * reference directories **between** them without one.
     *
     * The three-way split is forced, not stylistic. `ModuleDirectoryResolver` reads the project model and needs
     * the lock; `ReferenceRoots.refresh` is a synchronous VFS refresh, which the platform rejects under one; the
     * listing needs it again.
     *
     * Callable from the EDT as well as a background thread — which is what lets [PreviewGalleryPanel]'s inline
     * test branch call this directly instead of mirroring its steps — but legal on each for a different reason:
     * which thread calls [ReferenceRoots.refresh] is exactly what decides whether the call is legal, not merely
     * whether a read lock is held. On the EDT the read lock **is** held, and the refresh runs anyway only because
     * the platform exempts the EDT from the check that would otherwise reject it; off the EDT it runs only
     * because this call sits between the two read actions below rather than inside either. Getting either wrong
     * fails silently — a logged error, no refresh, no exception — so the panel would simply keep showing stale
     * images forever instead of crashing; that silent failure mode, not a stylistic preference, is why the lookup
     * stays split into three steps.
     *
     * Returns empty images and tasks when [snapshot] resolves to no module, or when [disposalCheck] fires between
     * the two read actions: the panel that would show the result is gone, so the refresh and the second read
     * action are both work nothing will use. Lets `ProcessCanceledException` propagate rather than catching it
     * here; only the caller's background hop needs to react to it, and it already does.
     *
     * Deleting the [ReferenceRoots.refresh] call below breaks no automated test — steps 2-3 of PG15's manual gate
     * are what actually cover it.
     */
    fun locate(snapshot: PreviewEntry): Located {
        val moduleDirectory = ReadAction.nonBlocking<VirtualFile?> {
            ModuleDirectoryResolver.resolve(project, snapshot.file)
        }
            .expireWith(parentDisposable)
            .executeSynchronously()
            ?: return Located(emptyList(), emptyList())
        if (disposalCheck.isDisposed) return Located(emptyList(), emptyList())
        ReferenceRoots.refresh(moduleDirectory)
        return ReadAction.nonBlocking<Located> { locateUnderRoots(snapshot, moduleDirectory) }
            .expireWith(parentDisposable)
            .executeSynchronously()
    }

    /**
     * Finds [snapshot]'s committed reference images under [moduleDirectory] (PG15 spec D3). **This** is the half
     * that needs a read action: the VFS directory listing, and nothing else.
     *
     * Every discovered root contributes, and the tasks that would regenerate them are collected here rather than
     * in the panel, because this is where the roots are known — the message has to name the module's own
     * variants, not the `Debug` a library module happens to have.
     */
    private fun locateUnderRoots(snapshot: PreviewEntry, moduleDirectory: VirtualFile): Located {
        val roots = ReferenceRoots.of(moduleDirectory)
        return Located(
            images = ReferenceImageLocator.locate(snapshot, roots),
            tasks = roots.mapNotNull { ReferenceRoots.updateTask(it.buildVariant) }.distinct().sorted(),
        )
    }

    /** Decodes what [locate] found — deliberately holding no read lock; a `VirtualFile`'s bytes are readable
     *  without one, and this is the slow half.
     *
     *  Labels come from [ReferenceImageLocator.labels], whose own KDoc states when one carries its source set. */
    fun decode(located: Located): Decoded {
        val images = mutableListOf<ReferenceStripView.LabelledImage>()
        val skipped = mutableListOf<String>()
        for ((reference, label) in located.images.zip(ReferenceImageLocator.labels(located.images))) {
            val image = readImage(reference.file)
            if (image == null) {
                skipped += label
            } else {
                images += ReferenceStripView.LabelledImage(label, image)
            }
        }
        return Decoded(images, skipped)
    }

    /** null when the PNG cannot be read: `ImageIO.read` returns null for a stream no decoder recognises and
     *  throws for an IO failure. Either way that one variant is skipped and reported, never fatal — the other
     *  variants still show (spec's error-handling table). */
    private fun readImage(file: VirtualFile): BufferedImage? =
        try {
            file.inputStream.use { ImageIO.read(it) }
        } catch (e: IOException) {
            thisLogger().warn("Could not read reference image ${file.path}", e)
            null
        }
}
