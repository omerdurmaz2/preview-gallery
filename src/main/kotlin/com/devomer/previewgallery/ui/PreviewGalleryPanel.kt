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
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.TreeExpander
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
    private val treeExpander = DefaultTreeExpander(tree)
    private val statusLabel = com.intellij.ui.components.JBLabel()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    private var entries: List<PreviewEntry> = emptyList()
    private var lastSelectedEntry: PreviewEntry? = null

    /** The label path (see [labelPathFor]) of every row expanded in the tree, captured just before a rebuild
     *  discards its node instances. Null means "nothing captured yet" — only true for the very first build —
     *  which is what makes [applyExpansionPolicy]'s module-level default apply exactly once instead of on every
     *  rebuild. Populated (possibly to an empty list, e.g. right after a Collapse All) by [applyFilter]. */
    private var rememberedExpansion: List<List<String>>? = null

    /** Whether the tree currently on screen (the one about to be replaced by the NEXT [applyFilter] call) was
     *  built from a non-empty query. Set at the end of [applyFilter] from that call's own query, so by the time
     *  the following call reads it, it describes the OUTGOING tree — which is what the capture guard must test.
     *  Testing the incoming/new query instead (as [searchField]'s text would) gets the rebuild that clears a
     *  query backwards: at that point the old, still-current tree is the query's machine-fully-expanded one, so
     *  its forced-open state would be captured and replayed onto the unfiltered tree instead of being discarded. */
    private var lastBuildWasQueryDriven = false

    /** An entry another surface asked to reveal before the tree could show it (the tool window may have been
     *  created by that very request, so [entries] can still be loading). Applied by [applyFilter], and dropped
     *  as soon as [entries] is loaded — whether or not the node was actually found — so a stale or filtered-out
     *  id never outlives the "still loading" window it exists for. */
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

        // The platform's own expand/collapse actions: same icons, tooltips and shortcuts as the Project view,
        // which is the behaviour a user coming from that tree expects.
        val commonActions = CommonActionsManager.getInstance()
        val actionGroup = DefaultActionGroup(
            RefreshAction(project) { reload() },
            ModuleFilterToggleAction(project) { applyFilter() },
            commonActions.createExpandAllAction(treeExpander, this),
            commonActions.createCollapseAllAction(treeExpander, this),
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

    /** Reveals and selects [entryId]: expands its ancestor path, selects the leaf, and scrolls it into view.
     *  Used by explicit reveal entry points ([revealEntry] and [PreviewSearchEverywhereContributor]) where
     *  opening the path is exactly the point. For the selection-restore path inside [applyFilter], which must
     *  NOT re-open a branch the user collapsed, see the private [selectEntry] overload below. */
    fun selectEntry(entryId: String) {
        selectEntry(entryId, revealPath = true)
    }

    /**
     * Selects [entryId], optionally revealing (expanding + scrolling to) its ancestor path.
     *
     * [revealPath] = true is the reveal intent: an explicit user/outside action asked to bring this entry into
     * view, so opening its path is the whole point.
     *
     * [revealPath] = false is the restore intent, used only by [applyFilter] when reapplying the previously
     * selected entry onto a freshly rebuilt tree. That rebuild can be triggered by far more than user intent
     * (e.g. [ActiveModuleTracker] firing on every editor `selectionChanged`, even when the active module did not
     * change), so it must not silently re-open a branch the user deliberately collapsed. The selection is still
     * set — [selectedEntry] and the `currentSelection?.id != previousSelectionId` check in [applyFilter] behave
     * exactly as if the node were visible — but the tree's expansion state is left untouched. Note that
     * [Tree.setSelectionPath] auto-expands a hidden path by default ([Tree.getExpandsSelectedPaths]), so that
     * flag is disabled for the duration of a non-revealing selection.
     */
    private fun selectEntry(entryId: String, revealPath: Boolean) {
        val path = findPath(entryId) ?: return
        if (revealPath) {
            path.parentPath?.let { tree.expandPath(it) }
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        } else {
            val previousExpandsSelectedPaths = tree.expandsSelectedPaths
            tree.expandsSelectedPaths = false
            try {
                tree.selectionPath = path
            } finally {
                tree.expandsSelectedPaths = previousExpandsSelectedPaths
            }
        }
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

    /** The label of every visible row, top to bottom — expansion state made assertable without a renderer. */
    @TestOnly
    fun visibleRowLabelsForTest(): List<String> = (0 until tree.rowCount).mapNotNull { row ->
        val node = tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode
        node?.let { labelOf(it.userObject) }
    }

    /** The expander the toolbar's expand/collapse actions drive. */
    @TestOnly
    fun treeExpanderForTest(): TreeExpander = treeExpander

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
        // Capture the user's expansion before the rebuild discards every node instance, so it can be restored
        // in applyExpansionPolicy below. Only when the OUTGOING tree (the one still on screen, tested via
        // lastBuildWasQueryDriven) was not itself built from a query: a query's expansion is machine-made (every
        // surviving row is opened to show the matches), so remembering it would leak that forced-open state into
        // the next no-query rebuild. This is deliberately not a test of the incoming searchField.text — that
        // reads the NEW query, but capturedExpansion() below reads the OLD, not-yet-rebuilt tree; on the rebuild
        // that clears a query, the old tree is the query's fully-expanded one, so testing the new (empty) text
        // would capture and replay that forced expansion instead of discarding it. Only when the tree already
        // has rows: on the very first build there is nothing to remember, and rememberedExpansion must stay null
        // so the module-level default below applies. A revealed branch (selectEntry's revealPath = true) is
        // captured like any other open branch: at the JTree level a reveal cannot be told apart from the user
        // expanding the same branch by hand and then clicking a preview inside it, so it is simply treated as
        // opened state — if the user does not want it open, they collapse it, which this capture then remembers.
        if (!lastBuildWasQueryDriven && tree.rowCount > 0) {
            rememberedExpansion = capturedExpansion()
        }
        restoringSelection = true
        try {
            treeRoot.removeAllChildren()
            modules.forEach { module ->
                val moduleNode = DefaultMutableTreeNode(module)
                // Branches before leaves at every level: the leaves of a row are its own previews, and burying
                // them above the sub-packages would make a deep tree read as if the packages belonged to them.
                module.branches.forEach { addBranch(moduleNode, it) }
                module.previews.forEach { moduleNode.add(DefaultMutableTreeNode(it)) }
                treeRoot.add(moduleNode)
            }
            treeModel.reload()
            applyExpansionPolicy()
            val pending = pendingSelectionId
            if (pending != null) {
                // A reveal request outranks the restore: it is an explicit user action, while the restore only
                // exists to survive the rebuild. Keep it pending only until entries have actually loaded — once
                // they have, either the node was found, or the id is simply unreachable (stale, or filtered out
                // by the active-module filter) and must not keep retrying on every later rebuild.
                selectEntry(pending)
                if (entries.isNotEmpty()) pendingSelectionId = null
            } else if (previousSelectionId != null) {
                // No-op if the previously selected entry was filtered out; selection then stays empty. This is
                // the restore intent (revealPath = false): re-selecting the same entry across a rebuild must not
                // re-open a branch the user collapsed themselves before this rebuild was triggered.
                selectEntry(previousSelectionId, revealPath = false)
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

        // Describes the tree this call just built, for the capture guard at the top of the NEXT applyFilter call
        // (see lastBuildWasQueryDriven's own KDoc for why that call, not this one, needs it).
        lastBuildWasQueryDriven = searchField.text.isNotEmpty()

        setState(
            when {
                entries.isEmpty() -> State.NO_PREVIEWS
                moduleFilterOn && visible.isEmpty() -> State.NO_ACTIVE_MODULE
                modules.isEmpty() -> State.NO_MATCH
                else -> State.LOADED
            },
        )
    }

    /**
     * A query has already pruned the tree to the matching rows, so opening everything shows exactly the
     * results. With no query, the tree keeps whatever the user had open across the rebuild — a rebuild fires far
     * more often than the user acts on it ([ActiveModuleTracker] on every editor `selectionChanged`, Refresh,
     * every keystroke), so it must not silently undo a Collapse All from the toolbar or a branch the user closed
     * by hand. Only the very first build, when [rememberedExpansion] is still null, falls back to expanding the
     * module level.
     */
    private fun applyExpansionPolicy() {
        if (searchField.text.isNotEmpty()) {
            var row = 0
            while (row < tree.rowCount) {
                tree.expandRow(row)
                row++
            }
            return
        }
        val remembered = rememberedExpansion
        if (remembered == null) {
            for (index in 0 until treeRoot.childCount) {
                val moduleNode = treeRoot.getChildAt(index) as? DefaultMutableTreeNode ?: continue
                tree.expandPath(TreePath(moduleNode.path))
            }
            return
        }
        for (labels in remembered) {
            // A label path that no longer resolves simply named rows that filtering/rebuilding removed; skip it
            // rather than expanding a partial or wrong node.
            val node = findNodeByLabelPath(labels) ?: continue
            tree.expandPath(TreePath(node.path))
        }
    }

    /** The label path of every currently expanded row, from the first level below the invisible root down to
     *  that row — the same label mapping [visibleRowLabelsForTest] uses, so a path survives the rebuild even
     *  though every node instance is replaced. */
    private fun capturedExpansion(): List<List<String>> {
        val expandedPaths = tree.getExpandedDescendants(TreePath(treeRoot)) ?: return emptyList()
        val result = mutableListOf<List<String>>()
        while (expandedPaths.hasMoreElements()) {
            val labels = labelPathFor(expandedPaths.nextElement()) ?: continue
            result.add(labels)
        }
        return result
    }

    /** Converts [path] (rooted at the invisible [treeRoot]) into the labels of every node below the root, or
     *  null if any node along the way carries no label (defensive; every real node has one). */
    private fun labelPathFor(path: TreePath): List<String>? {
        val labels = mutableListOf<String>()
        for (component in path.path) {
            val node = component as? DefaultMutableTreeNode ?: return null
            if (node === treeRoot) continue
            labels.add(labelOf(node.userObject) ?: return null)
        }
        return labels
    }

    /** The label [visibleRowLabelsForTest] would show for [userObject], or null for an unrecognised node. */
    private fun labelOf(userObject: Any?): String? = when (userObject) {
        is PreviewNode.ModuleNode -> userObject.moduleName
        is PreviewNode.PackageBranch -> userObject.segment
        is PreviewNode.PreviewLeaf -> userObject.row.indexed.displayName
        else -> null
    }

    /** Walks the current tree from [treeRoot] following [labels] one level at a time, returning the node at the
     *  end of the path, or null as soon as a label no longer matches any child (the row it named was filtered
     *  out of the rebuilt tree). */
    private fun findNodeByLabelPath(labels: List<String>): DefaultMutableTreeNode? {
        var current = treeRoot
        for (label in labels) {
            var next: DefaultMutableTreeNode? = null
            for (index in 0 until current.childCount) {
                val child = current.getChildAt(index) as? DefaultMutableTreeNode ?: continue
                if (labelOf(child.userObject) == label) {
                    next = child
                    break
                }
            }
            current = next ?: return null
        }
        return current
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

    private fun addBranch(parent: DefaultMutableTreeNode, branch: PreviewNode.PackageBranch) {
        val node = DefaultMutableTreeNode(branch)
        branch.branches.forEach { addBranch(node, it) }
        branch.previews.forEach { node.add(DefaultMutableTreeNode(it)) }
        parent.add(node)
    }

    /**
     * Depth-first search for the leaf carrying [entryId]. Runs on every rebuild (selection restore), so it walks
     * children by index rather than materialising a list per level.
     */
    private fun findPath(entryId: String): TreePath? = findPath(treeRoot, entryId)

    private fun findPath(node: DefaultMutableTreeNode, entryId: String): TreePath? {
        val entry = (node.userObject as? PreviewNode.PreviewLeaf)?.row as? PreviewEntry
        if (entry?.id == entryId) return TreePath(node.path)
        for (index in 0 until node.childCount) {
            val child = node.getChildAt(index) as? DefaultMutableTreeNode ?: continue
            findPath(child, entryId)?.let { return it }
        }
        return null
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150
    }
}
