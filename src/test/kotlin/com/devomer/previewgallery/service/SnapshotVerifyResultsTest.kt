package com.devomer.previewgallery.service

import org.junit.Assert.assertEquals
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

        val results = SnapshotVerifyResults.read(tempFolder.root.toPath(), startedAtMillis = 0L)

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

        val results = SnapshotVerifyResults.read(tempFolder.root.toPath(), startedAtMillis = 0L)

        assertEquals(SnapshotVerifyResults.Status.FAILED, results.single().status)
    }

    @Test
    fun `a file whose lastModified predates startedAtMillis is not read`() {
        val old = resultsFile("TEST-old.xml", passingCaseXml("Old_Snapshot", "phone", "/g/old.png", "/r/old.png"))
        val fresh = resultsFile("TEST-new.xml", passingCaseXml("New_Snapshot", "phone", "/g/new.png", "/r/new.png"))
        val startedAtMillis = 10_000_000L
        old.setLastModified(startedAtMillis - 60_000)
        fresh.setLastModified(startedAtMillis + 60_000)

        val results = SnapshotVerifyResults.read(tempFolder.root.toPath(), startedAtMillis)

        // If the guard were removed, "Old_Snapshot" would show up too — a stale, hand-run `update` presented as
        // this verify's answer.
        assertEquals(listOf("New_Snapshot"), results.map { it.methodName })
    }

    @Test
    fun `a malformed file is skipped while its sibling still parses`() {
        resultsFile("TEST-broken.xml", "not xml at all <<<")
        resultsFile("TEST-ok.xml", passingCaseXml("Ok_Snapshot", "phone", "/g/ok.png", "/r/ok.png"))

        val results = SnapshotVerifyResults.read(tempFolder.root.toPath(), startedAtMillis = 0L)

        assertEquals(listOf("Ok_Snapshot"), results.map { it.methodName })
    }

    @Test
    fun `an absent directory yields an empty list rather than throwing`() {
        val missing = Path.of(tempFolder.root.absolutePath, "does-not-exist")

        assertEquals(emptyList<SnapshotVerifyResults.SnapshotResult>(), SnapshotVerifyResults.read(missing, 0L))
    }
}
