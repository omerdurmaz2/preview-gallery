package com.devomer.previewgallery.mcp

import com.devomer.previewgallery.mcp.tools.SnapshotHealthTool
import com.devomer.previewgallery.service.GoldenInspector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotHealthToolTest {

    private val misnamed = PreviewFacts(
        composableFqn = "com.example.DialogKt.DeleteSelectedProductsDialog_Preview",
        displayName = "DeleteSelectedProductsDialog_Preview",
        functionName = "DeleteSelectedProductsDialog_Preview",
        moduleName = "app.main",
        packageName = "com.example",
        file = "/src/Dialog.kt",
        line = 58,
        isPrivate = false,
        hasPreviewParameter = false,
        unsupportedReason = null,
        covered = true,
        targets = listOf("PrimusDialog"),
    )

    private val honest = SnapshotFacts(
        snapshotFqn = "com.example.DialogSnapshotsKt.DeleteSelectedProductsDialog_Direct_Snapshot",
        moduleName = "app.main",
        file = "/src/DialogSnapshots.kt",
        line = 12,
        targets = listOf("DeleteSelectedProductsDialog"),
        orphan = true,
    )

    private val blankGolden = SnapshotFacts(
        snapshotFqn = "com.example.SheetSnapshotsKt.Sheet_Collapsed_Snapshot",
        moduleName = "app.main",
        file = "/src/SheetSnapshots.kt",
        line = 30,
        targets = listOf("Sheet"),
    )

    private val goldens = listOf(
        GoldenInspector.BlankFinding(
            composableFqn = "com.example.SheetSnapshotsKt.Sheet_Collapsed_Snapshot",
            moduleName = "app.main",
            variant = "phone",
            path = "/src/reference/Sheet_Collapsed_0.png",
        ),
    )

    private val project = ProjectSnapshot(
        name = "demo",
        path = "/src",
        indexing = false,
        previews = listOf(misnamed),
        snapshots = listOf(honest, blankGolden),
    )

    @Test
    fun `a name finding carries both sides`() {
        val json = SnapshotHealthTool.execute(project, module = null, blankGoldens = goldens)

        assertTrue(json, json.contains("\"namedAfter\":\"DeleteSelectedProductsDialog\""))
        assertTrue(json, json.contains("PrimusDialog"))
    }

    @Test
    fun `a blank golden carries the path to look at`() {
        val json = SnapshotHealthTool.execute(project, module = null, blankGoldens = goldens)

        assertTrue(json, json.contains("/src/reference/Sheet_Collapsed_0.png"))
    }

    @Test
    fun `the module filter applies to both halves`() {
        val json = SnapshotHealthTool.execute(project, module = "other.main", blankGoldens = goldens)

        assertTrue(json, json.contains("\"blankGoldens\":[]"))
        assertTrue(json, json.contains("\"namedAfterSomethingElse\":[]"))
    }

    @Test
    fun `the skipped count reaches the agent`() {
        val json = SnapshotHealthTool.execute(project, module = null, blankGoldens = goldens)

        assertFalse(json, json.contains("\"skippedRows\":null"))
        assertTrue(json, json.contains("\"skippedRows\":"))
    }
}
