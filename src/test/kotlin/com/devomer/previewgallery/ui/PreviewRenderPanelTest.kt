package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.RenderOutcome
import com.devomer.previewgallery.render.RenderResultView
import com.devomer.previewgallery.render.RenderState
import com.devomer.previewgallery.search.testRow
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage

/**
 * What the actions bar offers in each state — the capability gates, asserted without clicking anything.
 *
 * The rule this file exists to hold: a snapshot is never rendered (spec D8), so no control that ends in a render
 * may appear while one is showing — in **either** of the two snapshot states, not just the one that has a strip.
 */
class PreviewRenderPanelTest : BasePlatformTestCase() {

    private fun panel(): PreviewRenderPanel = PreviewRenderPanel(project).apply {
        // Both capabilities available, so nothing else can be the reason a control is missing.
        propertiesAvailable = true
        deviceOverrideAvailable = true
    }

    private fun entry(name: String, isSnapshotTest: Boolean): PreviewEntry {
        val file = myFixture.addFileToProject("$name.kt", "package com.example\n").virtualFile
        return PreviewEntry(
            indexed = testRow(displayName = name, functionName = name, isSnapshotTest = isSnapshotTest).indexed,
            moduleName = "app",
            file = file,
        )
    }

    private fun image() = BufferedImage(10, 20, BufferedImage.TYPE_INT_ARGB)

    fun `test a snapshot with no reference images offers no actions at all`() {
        val panel = panel()

        panel.showReference(entry("Widget_Default_Snapshot", isSnapshotTest = true), emptyList(), emptyList())

        assertEquals(RenderState.NO_REFERENCE, panel.activeState)
        // Properties used to survive here — the gate tested the strip, which this state does not have — leaving
        // the toolbar holding one control, bound to a snapshot, that opens Android Studio's `@Preview` picker.
        assertEquals(emptyList<String>(), panel.actionTitlesForTest())
    }

    fun `test a snapshot with reference images offers zoom and export but not Properties`() {
        val panel = panel()

        panel.showReference(
            entry("Widget_Default_Snapshot", isSnapshotTest = true),
            listOf(ReferenceStripView.LabelledImage("phone", image())),
            emptyList(),
        )

        assertEquals(RenderState.REFERENCE, panel.activeState)
        val titles = panel.actionTitlesForTest()
        assertTrue(titles.toString(), titles.contains("Fit"))
        assertTrue(titles.toString(), titles.contains("Copy image"))
        assertFalse(titles.toString(), titles.contains("Properties"))
    }

    fun `test a live render still offers Properties`() {
        val panel = panel()
        val entry = entry("WidgetPreview", isSnapshotTest = false)

        panel.show(RenderResultView(RenderState.LIVE, RenderOutcome.Success(image()), entry.moduleName), entry)

        // The snapshot gate must not have cost the render path its own controls.
        val titles = panel.actionTitlesForTest()
        assertTrue(titles.toString(), titles.contains("Properties"))
        assertTrue(titles.toString(), titles.contains("Fit"))
    }

    fun `test leaving a snapshot for a render restores Properties`() {
        val panel = panel()
        val entry = entry("WidgetPreview", isSnapshotTest = false)

        panel.showReference(entry("Widget_Default_Snapshot", isSnapshotTest = true), emptyList(), emptyList())
        panel.show(RenderResultView(RenderState.LIVE, RenderOutcome.Success(image()), entry.moduleName), entry)

        assertTrue(panel.actionTitlesForTest().toString(), panel.actionTitlesForTest().contains("Properties"))
    }
}
