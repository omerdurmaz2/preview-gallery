package com.devomer.previewgallery.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.image.BufferedImage

/**
 * [RenderOutcome.Success.dpi] is the density layoutlib rendered at (PG12-2). `LiveRenderer` reads it through two
 * guarded Android Studio calls, so the *default* is the contract that matters here: it must be the density at
 * which the dp conversion is the identity, so a guard failure degrades to raw render pixels — the pre-PG12
 * display — instead of scaling the image by a wrong factor.
 */
class RenderOutcomeTest {

    @Test fun `a Success defaults to the identity density`() {
        val outcome = RenderOutcome.Success(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB))
        assertEquals(160, RenderOutcome.DEFAULT_DPI)
        assertEquals(RenderOutcome.DEFAULT_DPI, outcome.dpi)
    }

    @Test fun `a Success carries the density it was given`() {
        val outcome = RenderOutcome.Success(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), emptyList(), 440)
        assertEquals(440, outcome.dpi)
    }
}
