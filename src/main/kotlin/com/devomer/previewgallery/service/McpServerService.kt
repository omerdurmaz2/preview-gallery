package com.devomer.previewgallery.service

import com.devomer.previewgallery.mcp.DispatchResult
import com.devomer.previewgallery.mcp.McpDispatcher
import com.devomer.previewgallery.mcp.McpHttpServer
import com.devomer.previewgallery.mcp.PreviewFacts
import com.devomer.previewgallery.mcp.ProjectSnapshot
import com.devomer.previewgallery.mcp.ReferenceImage
import com.devomer.previewgallery.mcp.SnapshotFacts
import com.devomer.previewgallery.mcp.ToolRegistry
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.SnapshotCoverage
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Owns the MCP socket and turns the IDE's live index into the flat [ProjectSnapshot] the protocol half serves.
 *
 * Application-level on purpose (spec D4): this workflow runs two IDEs at once — the main one and the plugin's
 * `runIde` sandbox — and a project-level server would make the second fight for the port. One server, one port,
 * and a `project` argument on every tool.
 */
@Service(Service.Level.APP)
class McpServerService : Disposable {

    sealed interface StartResult {
        data object Started : StartResult
        data object AlreadyRunning : StartResult
        data class PortInUse(val port: Int) : StartResult
    }

    val port: Int = PORT

    @Volatile
    private var server: McpHttpServer? = null

    val isRunning: Boolean get() = server?.isRunning == true

    fun start(): StartResult {
        if (isRunning) return StartResult.AlreadyRunning
        val dispatcher = McpDispatcher(SERVER_NAME, SERVER_VERSION, ToolRegistry(::snapshots))
        val started = McpHttpServer(PORT) { request -> dispatchLogged(dispatcher, request) }
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

    override fun dispose() {
        stop()
    }

    /**
     * `McpHttpServer` cannot log (`mcp/` cannot import `com.intellij`), so its catch turns a throw into a bare
     * 500 with nothing in `idea.log` either. This is the boundary that can: log with the project's logger, then
     * rethrow so the transport's own catch still produces the same response it always did.
     */
    private fun dispatchLogged(dispatcher: McpDispatcher, request: String): DispatchResult =
        try {
            dispatcher.handle(request)
        } catch (e: Throwable) {
            thisLogger().warn("MCP request handling failed", e)
            throw e
        }

    /**
     * One entry per open project, each read under its own read action.
     *
     * A project can be disposed in the gap between [Project.isDisposed] and the read action below — closing a
     * project while a call is in flight is expected, not a bug. [snapshotOrNull] isolates that failure to its own
     * project so the response still answers for every project that is fine, instead of a single straggler
     * turning the whole call into a 500.
     */
    fun snapshots(): List<ProjectSnapshot> =
        ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .mapNotNull(::snapshotOrNull)

    /**
     * Platform rule: [ProcessCanceledException] (and [CancellationException], the same signal under
     * coroutines) must always propagate, never be logged and dropped like an ordinary failure — dropping one
     * here would silently cancel whatever indexing or write action the read action was waiting behind.
     */
    private fun snapshotOrNull(project: Project): ProjectSnapshot? = try {
        ReadAction.compute<ProjectSnapshot, RuntimeException> { snapshot(project) }
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        thisLogger().warn("Dropping project \"${project.name}\" from the MCP response: its snapshot failed", e)
        null
    }

    private fun snapshot(project: Project): ProjectSnapshot {
        val name = project.name
        val path = project.basePath ?: ""
        if (DumbService.isDumb(project)) return ProjectSnapshot(name, path, indexing = true)

        val rows = cachedRows(project)
        return ProjectSnapshot(
            name = name,
            path = path,
            indexing = false,
            previews = rows.previews,
            snapshots = rows.snapshots,
        )
    }

    /**
     * The expensive half of [snapshot], cached per project the way [PreviewIndexService] caches its own rows.
     *
     * [name] and [path] are deliberately not part of what is cached here: a light test fixture reuses the same
     * `Project` object across test methods with a different [Project.name] each time, and a provider whose
     * captured fields can hold two different values for what the platform sees as "the same" cached slot is
     * exactly the misuse [com.intellij.util.CachedValueStabilityChecker] exists to catch — it fails the build
     * under test, and in production it would mean a stale name surviving a rename that raised no PSI event.
     * [project] itself is safe to capture: it is compared by reference, and the reference is stable for as long
     * as the cache entry it is stored on is.
     */
    private fun cachedRows(project: Project): Rows =
        CachedValuesManager.getManager(project).getCachedValue(
            project,
            SNAPSHOT_CACHE_KEY,
            {
                val index = PreviewIndexService.getInstance(project)
                val previews = index.findAll()
                val orphans = index.findOrphanSnapshots()
                CachedValueProvider.Result.create(
                    Rows(previews.map(::previewFacts), snapshotFacts(project, previews, orphans)),
                    PsiModificationTracker.MODIFICATION_COUNT,
                )
            },
            false,
        )

    private data class Rows(val previews: List<PreviewFacts>, val snapshots: List<SnapshotFacts>)

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
        referenceImages = referenceImages(project, entry),
    )

    /**
     * Reference PNGs as absolute paths (spec D7), found the way [ReferenceImageLocator] finds them for the
     * panel's own strip: the real layout nests each PNG under `<package>/<Facade>/`, named
     * `<function>_<variant>_<hash>_<i>.png`, so a flat scan of the `reference` directory's direct children — the
     * bug this replaced — never matches anything and a filter by [PreviewEntry]'s own function is required to
     * not also collect a sibling function's images. A missing directory is an empty list, not an error: a
     * snapshot whose `update…ScreenshotTest` has never run is a real state an agent should be able to see.
     *
     * Called once per [ReferenceRoots.Root] rather than once with every root, so [ReferenceImage.variant] can
     * carry that root's own [ReferenceRoots.Root.buildVariant] — [ReferenceImageLocator.locate]'s merged result
     * keeps only the lower-cased display token, which is not the same string.
     *
     * Does not call [ReferenceRoots.refresh] first, so a PNG written by an `update…ScreenshotTest` run moments
     * ago can stay invisible here until something else refreshes the VFS — indistinguishable from the test
     * never having run. [ReferenceRoots.refresh] must not run under a read lock, and this whole method does; a
     * synchronous VFS refresh from inside the read action this runs under is not something to add casually.
     */
    private fun referenceImages(project: Project, entry: PreviewEntry): List<ReferenceImage> {
        val moduleDirectory = ModuleDirectoryResolver.resolve(project, entry.file) ?: return emptyList()
        return ReferenceRoots.of(moduleDirectory).flatMap { root ->
            ReferenceImageLocator.locate(entry, listOf(root)).map { ReferenceImage(root.buildVariant, it.file.path) }
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
        private val SNAPSHOT_CACHE_KEY = Key.create<CachedValue<Rows>>(
            "com.devomer.previewgallery.mcp.snapshot",
        )

        fun getInstance(): McpServerService =
            ApplicationManager.getApplication().getService(McpServerService::class.java)
    }
}
