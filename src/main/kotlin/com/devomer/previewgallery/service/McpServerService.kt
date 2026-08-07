package com.devomer.previewgallery.service

import com.devomer.previewgallery.mcp.McpDispatcher
import com.devomer.previewgallery.mcp.McpHttpServer
import com.devomer.previewgallery.mcp.PreviewFacts
import com.devomer.previewgallery.mcp.ProjectSnapshot
import com.devomer.previewgallery.mcp.ReferenceImage
import com.devomer.previewgallery.mcp.SnapshotFacts
import com.devomer.previewgallery.mcp.ToolRegistry
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.SnapshotCoverage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Owns the MCP socket and turns the IDE's live index into the flat [ProjectSnapshot] the protocol half serves.
 *
 * Application-level on purpose (spec D4): this workflow runs two IDEs at once — the main one and the plugin's
 * `runIde` sandbox — and a project-level server would make the second fight for the port. One server, one port,
 * and a `project` argument on every tool.
 */
@Service(Service.Level.APP)
class McpServerService {

    sealed interface StartResult {
        data object Started : StartResult
        data object AlreadyRunning : StartResult
        data class PortInUse(val port: Int) : StartResult
    }

    val port: Int = PORT

    private var server: McpHttpServer? = null

    val isRunning: Boolean get() = server?.isRunning == true

    fun start(): StartResult {
        if (isRunning) return StartResult.AlreadyRunning
        val dispatcher = McpDispatcher(SERVER_NAME, SERVER_VERSION, ToolRegistry(::snapshots))
        val started = McpHttpServer(PORT) { dispatcher.handle(it) }
        return try {
            started.start()
            server = started
            StartResult.Started
        } catch (e: IOException) {
            thisLogger().warn("Failed to bind the MCP server to port $PORT", e)
            StartResult.PortInUse(PORT)
        }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    /** One entry per open project, each read under its own read action. */
    fun snapshots(): List<ProjectSnapshot> =
        ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .map { project -> ReadAction.compute<ProjectSnapshot, RuntimeException> { snapshot(project) } }

    private fun snapshot(project: Project): ProjectSnapshot {
        val name = project.name
        val path = project.basePath ?: ""
        if (DumbService.isDumb(project)) return ProjectSnapshot(name, path, indexing = true)

        val index = PreviewIndexService.getInstance(project)
        val previews = index.findAll()
        val orphans = index.findOrphanSnapshots()
        return ProjectSnapshot(
            name = name,
            path = path,
            indexing = false,
            previews = previews.map(::previewFacts),
            snapshots = snapshotFacts(project, previews, orphans),
        )
    }

    private fun previewFacts(entry: PreviewEntry) = PreviewFacts(
        composableFqn = entry.indexed.composableFqn,
        displayName = entry.indexed.displayName,
        moduleName = entry.moduleName,
        packageName = entry.indexed.packageName,
        file = entry.file.path,
        line = lineOf(entry.file, entry.indexed.offset),
        isPrivate = entry.indexed.isPrivate,
        hasPreviewParameter = entry.indexed.hasPreviewParameter,
        unsupportedReason = entry.indexed.unsupportedReason,
        covered = entry.coverage is SnapshotCoverage.Covered,
        snapshots = entry.snapshots.map { it.indexed.composableFqn },
    )

    private fun snapshotFacts(
        project: Project,
        previews: List<PreviewEntry>,
        orphans: List<PreviewEntry>,
    ): List<SnapshotFacts> {
        val covering = previews.flatMap { it.snapshots }.distinctBy { it.indexed.composableFqn }
        return (covering.map { facts(project, it, orphan = false) } +
            orphans.map { facts(project, it, orphan = true) })
    }

    private fun facts(project: Project, entry: PreviewEntry, orphan: Boolean) = SnapshotFacts(
        snapshotFqn = entry.indexed.composableFqn,
        moduleName = entry.moduleName,
        file = entry.file.path,
        line = lineOf(entry.file, entry.indexed.offset),
        targets = entry.indexed.targets,
        orphan = orphan,
        referenceImages = referenceImages(project, entry.file),
    )

    /**
     * Reference PNGs as absolute paths (spec D7). A missing directory is an empty list, not an error: a
     * snapshot whose `update…ScreenshotTest` has never run is a real state an agent should be able to see.
     */
    private fun referenceImages(project: Project, file: VirtualFile): List<ReferenceImage> {
        val moduleDirectory = ModuleDirectoryResolver.resolve(project, file) ?: return emptyList()
        return ReferenceRoots.of(moduleDirectory).flatMap { root ->
            val children: Array<VirtualFile> = root.directory.children ?: emptyArray()
            children
                .filter { it.extension == "png" }
                .map { ReferenceImage(root.buildVariant, it.path) }
        }
    }

    /** 1-based, or null when the document is unavailable — a line number is worth a null, never a failed call. */
    private fun lineOf(file: VirtualFile, offset: Int): Int? {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        if (offset < 0 || offset > document.textLength) return null
        return document.getLineNumber(offset) + 1
    }

    companion object {
        const val PORT = 7891
        private const val SERVER_NAME = "preview-gallery"
        private const val SERVER_VERSION = "0.0.1"

        fun getInstance(): McpServerService =
            ApplicationManager.getApplication().getService(McpServerService::class.java)
    }
}
