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

    @Test
    fun `an ordinary render that never asked for the calibration pin is not requested regardless of apply result`() {
        assertEquals(
            RenderModelResolver.DevicePinResolution.NotRequested,
            RenderModelResolver.decideDevicePin(pinRequested = false, applied = false),
        )
        assertEquals(
            RenderModelResolver.DevicePinResolution.NotRequested,
            RenderModelResolver.decideDevicePin(pinRequested = false, applied = true),
        )
    }

    @Test
    fun `a requested pin that was applied is Applied`() {
        val decision = RenderModelResolver.decideDevicePin(pinRequested = true, applied = true)

        assertEquals(RenderModelResolver.DevicePinResolution.Applied, decision)
    }

    @Test
    fun `a requested pin that could not be applied is Failed`() {
        val decision = RenderModelResolver.decideDevicePin(pinRequested = true, applied = false)

        assertEquals(RenderModelResolver.DevicePinResolution.Failed, decision)
    }
}
