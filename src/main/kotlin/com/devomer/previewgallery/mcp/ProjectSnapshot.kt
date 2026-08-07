package com.devomer.previewgallery.mcp

/**
 * Everything one open project contributes to an MCP response, already flattened to strings and paths.
 *
 * This is the seam between the IDE and the protocol: `McpServerService` builds it inside a read action, and
 * nothing under `mcp/` sees a `Project`, a `VirtualFile` or a PSI element. That is what lets every tool be
 * tested by constructing one of these instead of standing up a fixture.
 */
data class ProjectSnapshot(
    val name: String,
    val path: String,
    /** True while the index is still building. The row lists are empty then, and saying so is the point. */
    val indexing: Boolean,
    val previews: List<PreviewFacts> = emptyList(),
    val snapshots: List<SnapshotFacts> = emptyList(),
)

/** One `@Preview` function, with the coverage already resolved. */
data class PreviewFacts(
    val composableFqn: String,
    val displayName: String,
    val moduleName: String,
    val packageName: String,
    val file: String,
    /** 1-based, or null when the file's document could not be loaded. */
    val line: Int?,
    val isPrivate: Boolean,
    val hasPreviewParameter: Boolean,
    /** Non-null when the preview cannot be rendered, e.g. because it is declared inside a class. */
    val unsupportedReason: String?,
    val covered: Boolean,
    /** FQNs of the snapshot functions that cover this preview. */
    val snapshots: List<String> = emptyList(),
)

/** One `@PreviewTest` function, and the reference images committed for it. */
data class SnapshotFacts(
    val snapshotFqn: String,
    val moduleName: String,
    val file: String,
    val line: Int?,
    /** The composables this body shows — how a snapshot is matched to a preview. */
    val targets: List<String> = emptyList(),
    /** True when no preview in the module shows the same composable. */
    val orphan: Boolean = false,
    val referenceImages: List<ReferenceImage> = emptyList(),
)

/** A committed reference PNG. [variant] is null for a reference root that is not under a build variant. */
data class ReferenceImage(val variant: String?, val path: String)
