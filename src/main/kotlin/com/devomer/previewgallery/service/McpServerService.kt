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
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
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
        val dispatcher = McpDispatcher(SERVER_NAME, SERVER_VERSION, ToolRegistry(::snapshots, ::blankGoldens))
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
     *
     * [ProcessCanceledException] and [CancellationException] are rethrown ahead of the logging catch, same rule
     * as [snapshotOrNull]: they are control flow, not a failure worth a stack trace in `idea.log`.
     */
    private fun dispatchLogged(dispatcher: McpDispatcher, request: String): DispatchResult =
        try {
            dispatcher.handle(request)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
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

    /**
     * Not cached at this layer. [PreviewIndexService] already caches [PreviewIndexService.findAll] and
     * [PreviewIndexService.findOrphanSnapshots] with the dependencies that actually invalidate them correctly
     * (`PsiModificationTracker.MODIFICATION_COUNT` **and** its own `refreshTracker`, the second of which fires
     * for the indexing passes that fill the index without touching PSI at all). A cache at this layer keyed on
     * `MODIFICATION_COUNT` alone — tried once, see PG17-10's report — would keep serving a snapshot from the
     * smart window between two such passes after both `refreshTracker` and a later PSI change had moved on,
     * which is the exact false negative spec D10 exists to prevent, reached through a cache instead of dumb
     * mode. The reference images below have the same problem if cached: they read the VFS directly, which is
     * not covered by a PSI-keyed dependency at all.
     */
    private fun snapshot(project: Project): ProjectSnapshot {
        val name = project.name
        val path = project.basePath ?: ""
        if (DumbService.isDumb(project)) return ProjectSnapshot(name, path, indexing = true)

        val index = PreviewIndexService.getInstance(project)
        val previews = index.findAll()
        val orphans = index.findOrphanSnapshots()
        val fileText = mutableMapOf<VirtualFile, CharSequence>()
        return ProjectSnapshot(
            name = name,
            path = path,
            indexing = false,
            previews = previews.map { previewFacts(it, fileText) },
            snapshots = snapshotFacts(project, previews, orphans, fileText),
        )
    }

    private fun previewFacts(entry: PreviewEntry, fileText: MutableMap<VirtualFile, CharSequence>) = PreviewFacts(
        composableFqn = entry.indexed.composableFqn,
        displayName = entry.indexed.displayName,
        functionName = entry.indexed.functionName,
        moduleName = entry.moduleName,
        packageName = entry.indexed.packageName,
        file = entry.file.path,
        line = lineOf(entry.file, entry.indexed.offset, fileText),
        isPrivate = entry.indexed.isPrivate,
        hasPreviewParameter = entry.indexed.hasPreviewParameter,
        unsupportedReason = entry.indexed.unsupportedReason,
        covered = entry.coverage is SnapshotCoverage.Covered,
        snapshots = entry.snapshots.map { it.indexed.composableFqn },
        targets = entry.indexed.targets,
    )

    private fun snapshotFacts(
        project: Project,
        previews: List<PreviewEntry>,
        orphans: List<PreviewEntry>,
        fileText: MutableMap<VirtualFile, CharSequence>,
    ): List<SnapshotFacts> {
        val covering = previews.flatMap { it.snapshots }.distinctBy { it.indexed.composableFqn }
        return (covering.map { facts(project, it, orphan = false, fileText) } +
            orphans.map { facts(project, it, orphan = true, fileText) })
    }

    private fun facts(
        project: Project,
        entry: PreviewEntry,
        orphan: Boolean,
        fileText: MutableMap<VirtualFile, CharSequence>,
    ) = SnapshotFacts(
        snapshotFqn = entry.indexed.composableFqn,
        moduleName = entry.moduleName,
        file = entry.file.path,
        line = lineOf(entry.file, entry.indexed.offset, fileText),
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

    /**
     * The blank goldens of one open project, decoded on demand.
     *
     * Deliberately not part of [ProjectSnapshot]: that is rebuilt on every tool call, so a field there would
     * decode every reference PNG for `list_previews` too. Only the health tool pays this cost, and only when
     * an agent asks for it.
     *
     * The locate half needs the project model and takes a read action; the decode half must not hold one
     * (spec D7), so the two are split rather than nested.
     */
    fun blankGoldens(projectName: String): List<GoldenInspector.BlankFinding> {
        val project = ProjectManager.getInstance().openProjects
            .firstOrNull { !it.isDisposed && it.name == projectName }
            ?: return emptyList()
        val candidates = ReadAction.compute<List<GoldenInspector.Candidate>, RuntimeException> {
            if (DumbService.isDumb(project)) return@compute emptyList()
            val index = PreviewIndexService.getInstance(project)
            val covering = index.findAll().flatMap { it.snapshots }.distinctBy { it.indexed.composableFqn }
            val snapshots = covering + index.findOrphanSnapshots()
            snapshots.flatMap { snapshot ->
                val moduleDirectory = ModuleDirectoryResolver.resolve(project, snapshot.file)
                    ?: return@flatMap emptyList()
                ReferenceImageLocator.locate(snapshot, ReferenceRoots.of(moduleDirectory)).map {
                    GoldenInspector.Candidate(snapshot.indexed.composableFqn, snapshot.moduleName, it)
                }
            }
        }
        return GoldenInspector.inspect(candidates).findings
    }

    /**
     * 1-based, or null when the offset cannot be resolved — a line number is worth a null, never a failed call.
     *
     * Prefers [FileDocumentManager.getCachedDocument] — a document only exists there if an editor already
     * opened one — over [FileDocumentManager.getDocument], which would load and decode the file and build its
     * line table just to answer this one lookup; doing that once per row inside one read action is PG17-10 item
     * 2. When no document is cached, [fileText] holds each file's raw text for the lifetime of this call only
     * (built via [LoadTextUtil.loadText], never [com.intellij.openapi.vfs.VfsUtilCore.loadText]: [offset] is a
     * PSI offset, always in `\n`-normalized coordinates, and the latter would hand back the file's raw bytes —
     * `\r\n` and all — so counting newlines in it drifts further from the true line the deeper into the file the
     * offset is), so a file whose twenty previews all need a line pays for one disk read per call, not one per
     * row, and the cache itself cannot go stale because nothing survives past the call that built it.
     */
    private fun lineOf(file: VirtualFile, offset: Int, fileText: MutableMap<VirtualFile, CharSequence>): Int? {
        if (offset < 0) return null
        FileDocumentManager.getInstance().getCachedDocument(file)?.let { document ->
            return if (offset > document.textLength) null else document.getLineNumber(offset) + 1
        }
        val text = fileText.getOrPut(file) { LoadTextUtil.loadText(file) }
        if (offset > text.length) return null
        var line = 1
        for (i in 0 until offset) if (text[i] == '\n') line++
        return line
    }

    companion object {
        const val PORT = 7891
        private const val SERVER_NAME = "preview-gallery"
        private const val SERVER_VERSION = "0.0.1"

        fun getInstance(): McpServerService =
            ApplicationManager.getApplication().getService(McpServerService::class.java)
    }
}
