package com.devomer.previewgallery.render

import org.junit.Assert.assertTrue
import org.junit.Test

class RenderApiProbeTest {

    @Test
    fun `every API the probe names exists on the IDE the plugin compiles against`() {
        assertTrue("render", RenderApiProbe.isAvailable())
        assertTrue("picker", RenderApiProbe.isPickerAvailable())
        assertTrue("config-aware", RenderApiProbe.isConfigAwareAvailable())
        assertTrue("view tree", RenderApiProbe.isViewTreeAvailable())
        assertTrue("view override", RenderApiProbe.isViewOverrideAvailable())
        assertTrue("android module walk", RenderApiProbe.isAndroidModuleWalkAvailable())
        assertTrue("compile task finder", RenderApiProbe.isCompileTaskFinderAvailable())
    }
}
