package com.devomer.previewgallery.mcp.tools

import com.devomer.previewgallery.mcp.PreviewFacts
import com.devomer.previewgallery.mcp.ProjectSnapshot
import com.devomer.previewgallery.mcp.SnapshotFacts
import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.IndexedPreview
import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.model.SnapshotCoverage
import com.devomer.previewgallery.service.GoldenInspector
import com.devomer.previewgallery.service.SnapshotHealth
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The snapshot tests that pass without testing anything.
 *
 * An agent about to write snapshots for a module should be able to see that the module's existing ones are
 * suspect — otherwise it copies the pattern it finds. The blank-golden half is decided when the snapshot is
 * taken, so this tool reports it rather than reading any file.
 */
object SnapshotHealthTool {

    const val NAME = "snapshot_health"

    const val DESCRIPTION =
        "Reports snapshot tests that pass without testing anything: committed reference PNGs that are blank " +
            "or a single colour, and rows whose name claims a composable their body never calls. Filter with " +
            "`module` (exact match). Call before writing new snapshots in a module, so an existing mistake " +
            "does not get copied."

    fun execute(
        snapshot: ProjectSnapshot,
        module: String?,
        blankGoldens: List<GoldenInspector.BlankFinding>,
    ): String {
        val previews = snapshot.previews.filter { module == null || it.moduleName == module }
        val snapshots = snapshot.snapshots.filter { module == null || it.moduleName == module }
        val names = SnapshotHealth.check(previews.map(::asRow) + snapshots.map(::asSnapshotRow))
        val blanks = blankGoldens.filter { module == null || it.moduleName == module }
        return buildJsonObject {
            put(
                "blankGoldens",
                buildJsonArray {
                    blanks.forEach { finding ->
                        add(
                            buildJsonObject {
                                put("snapshotFqn", finding.composableFqn)
                                put("module", finding.moduleName)
                                put("variant", finding.variant)
                                put("path", finding.path)
                            },
                        )
                    }
                },
            )
            put(
                "namedAfterSomethingElse",
                buildJsonArray {
                    names.findings.forEach { finding ->
                        add(
                            buildJsonObject {
                                put("composableFqn", finding.composableFqn)
                                put("module", finding.moduleName)
                                put("namedAfter", finding.namedAfter)
                                put("shows", buildJsonArray { finding.shows.forEach { add(JsonPrimitive(it)) } })
                            },
                        )
                    }
                },
            )
            put("skippedRows", names.skipped)
        }.toString()
    }

    private fun asRow(facts: PreviewFacts): PreviewRow =
        HealthRow(indexed(facts.composableFqn, facts.functionName, facts.packageName, facts.targets), facts.moduleName)

    private fun asSnapshotRow(facts: SnapshotFacts): PreviewRow {
        val functionName = facts.snapshotFqn.substringAfterLast('.')
        return HealthRow(
            indexed(facts.snapshotFqn, functionName, facts.snapshotFqn.substringBeforeLast('.'), facts.targets),
            facts.moduleName,
        )
    }

    private fun indexed(fqn: String, functionName: String, packageName: String, targets: List<String>) =
        IndexedPreview(
            displayName = functionName,
            functionName = functionName,
            packageName = packageName,
            jvmClassName = fqn.substringBeforeLast('.'),
            composableFqn = fqn,
            offset = 0,
            annotationKind = AnnotationKind.ANDROIDX,
            isPrivate = false,
            hasPreviewParameter = false,
            previewGroup = null,
            unsupportedReason = null,
            targets = targets,
        )

    /** The rule takes `PreviewRow`s and the snapshot carries flat facts, so this is the adapter between them.
     *  Only `functionName`, `composableFqn`, `targets` and `moduleName` are read; the rest is filler the rule
     *  never touches, exactly as in `CoverageReportTool`. */
    private data class HealthRow(
        override val indexed: IndexedPreview,
        override val moduleName: String,
        override val coverage: SnapshotCoverage = SnapshotCoverage.Uncovered,
    ) : PreviewRow
}
