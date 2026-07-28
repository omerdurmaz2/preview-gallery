package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.render.BuildService
import com.devomer.previewgallery.render.EphemeralPickerBridge
import com.devomer.previewgallery.render.LiveRenderer
import com.devomer.previewgallery.render.PreviewPickerBridge
import com.devomer.previewgallery.render.RenderApiProbe
import com.devomer.previewgallery.render.RenderPipeline
import com.devomer.previewgallery.search.PreviewModuleFilter
import com.devomer.previewgallery.service.PreviewIndexService
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.Collections
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class PreviewGalleryPanel(
    private val project: Project,
    private val parentDisposable: Disposable,
) : JBPanel<PreviewGalleryPanel>(BorderLayout()) {

    enum class State { INDEXING, NO_PREVIEWS, NO_MATCH, NO_ACTIVE_MODULE, LOADED }

    var state: State = State.INDEXING
        private set

    private val searchField = SearchTextField()
    private val treeRoot = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = Tree(treeModel)
    private val statusLabel = com.intellij.ui.components.JBLabel()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    private var entries: List<PreviewEntry> = emptyList()
    private var lastSelectedEntry: PreviewEntry? = null

    /** An entry another surface asked to reveal before the tree could show it (the tool window may have been
     *  created by that very request, so [entries] can still be loading). Applied by [applyFilter] and cleared
     *  once the selection lands. */
    private var pendingSelectionId: String? = null

    /** Suppresses [pipeline] notifications while [applyFilter] is rebuilding tree nodes and restoring the
     *  previous selection onto the new ones — the rebuild otherwise looks like the selection was cleared and
     *  then reselected, which would restart an in-progress or already-finished render for no reason. */
    private var restoringSelection = false

    private val moduleTracker = ActiveModuleTracker(project, parentDisposable) { applyFilter() }

    private val renderPanel = PreviewRenderPanel(project)
    private val pipeline = RenderPipeline(
        project,
        LiveRenderer(project),
        BuildService.getInstance(project),
        parentDisposable,
    ) { view -> renderPanel.show(view, lastSelectedEntry) }
    private val pickerBridge = PreviewPickerBridge(project)
    private val ephemeralPickerBridge = EphemeralPickerBridge(project)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = PreviewTreeCellRenderer()
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.addTreeSelectionListener {
            if (restoringSelection) return@addTreeSelectionListener
            val selected = selectedEntry()
            lastSelectedEntry = selected
            pipeline.select(selected)
        }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigateToSelection()
        }.installOn(tree)

        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_ENTER && navigateToSelection()) event.consume()
            }
        })

        searchField.textEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                alarm.cancelAllRequests()
                alarm.addRequest({ applyFilter() }, SEARCH_DEBOUNCE_MS)
            }
        })

        val treeSide = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }
        // Single horizontal split: previews are tall, so a full-height render pane suits them better than a
        // short bottom strip (design D-fix: drop the file-info detail panel, give the space to the render).
        val split = OnePixelSplitter(false, "PreviewGallery.split", 0.35f).apply {
            firstComponent = treeSide
            secondComponent = renderPanel
        }

        renderPanel.onRender = { pipeline.requestBuildAndRender(it) }
        renderPanel.onOpenFile = { OpenFileDescriptor(project, it.file, it.indexed.offset).navigate(true) }
        renderPanel.propertiesAvailable = pickerBridge.isAvailable()
        renderPanel.onProperties = { entry, point -> pickerBridge.showPicker(entry, point, ::onPickerModification) }
        renderPanel.onNavigateToSource = { navigateToSource(it) }
        // PG6-4: comparison-view tab strip. RenderApiProbe is a plugin-owned object (render/) returning a plain
        // Boolean, so this stays AS-free despite gating an AS-backed capability; renderVariant is the dedicated,
        // non-debounced per-tab render entry point (task-3 report §4 / PG6-4 design), independent of the single
        // debounced Original selection this pipeline otherwise drives.
        renderPanel.deviceOverrideAvailable = RenderApiProbe.isViewOverrideAvailable()
        renderPanel.onRequestVariant = { entry, override, callback -> pipeline.renderVariant(entry, override, callback) }
        // PG6-10: a copy tab's Properties opens Android Studio's own picker over an in-memory model instead of
        // re-rendering directly; each edit flows back through onEphemeralProperties' own onEdit callback (wired
        // in PreviewRenderPanel itself, not here) to update ComparisonViewList and re-render just that tab.
        renderPanel.onEphemeralProperties = { entry, override, point, onEdit ->
            ephemeralPickerBridge.showEphemeralPicker(entry, override, point, onEdit)
        }

        val actionGroup = DefaultActionGroup(
            RefreshAction(project) { reload() },
            ModuleFilterToggleAction(project) { applyFilter() },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("PreviewGallery", actionGroup, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)

        statusLabel.border = JBUI.Borders.empty(8)
        add(split, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        reload()
    }

    /**
     * Fires when [pickerBridge] reports the user changed a value in the picker (spec P3's primary signal).
     * Re-renders the currently displayed preview in place via [RenderPipeline.rerenderCurrent]: the selection
     * has not changed, so [RenderPipeline.select] would be a no-op, but the edited `@Preview` annotation means
     * the source has.
     */
    private fun onPickerModification() {
        pipeline.rerenderCurrent()
    }

    /** Reloads the index off the EDT. Safe to call repeatedly. */
    fun reload() {
        if (DumbService.isDumb(project)) {
            setState(State.INDEXING)
            // Tied to the panel's lifetime: DumbService keeps the callback until the next smart-mode
            // transition, which can outlive a disposed tool window.
            DumbService.getInstance(project).runWhenSmart {
                if (!Disposer.isDisposed(parentDisposable)) reload()
            }
            return
        }
        ReadAction.nonBlocking<List<PreviewEntry>> { PreviewIndexService.getInstance(project).findAll() }
            .expireWith(parentDisposable)
            .finishOnUiThread(ModalityState.defaultModalityState()) { loaded ->
                entries = loaded
                applyFilter()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** Synchronous reload for tests — the production path is [reload]. */
    @TestOnly
    fun reloadSynchronously() {
        entries = PreviewIndexService.getInstance(project).findAll()
        applyFilter()
    }

    /** Applies a query directly, bypassing the 150 ms debounce. */
    @TestOnly
    fun applyQueryForTest(query: String) {
        searchField.text = query
        applyFilter()
    }

    fun selectEntry(entryId: String) {
        val path = findPath(entryId) ?: return
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    /**
     * Brings [entryId] into view and selects it, for entry points outside the tool window (PG8: the editor's
     * "Show all previews" button). Unlike [selectEntry] this clears a stale search query first, and survives the
     * entries not being loaded yet.
     */
    fun revealEntry(entryId: String) {
        pendingSelectionId = entryId
        if (searchField.text.isNotEmpty()) searchField.text = ""
        applyFilter()
    }

    /** The id of the currently selected entry, or null if none is selected. */
    @TestOnly
    fun selectedEntryIdForTest(): String? = selectedEntry()?.id

    private fun applyFilter() {
        // The tree is rebuilt from scratch below (new node instances), which otherwise drops the current
        // selection on every keystroke/reload. Capture it by id and restore it onto the new nodes so a
        // perfectly renderable preview does not intermittently look unselected.
        val previousSelectionId = selectedEntry()?.id

        val moduleFilterOn = ModuleFilterToggleAction.isEnabled(project)
        val visible = PreviewModuleFilter.apply(
            entries,
            moduleTracker.activeModuleName,
            moduleFilterOn,
        )
        val modules = PreviewTreeModelBuilder.build(visible, searchField.text)
        restoringSelection = true
        try {
            treeRoot.removeAllChildren()
            modules.forEach { module ->
                val moduleNode = DefaultMutableTreeNode(module)
                module.packages.forEach { pkg ->
                    val packageNode = DefaultMutableTreeNode(pkg)
                    pkg.previews.forEach { packageNode.add(DefaultMutableTreeNode(it)) }
                    moduleNode.add(packageNode)
                }
                treeRoot.add(moduleNode)
            }
            treeModel.reload()
            expandAll()
            val pending = pendingSelectionId
            if (pending != null) {
                // A reveal request outranks the restore: it is an explicit user action, while the restore only
                // exists to survive the rebuild. Keep it pending until the node actually exists.
                selectEntry(pending)
                if (selectedEntry()?.id == pending) pendingSelectionId = null
            } else if (previousSelectionId != null) {
                // No-op if the previously selected entry was filtered out; selection then stays empty.
                selectEntry(previousSelectionId)
            }
        } finally {
            restoringSelection = false
        }

        val currentSelection = selectedEntry()
        // Only notify the pipeline when the selection actually changed: re-landing on the same entry must
        // not restart a render that is already in flight or already showing a result.
        if (currentSelection?.id != previousSelectionId) {
            lastSelectedEntry = currentSelection
            pipeline.select(currentSelection)
        }

        setState(
            when {
                entries.isEmpty() -> State.NO_PREVIEWS
                moduleFilterOn && visible.isEmpty() -> State.NO_ACTIVE_MODULE
                modules.isEmpty() -> State.NO_MATCH
                else -> State.LOADED
            },
        )
    }

    private fun expandAll() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    private fun setState(newState: State) {
        state = newState
        statusLabel.text = when (newState) {
            State.INDEXING -> PreviewGalleryBundle.message("state.indexing")
            State.NO_PREVIEWS -> PreviewGalleryBundle.message("state.noPreviews")
            State.NO_MATCH -> PreviewGalleryBundle.message("state.noMatch", searchField.text)
            State.NO_ACTIVE_MODULE -> PreviewGalleryBundle.message("state.noActiveModule")
            State.LOADED -> ""
        }
        statusLabel.isVisible = newState != State.LOADED
    }

    private fun selectedEntry(): PreviewEntry? {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return (node.userObject as? PreviewNode.PreviewLeaf)?.row as? PreviewEntry
    }

    private fun navigateToSelection(): Boolean {
        val entry = selectedEntry() ?: return false
        OpenFileDescriptor(project, entry.file, entry.indexed.offset).navigate(true)
        return true
    }

    /**
     * Opens the source a hit-tested render node points at (PG4-5). The ViewInfo path yields only a file name +
     * line (no character offset — [PreviewSourceLocation.offset] is always null), so this navigates by line.
     * [resolveSourceFile] looks the name up in PROJECT scope only, so a click landing on a framework node (e.g.
     * `CompositionLocal.kt`, which lives in a library) resolves to nothing and no-ops rather than opening
     * Compose's own source. Runs on the EDT (mouse click); the file lookup takes its own read action.
     */
    private fun navigateToSource(chain: List<PreviewSourceLocation>) {
        for (location in chain) {
            val file = resolveSourceFile(location) ?: continue
            // lineNumber straight from AS's SourceLocation into OpenFileDescriptor(project, file, line, column):
            // the gate confirmed the caret lands on the right line (used as a 0-based line, no off-by-one).
            OpenFileDescriptor(project, file, location.lineNumber.coerceAtLeast(0), 0).navigate(true)
            return
        }
    }

    /**
     * Resolves [location] to a [VirtualFile], disambiguating same-named project files via [SourceFileDisambiguator]
     * (PG5-4). Project scope only (source, not libraries) so a framework file resolves to nothing; skip while
     * indexing since FilenameIndex is unavailable then.
     */
    private fun resolveSourceFile(location: PreviewSourceLocation): VirtualFile? {
        // The clicked node is very often inside the selected preview's own file — prefer it, no index needed.
        lastSelectedEntry?.file?.let { if (it.name == location.fileName) return it }
        if (DumbService.isDumb(project)) return null
        return ReadAction.compute<VirtualFile?, RuntimeException> {
            val matches = FilenameIndex.getVirtualFilesByName(location.fileName, GlobalSearchScope.projectScope(project)).toList()
            if (matches.size <= 1) return@compute matches.firstOrNull()
            val candidates = matches.map { SourceFileDisambiguator.Candidate(it, packageHashOf(it)) }
            SourceFileDisambiguator.pick(location.packageHash, candidates)
        }
    }

    /** The same hash AS puts on a SourceLocation, computed from a candidate file's package (V1). Under a read
     *  action (caller holds one). Returns null when the package can't be resolved -> that candidate won't match. */
    private fun packageHashOf(file: VirtualFile): Int? {
        val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(file) as? org.jetbrains.kotlin.psi.KtFile ?: return null
        val packageFqn = psi.packageFqName.asString()
        // V1 (javap on design-tools.jar, SourceLocationWithVirtualFileKt.packageNameHash): AS hashes a
        // PsiClassOwner's package name as Math.abs(name.hashCode()), not a bare hashCode() — confirmed by
        // decompiling the private packageNameHash(String)/matchesPackage(PsiClassOwner, Int) helpers.
        return kotlin.math.abs(packageFqn.hashCode())
    }

    private fun findPath(entryId: String): TreePath? {
        val moduleNodes = Collections.list(treeRoot.children()).filterIsInstance<DefaultMutableTreeNode>()
        for (moduleNode in moduleNodes) {
            for (packageNode in Collections.list(moduleNode.children()).filterIsInstance<DefaultMutableTreeNode>()) {
                for (leafNode in Collections.list(packageNode.children()).filterIsInstance<DefaultMutableTreeNode>()) {
                    val entry = (leafNode.userObject as? PreviewNode.PreviewLeaf)?.row as? PreviewEntry
                    if (entry?.id == entryId) return TreePath(leafNode.path)
                }
            }
        }
        return null
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150
    }
}
