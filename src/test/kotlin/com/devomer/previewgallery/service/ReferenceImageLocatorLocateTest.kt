package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.search.testRow
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [ReferenceImageLocator.locate] against a real directory tree — the half of the locator the pure
 * [ReferenceImageLocatorTest] cannot reach: the directory walk, the rejection of files that are not this
 * function's, the merge across roots and the sort.
 *
 * The roots are resolved the way the panel resolves them, from the snapshot's own module directory, so this also
 * covers the wiring between [ReferenceRoots] and the locator.
 *
 * The file contents are irrelevant here; nothing decodes them (the panel does, off this path), so an empty file
 * with the right name is a faithful fixture for what this function actually reads: names.
 */
class ReferenceImageLocatorLocateTest : BasePlatformTestCase() {

    private val facade = "com/example/WidgetSnapshotsKt"

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
        return PreviewEntry(indexed, "app.main", file)
    }

    private fun reference(sourceSet: String, name: String) {
        myFixture.tempDirFixture.createFile("src/$sourceSet/reference/$facade/$name", "")
    }

    /** Exactly what `ReferenceStripLoader.locateUnderRoots` passes: no `Module`, no `ProjectFileIndex`. */
    private fun roots(entry: PreviewEntry): List<ReferenceRoots.Root> {
        val moduleDirectory = requireNotNull(SnapshotSourceScanner.moduleDirectory(entry.file)) {
            "A snapshot under src/screenshotTest must resolve to the module directory holding its references"
        }
        return ReferenceRoots.of(moduleDirectory)
    }

    fun `test every variant of the function is found and sorted by variant`() {
        reference("screenshotTestDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        reference("screenshotTestDebug", "Widget_Default_Snapshot_small_72f29e0e_0.png")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, roots(entry))

        assertEquals(listOf("phone", "small"), located.map { it.variant })
        assertEquals(
            listOf("Widget_Default_Snapshot_phone_eee23ffd_0.png", "Widget_Default_Snapshot_small_72f29e0e_0.png"),
            located.map { it.file.name },
        )
    }

    fun `test variants sort case-insensitively`() {
        reference("screenshotTestDebug", "Widget_Default_Snapshot_Zebra_eee23ffd_0.png")
        reference("screenshotTestDebug", "Widget_Default_Snapshot_apple_72f29e0e_0.png")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, roots(entry))

        assertEquals(listOf("apple", "Zebra"), located.map { it.variant })
    }

    fun `test files belonging to another function or shape are rejected`() {
        reference("screenshotTestDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        reference("screenshotTestDebug", "OtherWidget_Default_Snapshot_phone_eee23ffd_0.png")
        reference("screenshotTestDebug", "Widget_Default_Snapshot_phone.png")
        reference("screenshotTestDebug", "README.txt")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, roots(entry))

        assertEquals(listOf("phone"), located.map { it.variant })
    }

    fun `test a missing reference directory yields nothing rather than failing`() {
        val entry = snapshot()

        assertEquals(emptyList<String>(), ReferenceImageLocator.locate(entry, roots(entry)).map { it.variant })
    }

    fun `test a reference directory holding no matching file yields nothing`() {
        reference("screenshotTestDebug", "SomethingElse_phone_eee23ffd_0.png")
        val entry = snapshot()

        assertEquals(emptyList<String>(), ReferenceImageLocator.locate(entry, roots(entry)).map { it.variant })
    }

    fun `test two flavours are merged and grouped by source set`() {
        reference("screenshotTestHuaweiDebug", "Widget_Default_Snapshot_small_72f29e0e_0.png")
        reference("screenshotTestHuaweiDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        reference("screenshotTestGoogleDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, roots(entry))

        assertEquals(listOf("googleDebug", "huaweiDebug", "huaweiDebug"), located.map { it.sourceSet })
        assertEquals(listOf("phone", "phone", "small"), located.map { it.variant })
    }

    fun `test a golden committed for one flavour only is still found`() {
        reference("screenshotTestHuaweiDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, roots(entry))

        assertEquals(listOf("huaweiDebug"), located.map { it.sourceSet })
    }

    fun `test a single root labels its images with the variant alone`() {
        reference("screenshotTestDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        reference("screenshotTestDebug", "Widget_Default_Snapshot_small_72f29e0e_0.png")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, roots(entry))

        assertEquals(listOf("phone", "small"), ReferenceImageLocator.labels(located))
    }

    fun `test two roots label their images with the source set`() {
        reference("screenshotTestGoogleDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        reference("screenshotTestHuaweiDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, roots(entry))

        // Two identical-looking images with no way to tell which flavour each belongs to would be worse than
        // one, which is why the prefix appears exactly here and not on the single-root strip above.
        assertEquals(
            listOf("googleDebug · phone", "huaweiDebug · phone"),
            ReferenceImageLocator.labels(located),
        )
    }
}
