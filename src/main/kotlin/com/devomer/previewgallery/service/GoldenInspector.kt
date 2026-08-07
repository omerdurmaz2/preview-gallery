package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.ReferenceImage
import com.devomer.previewgallery.render.RenderedImageInspector
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import javax.imageio.ImageIO

/**
 * Reads committed reference PNGs and reports the ones that cannot be showing anything.
 *
 * The blank rule itself is [RenderedImageInspector.isBlank], unchanged and uncopied: it already encodes
 * "degenerate in size, or every pixel identical", and a second copy for reference images would drift from the
 * one the render path enforces (spec D2).
 *
 * Decoding must not happen on the EDT or inside a read action — `RenderPipeline` calls holding the read lock
 * across long work a prime freeze suspect, and this decodes two device-resolution PNGs per snapshot. The
 * caller is responsible for that; this object only does the work it is handed.
 */
object GoldenInspector {

    /** One reference image to check, carrying enough of its owner to name it in a report. */
    data class Candidate(val composableFqn: String, val moduleName: String, val image: ReferenceImage)

    data class BlankFinding(
        val composableFqn: String,
        val moduleName: String,
        val variant: String,
        val path: String,
    )

    /** [unreadable] counts images that could not be decoded: reported, never fatal (spec D7). */
    data class Result(val findings: List<BlankFinding>, val unreadable: Int)

    fun inspect(images: List<Candidate>): Result {
        val findings = mutableListOf<BlankFinding>()
        var unreadable = 0

        images.forEach { candidate ->
            val decoded = read(candidate)
            if (decoded == null) {
                unreadable++
                return@forEach
            }
            if (RenderedImageInspector.isBlank(decoded)) {
                findings += BlankFinding(
                    composableFqn = candidate.composableFqn,
                    moduleName = candidate.moduleName,
                    variant = candidate.image.variant,
                    path = candidate.image.file.path,
                )
            }
        }
        return Result(findings.sortedBy { it.path }, unreadable)
    }

    private fun read(candidate: Candidate) =
        try {
            candidate.image.file.inputStream.use { ImageIO.read(it) }
        } catch (e: ProcessCanceledException) {
            throw e // Never swallow cancellation — the platform relies on it propagating.
        } catch (e: Exception) {
            thisLogger().warn("Could not read reference image ${candidate.image.file.path}", e)
            null
        }
}
