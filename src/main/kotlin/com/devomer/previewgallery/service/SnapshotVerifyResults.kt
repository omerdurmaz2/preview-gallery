package com.devomer.previewgallery.service

import org.w3c.dom.Element
import java.io.File
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads what `validate<Variant>ScreenshotTest` wrote about one module's snapshots.
 *
 * The task's JUnit XML already carries everything this feature needs — the function name, the variant, and the
 * paths of both images — so nothing here parses a file name or recomputes a configuration hash. That is what
 * keeps `ReferenceImageLocator`'s "a prefix is not an identity" limitation out of this path entirely (spec D2's
 * reasoning, one level down).
 *
 * Deliberately free of `com.intellij` imports (spec D10): these results are the natural thing to serve over MCP
 * next, and nothing under `mcp/` may import platform classes.
 */
object SnapshotVerifyResults {

    enum class Status { PASSED, FAILED }

    /** [diffPath] is null for a snapshot that passed — there is no difference image to point at. */
    data class SnapshotResult(
        val methodName: String,
        val variant: String,
        val status: Status,
        val goldenPath: String?,
        val renderedPath: String?,
        val diffPath: String?,
    )

    private const val PREVIEW_NAME = "PreviewScreenshot.previewName"
    private const val METHOD_NAME = "PreviewScreenshot.methodName"
    private const val REF_IMAGE = "PreviewScreenshot.refImagePath"
    private const val NEW_IMAGE = "PreviewScreenshot.newImagePath"
    private const val DIFF_IMAGE = "PreviewScreenshot.diffImagePath"

    /**
     * Every snapshot result in [resultsDirectory], ignoring files last modified before [startedAtMillis].
     *
     * The timestamp guard is not defensive tidiness. The same directory can hold results from an `update` the
     * human ran by hand at a terminal, and reading those would present someone else's older run as this verify's
     * answer — stale data shown as fresh, which is the failure this project keeps designing against (spec D7).
     *
     * Returns an empty list when the directory is absent or holds nothing new enough. The caller distinguishes
     * "nothing to read" from "ran and found nothing" — this object cannot, and must not guess.
     */
    fun read(resultsDirectory: Path, startedAtMillis: Long): List<SnapshotResult> {
        val files = resultsDirectory.toFile()
            .listFiles { file -> file.isFile && file.name.startsWith("TEST-") && file.name.endsWith(".xml") }
            ?: return emptyList()
        return files
            .filter { it.lastModified() >= startedAtMillis }
            .sortedBy { it.name }
            .flatMap { readFile(it) }
    }

    /** A file that will not parse is skipped rather than failing the whole read: one malformed result must not
     *  hide the other nine facade classes' answers. */
    private fun readFile(file: File): List<SnapshotResult> =
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().parse(file)
            val cases = document.getElementsByTagName("testcase")
            (0 until cases.length).mapNotNull { index -> (cases.item(index) as? Element)?.let(::readCase) }
        } catch (e: Exception) {
            emptyList()
        }

    private fun readCase(case: Element): SnapshotResult? {
        val properties = propertiesOf(case)
        val methodName = properties[METHOD_NAME] ?: return null
        val variant = properties[PREVIEW_NAME] ?: return null
        // A <failure> child is what marks a mismatch; its absence is a pass. Checked by presence rather than by
        // the testsuite's failures= count, because that count is per facade class and this is per snapshot.
        val failed = case.getElementsByTagName("failure").length > 0
        return SnapshotResult(
            methodName = methodName,
            variant = variant,
            status = if (failed) Status.FAILED else Status.PASSED,
            goldenPath = properties[REF_IMAGE],
            renderedPath = properties[NEW_IMAGE],
            diffPath = properties[DIFF_IMAGE],
        )
    }

    private fun propertiesOf(case: Element): Map<String, String> {
        val nodes = case.getElementsByTagName("property")
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it) as? Element }
            .mapNotNull { element ->
                val name = element.getAttribute("name").ifEmpty { return@mapNotNull null }
                val value = element.getAttribute("value")
                name to value
            }
            .toMap()
    }
}
