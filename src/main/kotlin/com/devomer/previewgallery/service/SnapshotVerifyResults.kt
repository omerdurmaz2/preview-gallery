package com.devomer.previewgallery.service

import org.w3c.dom.Element
import java.io.File
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger
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
 * next, and nothing under `mcp/` may import platform classes. The build root [read] resolves against is a plain
 * [Path] for that reason — the caller knows where Gradle was invoked from, this object only needs the value.
 *
 * The failing shape was **assumed** when this was written, because only a passing `update` run existed; the manual
 * gate then broke a golden on purpose and read the real failing XML (PG20-9). What that corrected:
 *
 * - There is no `PreviewScreenshot.diffImagePath` property. The whole vocabulary is `diffPercent`, `previewName`,
 *   `methodName`, `refImagePath`, `newImagePath` — the assumed constant matched nothing, so [SnapshotResult.diffPath]
 *   was always null. The diff path is instead one line of the `<failure message>` attribute, `Diff Image: <path>`.
 *   That message also repeats both image paths as `Expected:`/`Actual:` lines, which are deliberately not parsed:
 *   the properties say the same thing without string handling.
 * - A named diff path is not a promise of a file. The size mismatch the gate produced named one and wrote nothing —
 *   the `diffs/` directory was empty afterwards. Only a failure whose images are the same size gets an image.
 * - The `<failure>`-child check was right, and the message's first line turned out to be the most useful sentence
 *   in the file ("Size Mismatch. Reference image size: 840x168. Rendered image size: 371x168"), so it is kept as
 *   [SnapshotResult.failureSummary] rather than thrown away.
 *
 * The two tasks write **different file layouts**, which matters to anyone extending [read]: `update` writes one
 * `TEST-<facade class>.xml` per facade, while `validate` writes a single engine-wide
 * `TEST-preview-screenshot-test-engine.xml` holding every case (100 of them, in the gate's run). Only `update`
 * output existed when this was written, hence the per-facade wording elsewhere. The `TEST-*.xml` filter matches
 * both, and nothing here reads the `testsuite` element, so both layouts parse the same way.
 */
object SnapshotVerifyResults {

    enum class Status { PASSED, FAILED }

    /**
     * [diffPath] is null for a snapshot that passed — there is no difference image to point at — and also for a
     * failure whose message names none. It being non-null still does not mean the file exists; see the class doc.
     * All three paths are absolute, resolved by [read]; see its doc for why the XML's own are not.
     *
     * [failureSummary] is the failure message's first line, the sentence naming what went wrong. [diffPercent] is
     * present whenever the task measured one, which is every passing case and only some failing ones.
     */
    data class SnapshotResult(
        val methodName: String,
        val variant: String,
        val status: Status,
        val goldenPath: String?,
        val renderedPath: String?,
        val diffPath: String?,
        val failureSummary: String? = null,
        val diffPercent: Double? = null,
    )

    private const val PREVIEW_NAME = "PreviewScreenshot.previewName"
    private const val METHOD_NAME = "PreviewScreenshot.methodName"
    private const val REF_IMAGE = "PreviewScreenshot.refImagePath"
    private const val NEW_IMAGE = "PreviewScreenshot.newImagePath"
    private const val DIFF_PERCENT = "PreviewScreenshot.diffPercent"
    private const val DIFF_IMAGE_LINE = "Diff Image:"

    private val logger = Logger.getLogger(SnapshotVerifyResults::class.java.name)

    /**
     * Every snapshot result in [resultsDirectory], ignoring files last modified before [startedAtMillis], with
     * every image path resolved against [buildRoot].
     *
     * The timestamp guard is not defensive tidiness. The same directory can hold results from an `update` the
     * human ran by hand at a terminal, and reading those would present someone else's older run as this verify's
     * answer — stale data shown as fresh, which is the failure this project keeps designing against (spec D7).
     *
     * [buildRoot] is not tidiness either. The task writes `refImagePath` and `newImagePath` **relative to the
     * directory Gradle was invoked from**, e.g. `features/favorites/ui/src/screenshotTestDebug/reference/….png`.
     * Handing those to `File` decodes them against the JVM's working directory, which for the IDE process is
     * nowhere near the project — so every image read failed and the render pane reported both of them missing.
     * An already-absolute path is returned unchanged, because [Path.resolve] does exactly that.
     *
     * Returns an empty list when the directory is absent or holds nothing new enough. The caller distinguishes
     * "nothing to read" from "ran and found nothing" — this object cannot, and must not guess.
     */
    fun read(resultsDirectory: Path, startedAtMillis: Long, buildRoot: Path): List<SnapshotResult> {
        val files = resultsDirectory.toFile()
            .listFiles { file -> file.isFile && file.name.startsWith("TEST-") && file.name.endsWith(".xml") }
            ?: return emptyList()
        return files
            .filter { it.lastModified() >= startedAtMillis }
            .sortedBy { it.name }
            .flatMap { readFile(it, buildRoot) }
    }

    /** A file that will not parse is skipped rather than failing the whole read: one malformed result must not
     *  hide the other nine facade classes' answers. */
    private fun readFile(file: File, buildRoot: Path): List<SnapshotResult> =
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
            (0 until cases.length).mapNotNull { index ->
                (cases.item(index) as? Element)?.let { readCase(it, buildRoot) }
            }
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to parse snapshot verify results file: ${file.name}", e)
            emptyList()
        }

    private fun readCase(case: Element, buildRoot: Path): SnapshotResult? {
        val properties = propertiesOf(case)
        val methodName = properties[METHOD_NAME] ?: return null
        val variant = properties[PREVIEW_NAME] ?: return null
        // Presence of a <failure> child is the mismatch marker, not the testsuite's failures= count: that count
        // covers a whole file, and `validate` writes every snapshot in the module into one.
        val failures = case.getElementsByTagName("failure")
        val failure = (failures.item(0) as? Element)?.getAttribute("message").orEmpty()
        return SnapshotResult(
            methodName = methodName,
            variant = variant,
            status = if (failures.length > 0) Status.FAILED else Status.PASSED,
            goldenPath = imagePath(properties[REF_IMAGE], buildRoot),
            renderedPath = imagePath(properties[NEW_IMAGE], buildRoot),
            diffPath = imagePath(diffLine(failure), buildRoot),
            failureSummary = failure.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            diffPercent = properties[DIFF_PERCENT]?.toDoubleOrNull(),
        )
    }

    private fun diffLine(failureMessage: String): String? =
        failureMessage.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(DIFF_IMAGE_LINE) }
            ?.removePrefix(DIFF_IMAGE_LINE)
            ?.trim()

    /** An empty value stays null rather than resolving to [buildRoot] itself: "no path" must keep reading as a
     *  missing image, not as a directory that will fail to decode for an unrelated reason. */
    private fun imagePath(raw: String?, buildRoot: Path): String? =
        raw?.takeIf { it.isNotEmpty() }?.let { buildRoot.resolve(it).toString() }

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
