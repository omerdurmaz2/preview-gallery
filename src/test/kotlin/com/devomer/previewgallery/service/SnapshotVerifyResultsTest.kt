package com.devomer.previewgallery.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path

/**
 * [SnapshotVerifyResults] touches only the JDK, so this needs no IDE project at all — a [TemporaryFolder] with
 * real JUnit XML in it is enough to exercise the parser and the timestamp guard.
 */
class SnapshotVerifyResultsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun resultsFile(name: String, xml: String): File {
        val file = tempFolder.newFile(name)
        file.writeText(xml)
        return file
    }

    private fun passingCaseXml(methodName: String, variant: String, golden: String, rendered: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="com.example.WidgetSnapshotsTest" tests="1">
          <testcase name="$methodName" classname="com.example.WidgetSnapshotsTest">
            <properties>
              <property name="PreviewScreenshot.previewName" value="$variant"/>
              <property name="PreviewScreenshot.methodName" value="$methodName"/>
              <property name="PreviewScreenshot.refImagePath" value="$golden"/>
              <property name="PreviewScreenshot.newImagePath" value="$rendered"/>
            </properties>
          </testcase>
        </testsuite>
    """.trimIndent()

    @Test
    fun `a passing testcase with all four properties yields a PASSED result with method, variant and both paths`() {
        resultsFile(
            "TEST-com.example.WidgetSnapshotsTest.xml",
            passingCaseXml("Widget_Default_Snapshot", "phone", "/golden/widget.png", "/rendered/widget.png"),
        )

        val results = SnapshotVerifyResults.read(tempFolder.root.toPath(), startedAtMillis = 0L, buildRoot = BUILD_ROOT)

        assertEquals(1, results.size)
        val result = results.single()
        assertEquals("Widget_Default_Snapshot", result.methodName)
        assertEquals("phone", result.variant)
        assertEquals(SnapshotVerifyResults.Status.PASSED, result.status)
        assertEquals("/golden/widget.png", result.goldenPath)
        assertEquals("/rendered/widget.png", result.renderedPath)
        assertNull(result.diffPath)
    }

    @Test
    fun `a testcase carrying a failure child yields a FAILED result`() {
        resultsFile(
            "TEST-com.example.WidgetSnapshotsTest.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.WidgetSnapshotsTest" tests="1">
              <testcase name="Widget_Broken_Snapshot" classname="com.example.WidgetSnapshotsTest">
                <properties>
                  <property name="PreviewScreenshot.previewName" value="phone"/>
                  <property name="PreviewScreenshot.methodName" value="Widget_Broken_Snapshot"/>
                  <property name="PreviewScreenshot.refImagePath" value="/golden/widget.png"/>
                  <property name="PreviewScreenshot.newImagePath" value="/rendered/widget.png"/>
                </properties>
                <failure message="images differ">pixel mismatch</failure>
              </testcase>
            </testsuite>
            """.trimIndent(),
        )

        val results = SnapshotVerifyResults.read(tempFolder.root.toPath(), startedAtMillis = 0L, buildRoot = BUILD_ROOT)

        val result = results.single()
        assertEquals(SnapshotVerifyResults.Status.FAILED, result.status)
        assertEquals("images differ", result.failureSummary)
        // No `Diff Image:` line means no diff path — the pane's own status-derived branch is what reports that.
        assertNull(result.diffPath)
    }

    @Test
    fun `the engine-wide file validate really writes parses its passing case, diff percent and all`() {
        resultsFile("TEST-preview-screenshot-test-engine.xml", engineFileXml(REAL_PASSING_CASE))

        val result = SnapshotVerifyResults.read(tempFolder.root.toPath(), 0L, BUILD_ROOT).single()

        assertEquals("LiteProductCard_Selected_Snapshot", result.methodName)
        assertEquals("small", result.variant)
        assertEquals(SnapshotVerifyResults.Status.PASSED, result.status)
        assertEquals(0.0, result.diffPercent ?: Double.NaN, 0.0)
        assertNull(result.failureSummary)
        assertNull(result.diffPath)
        assertEquals(
            "/repo/features/favorites/ui/src/screenshotTestDebug/reference/com/hepsiburada/ui/feature/" +
                "favorites/screen/addproducts/AddProductsSnapshotsKt/LiteProductCard_Selected_Snapshot_small_72f29e0e_0.png",
            result.goldenPath,
        )
    }

    @Test
    fun `the real failing case yields its diff path and its summary from the failure message`() {
        resultsFile("TEST-preview-screenshot-test-engine.xml", engineFileXml(REAL_FAILING_CASE))

        val result = SnapshotVerifyResults.read(tempFolder.root.toPath(), 0L, BUILD_ROOT).single()

        assertEquals("RefreshFavoritesButton_Visible_Snapshot", result.methodName)
        assertEquals("phone", result.variant)
        assertEquals(SnapshotVerifyResults.Status.FAILED, result.status)
        assertEquals(
            "Size Mismatch. Reference image size: 840x168. Rendered image size: 371x168",
            result.failureSummary,
        )
        assertEquals(
            "/repo/features/favorites/ui/build/outputs/screenshotTest-results/preview/debug/diffs/com/" +
                "hepsiburada/ui/feature/favorites/util/UtilSnapshotsKt/" +
                "RefreshFavoritesButton_Visible_Snapshot_phone_eee23ffd_0.png",
            result.diffPath,
        )
        // The message repeats both image paths as Expected:/Actual: lines; the properties stay authoritative.
        assertEquals(
            "/repo/features/favorites/ui/src/screenshotTestDebug/reference/com/hepsiburada/ui/feature/favorites/" +
                "util/UtilSnapshotsKt/RefreshFavoritesButton_Visible_Snapshot_phone_eee23ffd_0.png",
            result.goldenPath,
        )
        // A size mismatch measures no percentage at all, so there is none to report.
        assertNull(result.diffPercent)
    }

    @Test
    fun `a Diff Image line whose file was never written still yields the path`() {
        // The gate's own case: a size mismatch names a diff image and leaves `diffs/` empty. Reporting the path
        // is what lets the pane say the diff could not be read; silently nulling it would read as "no difference".
        resultsFile("TEST-preview-screenshot-test-engine.xml", engineFileXml(REAL_FAILING_CASE))
        val buildRoot = tempFolder.newFolder("repo")
        File(
            buildRoot,
            "features/favorites/ui/build/outputs/screenshotTest-results/preview/debug/diffs/com/hepsiburada/" +
                "ui/feature/favorites/util/UtilSnapshotsKt",
        ).mkdirs()

        val result = SnapshotVerifyResults.read(tempFolder.root.toPath(), 0L, buildRoot.toPath()).single()

        val diffPath = result.diffPath
        assertNotNull(diffPath)
        assertFalse(File(diffPath.orEmpty()).exists())
    }

    @Test
    fun `a file whose lastModified predates startedAtMillis is not read`() {
        val old = resultsFile("TEST-old.xml", passingCaseXml("Old_Snapshot", "phone", "/g/old.png", "/r/old.png"))
        val fresh = resultsFile("TEST-new.xml", passingCaseXml("New_Snapshot", "phone", "/g/new.png", "/r/new.png"))
        val startedAtMillis = 10_000_000L
        old.setLastModified(startedAtMillis - 60_000)
        fresh.setLastModified(startedAtMillis + 60_000)

        val results = SnapshotVerifyResults.read(tempFolder.root.toPath(), startedAtMillis, BUILD_ROOT)

        // If the guard were removed, "Old_Snapshot" would show up too — a stale, hand-run `update` presented as
        // this verify's answer.
        assertEquals(listOf("New_Snapshot"), results.map { it.methodName })
    }

    @Test
    fun `readForRun falls back to the results on disk when a successful build rewrote nothing`() {
        val startedAtMillis = 10_000_000L
        val file = resultsFile(
            "TEST-preview-screenshot-test-engine.xml",
            passingCaseXml("UpToDate_Snapshot", "phone", "/g/uptodate.png", "/r/uptodate.png"),
        )
        file.setLastModified(startedAtMillis - 60_000)

        val results = SnapshotVerifyResults.readForRun(
            tempFolder.root.toPath(),
            startedAtMillis,
            BUILD_ROOT,
            buildSucceeded = true,
        )

        // Plain read() sees nothing newer than startedAtMillis and returns empty; only the fallback to an
        // unfiltered read recovers the up-to-date task's own answer.
        assertEquals(listOf("UpToDate_Snapshot"), results.map { it.methodName })
    }

    @Test
    fun `readForRun still returns nothing when a failed build rewrote nothing`() {
        val startedAtMillis = 10_000_000L
        val file = resultsFile(
            "TEST-preview-screenshot-test-engine.xml",
            passingCaseXml("UpToDate_Snapshot", "phone", "/g/uptodate.png", "/r/uptodate.png"),
        )
        file.setLastModified(startedAtMillis - 60_000)

        val results = SnapshotVerifyResults.readForRun(
            tempFolder.root.toPath(),
            startedAtMillis,
            BUILD_ROOT,
            buildSucceeded = false,
        )

        // An unguarded fallback (one that ignores buildSucceeded) would return "UpToDate_Snapshot" here too — a
        // failed build proves nothing about an unrewritten result, so the guard must still hold.
        assertEquals(emptyList<String>(), results.map { it.methodName })
    }

    @Test
    fun `readForRun returns a rewritten result exactly once, whatever the build outcome`() {
        val startedAtMillis = 10_000_000L
        val file = resultsFile(
            "TEST-preview-screenshot-test-engine.xml",
            passingCaseXml("Fresh_Snapshot", "phone", "/g/fresh.png", "/r/fresh.png"),
        )
        file.setLastModified(startedAtMillis + 60_000)

        val succeeded = SnapshotVerifyResults.readForRun(tempFolder.root.toPath(), startedAtMillis, BUILD_ROOT, buildSucceeded = true)
        val failed = SnapshotVerifyResults.readForRun(tempFolder.root.toPath(), startedAtMillis, BUILD_ROOT, buildSucceeded = false)

        // A readForRun that always appended the unfiltered fallback, instead of only reaching for it when the
        // guarded read came back empty, would return "Fresh_Snapshot" twice here.
        assertEquals(listOf("Fresh_Snapshot"), succeeded.map { it.methodName })
        assertEquals(listOf("Fresh_Snapshot"), failed.map { it.methodName })
    }

    @Test
    fun `readForRun on an absent results directory yields an empty list for either build outcome`() {
        val missing = Path.of(tempFolder.root.absolutePath, "does-not-exist")

        assertEquals(
            emptyList<SnapshotVerifyResults.SnapshotResult>(),
            SnapshotVerifyResults.readForRun(missing, 0L, BUILD_ROOT, buildSucceeded = true),
        )
        assertEquals(
            emptyList<SnapshotVerifyResults.SnapshotResult>(),
            SnapshotVerifyResults.readForRun(missing, 0L, BUILD_ROOT, buildSucceeded = false),
        )
    }

    @Test
    fun `a malformed file is skipped while its sibling still parses`() {
        resultsFile("TEST-broken.xml", "not xml at all <<<")
        resultsFile("TEST-ok.xml", passingCaseXml("Ok_Snapshot", "phone", "/g/ok.png", "/r/ok.png"))

        val results = SnapshotVerifyResults.read(tempFolder.root.toPath(), startedAtMillis = 0L, buildRoot = BUILD_ROOT)

        assertEquals(listOf("Ok_Snapshot"), results.map { it.methodName })
    }

    @Test
    fun `an absent directory yields an empty list rather than throwing`() {
        val missing = Path.of(tempFolder.root.absolutePath, "does-not-exist")

        assertEquals(
            emptyList<SnapshotVerifyResults.SnapshotResult>(),
            SnapshotVerifyResults.read(missing, 0L, BUILD_ROOT),
        )
    }

    @Test
    fun `the relative paths the task really writes are resolved against the build root`() {
        // Verbatim shape from a real result file in the reference project: relative to the directory Gradle was
        // invoked from, which is neither the results directory nor the IDE process's working directory.
        resultsFile(
            "TEST-com.example.WidgetSnapshotsTest.xml",
            passingCaseXml(
                "Widget_Default_Snapshot",
                "phone",
                "features/favorites/ui/src/screenshotTestDebug/reference/com/example/Widget_phone_0.png",
                "features/favorites/ui/build/outputs/screenshotTest-results/preview/debug/rendered/com/example/Widget_phone_0.png",
            ),
        )

        val result = SnapshotVerifyResults.read(tempFolder.root.toPath(), 0L, BUILD_ROOT).single()

        assertEquals(
            "/repo/features/favorites/ui/src/screenshotTestDebug/reference/com/example/Widget_phone_0.png",
            result.goldenPath,
        )
        assertEquals(
            "/repo/features/favorites/ui/build/outputs/screenshotTest-results/preview/debug/rendered/com/example/Widget_phone_0.png",
            result.renderedPath,
        )
    }

    @Test
    fun `an already absolute path is left exactly as the task wrote it`() {
        resultsFile(
            "TEST-com.example.WidgetSnapshotsTest.xml",
            passingCaseXml("Widget_Default_Snapshot", "phone", "/elsewhere/golden.png", "/elsewhere/rendered.png"),
        )

        val result = SnapshotVerifyResults.read(tempFolder.root.toPath(), 0L, BUILD_ROOT).single()

        assertEquals("/elsewhere/golden.png", result.goldenPath)
        assertEquals("/elsewhere/rendered.png", result.renderedPath)
    }

    @Test
    fun `an empty path attribute stays null rather than resolving to the build root itself`() {
        resultsFile(
            "TEST-com.example.WidgetSnapshotsTest.xml",
            passingCaseXml("Widget_Default_Snapshot", "phone", "", ""),
        )

        val result = SnapshotVerifyResults.read(tempFolder.root.toPath(), 0L, BUILD_ROOT).single()

        assertNull(result.goldenPath)
        assertNull(result.renderedPath)
    }

    /** The engine-wide file `validate` writes: one `testsuite` holding every case in the module, where `update`
     *  writes one file per facade class. Element and attributes copied from the gate's real run. Concatenated
     *  rather than interpolated into a `trimIndent`ed template, which would leave the XML declaration indented. */
    private fun engineFileXml(cases: String) = ENGINE_FILE_HEADER + cases + "</testsuite>\n"

    private companion object {
        /** Stands in for [com.devomer.previewgallery.render.SnapshotVerifyRunner.Started.buildRoot]: the
         *  directory Gradle was invoked from, deliberately unrelated to the results directory these tests read. */
        val BUILD_ROOT: Path = Path.of("/repo")

        const val ENGINE_FILE_HEADER = """<?xml version='1.0' encoding='UTF-8'?>
<testsuite name="Preview Screenshot Test Engine" tests="100" skipped="0" failures="1" errors="0" time="41.387" hostname="hb-dev-148" timestamp="2026-08-11T10:10:02">
<properties>
<property name="deviceId" value="Preview"/>
<property name="deviceDisplayName" value="Preview"/>
</properties>
"""

        /** Copied verbatim out of the gate's `TEST-preview-screenshot-test-engine.xml`, `system-out` and all. */
        const val REAL_PASSING_CASE = """
<testcase name="LiteProductCard_Selected_Snapshot_small_{widthDp=320}" classname="com.hepsiburada.ui.feature.favorites.screen.addproducts.AddProductsSnapshotsKt" time="0.047">
<properties>
<property name="PreviewScreenshot.diffPercent" value="0.0"/>
<property name="PreviewScreenshot.previewName" value="small"/>
<property name="PreviewScreenshot.methodName" value="LiteProductCard_Selected_Snapshot"/>
<property name="PreviewScreenshot.refImagePath" value="features/favorites/ui/src/screenshotTestDebug/reference/com/hepsiburada/ui/feature/favorites/screen/addproducts/AddProductsSnapshotsKt/LiteProductCard_Selected_Snapshot_small_72f29e0e_0.png"/>
<property name="PreviewScreenshot.newImagePath" value="features/favorites/ui/build/outputs/screenshotTest-results/preview/debug/rendered/com/hepsiburada/ui/feature/favorites/screen/addproducts/AddProductsSnapshotsKt/LiteProductCard_Selected_Snapshot_small_72f29e0e_0.png"/>
</properties>

<system-out><![CDATA[
unique-id: [engine:preview-screenshot-test-engine]/[class:com.hepsiburada.ui.feature.favorites.screen.addproducts.AddProductsSnapshotsKt]/[method:LiteProductCard_Selected_Snapshot]
display-name: LiteProductCard_Selected_Snapshot_small_{widthDp=320}
]]></system-out>
</testcase>
"""

        /** The one failure the gate produced, verbatim: no `diffPercent`, and the diff path only in the message. */
        const val REAL_FAILING_CASE = """
<testcase name="RefreshFavoritesButton_Visible_Snapshot_phone" classname="com.hepsiburada.ui.feature.favorites.util.UtilSnapshotsKt" time="0.006">
<properties>
<property name="PreviewScreenshot.previewName" value="phone"/>
<property name="PreviewScreenshot.methodName" value="RefreshFavoritesButton_Visible_Snapshot"/>
<property name="PreviewScreenshot.refImagePath" value="features/favorites/ui/src/screenshotTestDebug/reference/com/hepsiburada/ui/feature/favorites/util/UtilSnapshotsKt/RefreshFavoritesButton_Visible_Snapshot_phone_eee23ffd_0.png"/>
<property name="PreviewScreenshot.newImagePath" value="features/favorites/ui/build/outputs/screenshotTest-results/preview/debug/rendered/com/hepsiburada/ui/feature/favorites/util/UtilSnapshotsKt/RefreshFavoritesButton_Visible_Snapshot_phone_eee23ffd_0.png"/>
</properties>

<failure message="Size Mismatch. Reference image size: 840x168. Rendered image size: 371x168&#xa;Expected: features/favorites/ui/src/screenshotTestDebug/reference/com/hepsiburada/ui/feature/favorites/util/UtilSnapshotsKt/RefreshFavoritesButton_Visible_Snapshot_phone_eee23ffd_0.png&#xa;Actual: features/favorites/ui/build/outputs/screenshotTest-results/preview/debug/rendered/com/hepsiburada/ui/feature/favorites/util/UtilSnapshotsKt/RefreshFavoritesButton_Visible_Snapshot_phone_eee23ffd_0.png&#xa;Diff Image: features/favorites/ui/build/outputs/screenshotTest-results/preview/debug/diffs/com/hepsiburada/ui/feature/favorites/util/UtilSnapshotsKt/RefreshFavoritesButton_Visible_Snapshot_phone_eee23ffd_0.png&#xa;" type="com.android.tools.screenshot.differ.ImageComparisonAssertionError"><![CDATA[com.android.tools.screenshot.differ.ImageComparisonAssertionError: Size Mismatch. Reference image size: 840x168. Rendered image size: 371x168
Expected: features/favorites/ui/src/screenshotTestDebug/reference/com/hepsiburada/ui/feature/favorites/util/UtilSnapshotsKt/RefreshFavoritesButton_Visible_Snapshot_phone_eee23ffd_0.png
Actual: features/favorites/ui/build/outputs/screenshotTest-results/preview/debug/rendered/com/hepsiburada/ui/feature/favorites/util/UtilSnapshotsKt/RefreshFavoritesButton_Visible_Snapshot_phone_eee23ffd_0.png
Diff Image: features/favorites/ui/build/outputs/screenshotTest-results/preview/debug/diffs/com/hepsiburada/ui/feature/favorites/util/UtilSnapshotsKt/RefreshFavoritesButton_Visible_Snapshot_phone_eee23ffd_0.png

]]></failure>
</testcase>
"""
    }
}
