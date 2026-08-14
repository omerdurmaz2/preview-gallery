package com.devomer.previewgallery.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RenderModelResolver.decideVariantResolution] is the pure form of the branch D3a added — see its own KDoc.
 * Everything else in [RenderModelResolver] needs Android Studio to run at all; this one decision does not, so it
 * is tested directly here, the same split [ImageDiffTest] and [ScreenshotTestClassesTest] already draw for their
 * own pure logic in this package.
 */
class RenderModelResolverTest {

    @Test
    fun `no required variant proceeds unassumed regardless of what the finder returned`() {
        val decision = RenderModelResolver.decideVariantResolution(
            requiredVariant = null,
            elementFound = false,
            finderReturnedNothing = false,
        )

        assertEquals(RenderModelResolver.VariantResolution.Proceed(variantAssumed = false), decision)
    }

    @Test
    fun `a required variant the finder matched proceeds unassumed`() {
        val decision = RenderModelResolver.decideVariantResolution(
            requiredVariant = "phone",
            elementFound = true,
            finderReturnedNothing = false,
        )

        assertEquals(RenderModelResolver.VariantResolution.Proceed(variantAssumed = false), decision)
    }

    @Test
    fun `a required variant the finder could not name at all proceeds assumed`() {
        val decision = RenderModelResolver.decideVariantResolution(
            requiredVariant = "phone",
            elementFound = false,
            finderReturnedNothing = true,
        )

        assertEquals(RenderModelResolver.VariantResolution.Proceed(variantAssumed = true), decision)
    }

    @Test
    fun `a required variant the finder disagreed about by name stays unresolved`() {
        val decision = RenderModelResolver.decideVariantResolution(
            requiredVariant = "phone",
            elementFound = false,
            finderReturnedNothing = false,
        )

        assertEquals(RenderModelResolver.VariantResolution.Unresolved, decision)
    }
}
