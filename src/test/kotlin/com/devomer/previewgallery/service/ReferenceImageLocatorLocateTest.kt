package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.search.testRow
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [ReferenceImageLocator.locate] against a real directory tree — the half of the locator the pure
 * [ReferenceImageLocatorTest] cannot reach: the content-root walk, the rejection of files that are not this
 * function's, and the variant sort.
 *
 * The file contents are irrelevant here; nothing decodes them (the panel does, off this path), so an empty file
 * with the right name is a faithful fixture for what this function actually reads: names.
 */
class ReferenceImageLocatorLocateTest : BasePlatformTestCase() {

    private val directory = "src/screenshotTestDebug/reference/com/example/WidgetSnapshotsKt"

    private fun snapshot(functionName: String = "Widget_Default_Snapshot"): PreviewEntry {
        val file = myFixture.addFileToProject(
            "src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
            "package com.example\n",
        ).virtualFile
        val indexed = testRow(
            displayName = functionName,
            functionName = functionName,
            packageName = "com.example",
            isSnapshotTest = true,
        ).indexed.copy(jvmClassName = "com.example.WidgetSnapshotsKt")
        return PreviewEntry(indexed, hostModule.name, file)
    }

    /** Resolved from a fixture file the way the panel resolves a snapshot's own module, and memoised: adding the
     *  anchor file twice in one test would fail on the duplicate path. */
    private val hostModule: Module by lazy {
        val file = myFixture.addFileToProject("src/main/kotlin/com/example/Anchor.kt", "package com.example\n")
        val resolved = ProjectFileIndex.getInstance(project).getModuleForFile(file.virtualFile)
        requireNotNull(resolved) { "The fixture file must belong to a module for locate() to have a content root" }
    }

    private fun reference(name: String) {
        myFixture.tempDirFixture.createFile("$directory/$name", "")
    }

    fun `test every variant of the function is found and sorted by variant`() {
        reference("Widget_Default_Snapshot_phone_eee23ffd_0.png")
        reference("Widget_Default_Snapshot_small_72f29e0e_0.png")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, hostModule)

        assertEquals(listOf("phone", "small"), located.map { it.variant })
        assertEquals(
            listOf("Widget_Default_Snapshot_phone_eee23ffd_0.png", "Widget_Default_Snapshot_small_72f29e0e_0.png"),
            located.map { it.file.name },
        )
    }

    fun `test variants sort case-insensitively`() {
        reference("Widget_Default_Snapshot_Zebra_eee23ffd_0.png")
        reference("Widget_Default_Snapshot_apple_72f29e0e_0.png")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, hostModule)

        // A case-sensitive sort would put "Zebra" first; the plugin sorts case-insensitively everywhere.
        assertEquals(listOf("apple", "Zebra"), located.map { it.variant })
    }

    fun `test files belonging to another function or shape are rejected`() {
        reference("Widget_Default_Snapshot_phone_eee23ffd_0.png")
        reference("OtherWidget_Default_Snapshot_phone_eee23ffd_0.png")
        reference("Widget_Default_Snapshot_phone.png")
        reference("README.txt")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, hostModule)

        assertEquals(listOf("phone"), located.map { it.variant })
    }

    fun `test a missing reference directory yields nothing rather than failing`() {
        val entry = snapshot()

        // Spec D10: nothing generated yet is a normal state for a written snapshot, not an error.
        assertEquals(emptyList<String>(), ReferenceImageLocator.locate(entry, hostModule).map { it.variant })
    }

    fun `test a reference directory holding no matching file yields nothing`() {
        reference("SomethingElse_phone_eee23ffd_0.png")
        val entry = snapshot()

        assertEquals(emptyList<String>(), ReferenceImageLocator.locate(entry, hostModule).map { it.variant })
    }
}
