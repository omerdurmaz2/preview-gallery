package com.devomer.previewgallery.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [RenderPipeline.classify], the pure part of the pipeline's state machine — it takes booleans rather
 * than a `PreviewEntry`/`Module` specifically so it needs no fixture at all (see its own doc comment). The rest
 * of [RenderPipeline] drives [LiveRenderer] / [BuildService] asynchronously across a debounce, a background
 * executor and the EDT; both are concrete AS-integration classes with no seam to substitute a test double
 * (no mocking framework is set up in this project), so that half is verified manually via `runIde` instead —
 * see the PG3-6 report for what was checked and what still needs a running IDE.
 */
class RenderPipelineTest {

    @Test
    fun `unsupported wins over everything else`() {
        assertEquals(
            RenderState.UNSUPPORTED,
            RenderPipeline.classify(unsupported = true, hasPreviewParameter = true, isFresh = true),
        )
    }

    @Test
    fun `a preview parameter is unsupported even when fresh`() {
        assertEquals(
            RenderState.UNSUPPORTED,
            RenderPipeline.classify(unsupported = false, hasPreviewParameter = true, isFresh = true),
        )
    }

    @Test
    fun `a fresh supported module renders immediately`() {
        assertEquals(
            RenderState.RENDERING,
            RenderPipeline.classify(unsupported = false, hasPreviewParameter = false, isFresh = true),
        )
    }

    @Test
    fun `a stale supported module needs a build first`() {
        assertEquals(
            RenderState.NEEDS_BUILD,
            RenderPipeline.classify(unsupported = false, hasPreviewParameter = false, isFresh = false),
        )
    }
}
