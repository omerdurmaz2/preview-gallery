package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.model.ReferenceImage
import com.devomer.previewgallery.render.BuildService
import com.devomer.previewgallery.render.EphemeralPickerBridge
import com.devomer.previewgallery.render.LiveRenderer
import com.devomer.previewgallery.render.PreviewPickerBridge
import com.devomer.previewgallery.render.RenderApiProbe
import com.devomer.previewgallery.render.RenderPipeline
import com.devomer.previewgallery.render.RenderState
import com.devomer.previewgallery.search.PreviewModuleFilter
import com.devomer.previewgallery.service.ModuleDirectoryResolver
import com.devomer.previewgallery.service.PreviewIndexService
import com.devomer.previewgallery.service.ReferenceImageLocator
import com.devomer.previewgallery.service.ReferenceRoots
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.TreeExpander
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.CheckedDisposable
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
import java.awt.image.BufferedImage
import java.io.IOException
import javax.imageio.ImageIO
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import kotlin.reflect.KMutableProperty0

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
    private val treeExpander = SelectionPreservingTreeExpander(tree, treeRoot, ::restoringSelection)
    private val statusLabel = com.intellij.ui.components.JBLabel()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    /** The snapshot-selection debounce (see [showReferenceImages]). Separate from [alarm], which the search field
     *  cancels on every keystroke. */
    private val referenceAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    /** "Is the panel gone?", checked before the reference lookup's own EDT hop — the same live disposal check
     *  [RenderPipeline] keeps for its render results, and for the same reason: [Alarm] and
     *  `ReadAction.expireWith` cover the work they own, but the plain `invokeLater` that publishes the decoded
     *  images is not tied to [parentDisposable] by itself. */
    private val disposalCheck: CheckedDisposable = Disposer.newCheckedDisposable(parentDisposable)

    private var entries: List<PreviewEntry> = emptyList()

    /** The snapshots that matched no preview, shown under each module's own orphan branch (spec D4). Loaded in
     *  [reload] alongside [entries] rather than read per [applyFilter]: [PreviewIndexService.findOrphanSnapshots]
     *  shares [PreviewIndexService.findAll]'s cached computation, so calling it from [applyFilter] — which runs on
     *  the EDT, on every keystroke and every editor selection change — would put that whole index join on the EDT
     *  the first time a PSI change had invalidated the cache. */
    private var orphanSnapshots: List<PreviewEntry> = emptyList()

    private var lastSelectedEntry: PreviewEntry? = null

    /** The label path (see [labelPathFor]) of every row expanded in the tree, captured just before a rebuild
     *  discards its node instances. Null means "nothing captured yet" — only true for the very first build —
     *  which is what makes [applyExpansionPolicy]'s module-level default apply exactly once instead of on every
     *  rebuild. Populated by [applyFilter]; empty (not null) right after a Collapse All, since [capturedExpansion]
     *  excludes the invisible root's own (label-less) path from the result. */
    private var rememberedExpansion: List<List<String>>? = null

    /** Whether the tree currently on screen (the one about to be replaced by the NEXT [applyFilter] call) was
     *  built from a non-empty query. Set inside [applyFilter], right after that call's own [applyExpansionPolicy]
     *  has built the tree now on screen, so by the time the following call reads it, it describes the OUTGOING
     *  tree — which is what the capture guard must test.
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

    /** PG11-2: indexing a large project takes several dumb→smart transitions, and [reload]'s own
     *  `runWhenSmart` only rides the first one — so the tree used to cache whatever fraction of the index
     *  existed at that moment and stay that way until the user pressed Refresh. This reloads again, exactly
     *  like that button does, on every later pass that actually moved the index. See the tracker's own doc. */
    private val indexingTracker = IndexingCompletionTracker(project, parentDisposable) {
        PreviewIndexService.getInstance(project).refresh()
        reload()
    }

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
            routeSelection(deferReferenceLookup = true)
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
            // The lambda is the reload only: RefreshAction itself invalidates PreviewIndexService's cache first,
            // which is what makes the button a real escape hatch for inputs that raise no PSI event — a
            // module's `src/screenshotTest` directory appearing or disappearing (PG13) is exactly such an input.
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
        ReadAction.nonBlocking<LoadedRows> { loadRows() }
            .expireWith(parentDisposable)
            .finishOnUiThread(ModalityState.defaultModalityState()) { loaded ->
                entries = loaded.previews
                orphanSnapshots = loaded.orphans
                applyFilter()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** Both halves of one index read, taken together so the tree never mixes previews from one computation with
     *  orphan snapshots from another — the service caches them as a pair for the same reason. */
    private fun loadRows(): LoadedRows {
        val service = PreviewIndexService.getInstance(project)
        return LoadedRows(service.findAll(), service.findOrphanSnapshots())
    }

    /** Synchronous reload for tests — the production path is [reload]. */
    @TestOnly
    fun reloadSynchronously() {
        val loaded = loadRows()
        entries = loaded.previews
        orphanSnapshots = loaded.orphans
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

    /** Labels of the children of the first node whose own label is [label]; empty when no such node exists.
     *  Searches the whole node tree rather than the visible rows, so a test does not depend on how deeply the
     *  module and package levels nest, nor on whether the row is currently expanded. */
    @TestOnly
    fun childLabelsForTest(label: String): List<String> {
        val node = findNodeByLabel(treeRoot, label) ?: return emptyList()
        return (0 until node.childCount).mapNotNull { index ->
            (node.getChildAt(index) as? DefaultMutableTreeNode)?.let { labelOf(it.userObject) }
        }
    }

    /**
     * Selects the node at the given label path, resolved from the tree root downwards: the first label is matched
     * anywhere in the tree (the same whole-tree lookup [childLabelsForTest] uses, so a test need not spell out the
     * module and package levels), each later one against the children of the node found so far. A no-op when the
     * path does not resolve.
     *
     * The selection listener is suppressed for the assignment and [routeSelection] is then called synchronously:
     * that is the very same production routing, only without the thread hop a snapshot's reference lookup would
     * otherwise take, so a test can assert [renderStateForTest] without pumping the event queue — the same
     * synchronous-for-tests seam [reloadSynchronously] is for [reload].
     */
    @TestOnly
    fun selectByLabelPathForTest(vararg labels: String) {
        val first = labels.firstOrNull() ?: return
        var node = findNodeByLabel(treeRoot, first) ?: return
        for (label in labels.drop(1)) {
            node = childByLabel(node, label) ?: return
        }
        restoringSelection = true
        try {
            tree.selectionPath = TreePath(node.path)
        } finally {
            restoringSelection = false
        }
        routeSelection(deferReferenceLookup = false)
    }

    /** The render panel's current state, so selection routing can be asserted without a live render. */
    @get:TestOnly
    val renderStateForTest: RenderState get() = renderPanel.activeState

    /** The no-reference message currently on screen, so its task name can be asserted without a live render. */
    @get:TestOnly
    internal val renderMessageForTest: String?
        get() = renderPanel.messageForTest()

    /** What double-click and Enter do to the current selection, without a mouse or a key event. */
    @TestOnly
    fun navigateToSelectionForTest(): Boolean = navigateToSelection()

    /** Whether [searchField] currently holds a query the tree should filter/expand for. [PreviewSearchFilter]
     *  trims the query before matching, so a single stray space filters nothing — this must agree with that
     *  trimming (`isNotBlank`, not `isNotEmpty`), or a whitespace-only query would force-expand the entire
     *  unfiltered tree and suspend the expansion memory until the space is deleted. The single shared source for
     *  both [applyFilter]'s expansion-capture guard and [applyExpansionPolicy]. */
    private fun isQueryActive(): Boolean = searchField.text.isNotBlank()

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
        // The orphan branch goes through the same module filter as the previews: "show only the active editor's
        // module" that still showed another module's snapshots would not be that filter at all. The query is
        // applied to the two independently, inside the builder (spec D11).
        val visibleOrphans = PreviewModuleFilter.apply(
            orphanSnapshots,
            moduleTracker.activeModuleName,
            moduleFilterOn,
        )
        val modules = PreviewTreeModelBuilder.build(visible, visibleOrphans, searchField.text)
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
            modules.forEach { addModule(treeRoot, it) }
            treeModel.reload()
            applyExpansionPolicy()
            // Recorded right here, immediately after the call that builds the tree now on screen — this must
            // describe THAT tree, not whatever selection/notification bookkeeping happens later in this method,
            // and searchField.text has not changed since (see lastBuildWasQueryDriven's own KDoc for why the
            // NEXT applyFilter call, not this one, is the one that reads it).
            lastBuildWasQueryDriven = isQueryActive()
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

        // Both emptiness tests count the orphan snapshots too (spec D4): a module that snapshots composables it
        // has no `@Preview` for shows orphan rows, and "No @Preview functions found in this project" printed
        // underneath a tree that visibly has rows in it is simply false.
        setState(
            when {
                entries.isEmpty() && orphanSnapshots.isEmpty() -> State.NO_PREVIEWS
                moduleFilterOn && visible.isEmpty() && visibleOrphans.isEmpty() -> State.NO_ACTIVE_MODULE
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
     *
     * Both paths already handle the snapshot children a preview row now carries (spec D4), and differently on
     * purpose. The query path walks *rows*, re-reading `rowCount` as it grows, so it opens every level that
     * survived filtering — snapshots included, which is the point: they are part of the result. The module-level
     * default expands the module rows only, so a plain load reveals a preview's snapshot children not at all;
     * they arrive with the handle the user (or a query) can open, never opened for them.
     */
    private fun applyExpansionPolicy() {
        if (isQueryActive()) {
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
            // This tree is a com.intellij.ui.treeStructure.Tree, whose ExpandImpl.getExpandedDescendants
            // (javap-verified) excludes any expanded path equal to the argument via TreePath.equals — value
            // equality, not reference — so the invisible root's own path (TreePath(treeRoot)) is already excluded
            // by the platform and never reaches labelPathFor here. A bare javax.swing.JTree does compare by
            // reference and would include it, which labelPathFor maps to an empty list (treeRoot itself carries
            // no label); this guard is defensive against that JDK behaviour, not load-bearing for this tree.
            if (labels.isEmpty()) continue
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

    /** The label [visibleRowLabelsForTest] would show for [userObject], or null for an unrecognised node. Covers
     *  every node kind the tree can hold, including the two snapshot ones: the expansion bookkeeping keys rows by
     *  this label, so a kind missing here would be a row whose expansion could not survive a rebuild. */
    private fun labelOf(userObject: Any?): String? = when (userObject) {
        is PreviewNode.ModuleNode -> userObject.segment
        is PreviewNode.PackageBranch -> userObject.segment
        is PreviewNode.PreviewLeaf -> userObject.row.indexed.displayName
        is PreviewNode.SnapshotLeaf -> userObject.row.indexed.functionName
        // Read from the renderer that draws this row rather than repeated here: a label path is only useful if
        // it names the row the user sees.
        is PreviewNode.OrphanSnapshotBranch -> PreviewTreeCellRenderer.ORPHAN_BRANCH_LABEL
        else -> null
    }

    /** Walks the current tree from [treeRoot] following [labels] one level at a time, returning the node at the
     *  end of the path, or null as soon as a label no longer matches any child (the row it named was filtered
     *  out of the rebuilt tree). */
    private fun findNodeByLabelPath(labels: List<String>): DefaultMutableTreeNode? {
        var current = treeRoot
        for (label in labels) {
            current = childByLabel(current, label) ?: return null
        }
        return current
    }

    /** The first child of [node] whose label is [label], or null when it has none. */
    private fun childByLabel(node: DefaultMutableTreeNode, label: String): DefaultMutableTreeNode? {
        for (index in 0 until node.childCount) {
            val child = node.getChildAt(index) as? DefaultMutableTreeNode ?: continue
            if (labelOf(child.userObject) == label) return child
        }
        return null
    }

    /** Depth-first (pre-order) search for the first node anywhere under [node] whose own label is [label].
     *  [treeRoot] itself carries no user object, so it can never match. */
    private fun findNodeByLabel(node: DefaultMutableTreeNode, label: String): DefaultMutableTreeNode? {
        if (labelOf(node.userObject) == label) return node
        for (index in 0 until node.childCount) {
            val child = node.getChildAt(index) as? DefaultMutableTreeNode ?: continue
            findNodeByLabel(child, label)?.let { return it }
        }
        return null
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

    /**
     * Routes whatever the tree has selected: a snapshot row to its reference images, everything else to
     * [pipeline] — where a module, package or orphan-branch row lands as "nothing selected", exactly as before.
     *
     * The snapshot branch hands [pipeline] a null selection rather than the snapshot: a snapshot is never
     * rendered (spec D8), and null is what a switch away from a preview already does — it cancels the debounced
     * dispatch AND bumps the pipeline's generation, so a render still in flight can no longer publish over the
     * strip. Going through the pipeline's own entry point rather than adding a "cancel" API is deliberate: the
     * cancellation semantics then cannot drift from the preview-to-preview switch's.
     *
     * [deferReferenceLookup] is false only for [selectByLabelPathForTest], which needs the routing to have
     * finished by the time it returns — so it calls [resolveReferences] and [decodeReferences] directly, on
     * whatever thread the test runs on, instead of going through [loadReferences]'s background hop and debounce.
     * Both paths now run the very same [resolveReferences] rather than each keeping their own copy of its three
     * steps in sync by hand. Production always defers, keeping the lookup and the PNG decode off the EDT and
     * behind the selection debounce.
     */
    private fun routeSelection(deferReferenceLookup: Boolean) {
        val snapshot = selectedSnapshotEntry()
        if (snapshot != null) {
            lastSelectedEntry = null
            pipeline.select(null)
            if (deferReferenceLookup) {
                showReferenceImages(snapshot)
            } else {
                val located = resolveReferences(snapshot)
                publishReferences(snapshot, decodeReferences(located.images), located.tasks)
            }
            return
        }
        // Anything pending on the reference alarm belongs to a row that is no longer selected: drop it before it
        // starts rather than letting it run and be discarded on arrival by [publishReferences]' guard.
        referenceAlarm.cancelAllRequests()
        val selected = selectedEntry()
        lastSelectedEntry = selected
        pipeline.select(selected)
    }

    /** The selected snapshot row, or null when the selection is anything else — deliberately disjoint from
     *  [selectedEntry], which only ever matches a [PreviewNode.PreviewLeaf], so a snapshot can never be mistaken
     *  for something renderable (spec D8). */
    private fun selectedSnapshotEntry(): PreviewEntry? {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return (node.userObject as? PreviewNode.SnapshotLeaf)?.row as? PreviewEntry
    }

    /**
     * Shows [snapshot]'s committed reference PNGs (spec D6/D7), debounced exactly as a preview selection is.
     *
     * The debounce is the whole point of this method: arrow-keying down a preview's snapshot children fires one
     * selection per row, and locating plus decoding device-resolution PNGs (~1080x2340, ~10 MB decoded, once per
     * variant) for a row the user has already left is pure waste — [publishReferences]' guard only discards the
     * *result*, after the work has run to completion. [RenderPipeline.DEBOUNCE_MS] is shared with the render path
     * deliberately: both are "the user settled on a row", and they should not feel different.
     *
     * Its own alarm, not the search field's: that one is cancelled on every keystroke, and a query typed while a
     * snapshot is selected must not drop the images (nor a selection drop a pending re-filter).
     *
     * `coalesceBy` on the read action would not do this job — every row is a different snapshot id, so nothing
     * would coalesce; keying it on a panel-wide constant would cancel *and* still start each lookup.
     */
    private fun showReferenceImages(snapshot: PreviewEntry) {
        referenceAlarm.cancelAllRequests()
        referenceAlarm.addRequest({ loadReferences(snapshot) }, RenderPipeline.DEBOUNCE_MS)
    }

    /**
     * Runs [resolveReferences] and decodes its images, both on the same background executor, then hands the
     * result to [publishReferences] on the EDT.
     *
     * Decoding stays outside every read action for the reason it always has (`RenderPipeline`'s own class doc
     * calls holding the read lock across long work "a prime freeze suspect"): `ImageIO.read` on two
     * device-resolution PNGs is tens of milliseconds that would otherwise block every write action in the IDE.
     */
    private fun loadReferences(snapshot: PreviewEntry) {
        val modality = ModalityState.defaultModalityState()
        AppExecutorUtil.getAppExecutorService().execute {
            val located = try {
                resolveReferences(snapshot)
            } catch (e: ProcessCanceledException) {
                // The panel is gone, or a write action preempted the lookup. Nothing to publish and nothing to
                // retry: the selection that would want this result is gone with it.
                return@execute
            }
            val decoded = decodeReferences(located.images)
            ApplicationManager.getApplication().invokeLater(
                {
                    if (disposalCheck.isDisposed) return@invokeLater
                    publishReferences(snapshot, decoded, located.tasks)
                },
                modality,
            )
        }
    }

    /**
     * Resolves [snapshot]'s module directory and locates its reference images under read actions, refreshing the
     * reference directories **between** them without one.
     *
     * The three-way split is forced, not stylistic. `ModuleDirectoryResolver` reads the project model and needs
     * the lock; `ReferenceRoots.refresh` is a synchronous VFS refresh, which the platform rejects under one; the
     * listing needs it again.
     *
     * Callable from the EDT as well as a background thread: `executeSynchronously` runs its read action inline
     * when already on the EDT, and the refresh's own restriction is on holding the read lock, not on which
     * thread calls it — which is what lets [routeSelection]'s inline branch call this directly instead of
     * mirroring its steps.
     *
     * Returns empty images and tasks when [snapshot] resolves to no module, or when [disposalCheck] fires
     * between the two read actions: the panel that would show the result is gone, so the refresh and the second
     * read action are both work nothing will use. Lets [ProcessCanceledException] propagate rather than
     * catching it here; only [loadReferences] needs to react to it, and it already does.
     */
    private fun resolveReferences(snapshot: PreviewEntry): LocatedReferences {
        val moduleDirectory = ReadAction.nonBlocking<VirtualFile?> {
            ModuleDirectoryResolver.resolve(project, snapshot.file)
        }
            .expireWith(parentDisposable)
            .executeSynchronously()
            ?: return LocatedReferences(emptyList(), emptyList())
        if (disposalCheck.isDisposed) return LocatedReferences(emptyList(), emptyList())
        ReferenceRoots.refresh(moduleDirectory)
        return ReadAction.nonBlocking<LocatedReferences> { locateReferences(snapshot, moduleDirectory) }
            .expireWith(parentDisposable)
            .executeSynchronously()
    }

    /** What one lookup produced: the images to show, and the Gradle tasks to name when there are none. */
    private data class LocatedReferences(val images: List<ReferenceImage>, val tasks: List<String>)

    /**
     * EDT half of [showReferenceImages]. Dropped unless [snapshot] is *still* the selected row: arrow-keying down
     * a preview's snapshot children starts one decode per row, and a slower earlier one must not land on top of a
     * later selection — nor on top of a live render, if the user has moved back to a preview meanwhile. Re-reading
     * the tree's own selection is the whole guard; no separate generation counter can disagree with it. [tasks]
     * passes straight through to [PreviewRenderPanel.showReference] — it is assembled in [locateReferences], the
     * one place that already knows which roots exist.
     */
    private fun publishReferences(snapshot: PreviewEntry, decoded: DecodedReferences, tasks: List<String>) {
        if (selectedSnapshotEntry()?.id != snapshot.id) return
        renderPanel.showReference(snapshot, decoded.images, decoded.skipped, tasks)
    }

    /**
     * Finds [snapshot]'s committed reference images under [moduleDirectory] (spec D6). **This** is the half that
     * needs a read action: the VFS directory listing, and nothing else.
     *
     * Every discovered root contributes, and the tasks that would regenerate them are collected here rather than
     * in the panel, because this is where the roots are known — the message has to name the module's own
     * variants, not the `Debug` a library module happens to have.
     */
    private fun locateReferences(snapshot: PreviewEntry, moduleDirectory: VirtualFile): LocatedReferences {
        val roots = ReferenceRoots.of(moduleDirectory)
        return LocatedReferences(
            images = ReferenceImageLocator.locate(snapshot, roots),
            tasks = roots.mapNotNull { ReferenceRoots.updateTask(it.variant) }.distinct().sorted(),
        )
    }

    /** Decodes what [locateReferences] found — deliberately holding no read lock (see [loadReferences]); a
     *  `VirtualFile`'s bytes are readable without one, and this is the slow half.
     *
     *  Labels come from [ReferenceImageLocator.labels], whose own KDoc states when one carries its source set. */
    private fun decodeReferences(references: List<ReferenceImage>): DecodedReferences {
        val images = mutableListOf<ReferenceStripView.LabelledImage>()
        val skipped = mutableListOf<String>()
        for ((reference, label) in references.zip(ReferenceImageLocator.labels(references))) {
            val image = readImage(reference.file)
            if (image == null) {
                skipped += label
            } else {
                images += ReferenceStripView.LabelledImage(label, image)
            }
        }
        return DecodedReferences(images, skipped)
    }

    /** null when the PNG cannot be read: `ImageIO.read` returns null for a stream no decoder recognises and
     *  throws for an IO failure. Either way that one variant is skipped and reported, never fatal — the other
     *  variants still show (spec's error-handling table). */
    private fun readImage(file: VirtualFile): BufferedImage? =
        try {
            file.inputStream.use { ImageIO.read(it) }
        } catch (e: IOException) {
            thisLogger().warn("Could not read reference image ${file.path}", e)
            null
        }

    /**
     * Double-click / Enter: opens the selected row's source.
     *
     * Falls back to the selected snapshot row, which [selectedEntry] deliberately never returns. Navigation is
     * not rendering, so this does not touch spec D8 — it is the same `PreviewEntry`, opened in an editor rather
     * than handed to layoutlib — and without it double-click and Enter on a snapshot row silently did nothing.
     * Anything else (a module, package or orphan-branch row) still navigates nowhere.
     */
    private fun navigateToSelection(): Boolean {
        val entry = selectedEntry() ?: selectedSnapshotEntry() ?: return false
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

    /** Builds one module row and everything nested under it: child modules first, then this module's own
     *  package branches, then its own default-package leaves — the same branches-before-leaves ordering
     *  [addBranch] uses one level down, extended one level up so a nested module never reads as if it belonged
     *  to a sibling package branch — and finally its orphan-snapshot branch, if it has one. */
    private fun addModule(parent: DefaultMutableTreeNode, module: PreviewNode.ModuleNode) {
        val node = DefaultMutableTreeNode(module)
        module.modules.forEach { addModule(node, it) }
        module.branches.forEach { addBranch(node, it) }
        module.previews.forEach { addPreview(node, it) }
        // Last, below this module's own previews: an orphan is what did NOT match one of them (spec D4), so it
        // reads as a remainder rather than as a peer of the package branches.
        module.orphans?.let { orphans ->
            val orphanNode = DefaultMutableTreeNode(orphans)
            orphans.snapshots.forEach { orphanNode.add(DefaultMutableTreeNode(it)) }
            node.add(orphanNode)
        }
        parent.add(node)
    }

    private fun addBranch(parent: DefaultMutableTreeNode, branch: PreviewNode.PackageBranch) {
        val node = DefaultMutableTreeNode(branch)
        branch.branches.forEach { addBranch(node, it) }
        branch.previews.forEach { addPreview(node, it) }
        parent.add(node)
    }

    /** One preview row and the snapshots that cover it, hung under it as children (spec D4). Shared by
     *  [addModule] and [addBranch] so a default-package preview carries its snapshots exactly like a packaged one.
     *  A preview with snapshots is therefore no longer a `JTree` leaf: it gains a handle and stays collapsed until
     *  something expands it (the load-time policy expands the module level only). */
    private fun addPreview(parent: DefaultMutableTreeNode, leaf: PreviewNode.PreviewLeaf) {
        val node = DefaultMutableTreeNode(leaf)
        leaf.snapshots.forEach { node.add(DefaultMutableTreeNode(it)) }
        parent.add(node)
    }

    /**
     * Depth-first search for the leaf carrying [entryId]. Runs on every rebuild (selection restore), so it walks
     * children by index rather than materialising a list per level.
     *
     * Matches a [PreviewNode.PreviewLeaf] only — deliberately, even though the tree now also holds snapshot rows
     * that carry a [PreviewEntry] of their own. Selection restore and reveal are both about *previews*: a snapshot
     * selection is simply not restored across a rebuild, exactly as a module or package row's is not.
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

    /** The two halves of one index read, kept together by [loadRows]. */
    private data class LoadedRows(val previews: List<PreviewEntry>, val orphans: List<PreviewEntry>)

    /** What [decodeReferences] found: the variants it could decode, and the ones it could not — reported in the
     *  strip's tooltip rather than dropped silently. */
    private data class DecodedReferences(
        val images: List<ReferenceStripView.LabelledImage>,
        val skipped: List<String>,
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150
    }
}

/**
 * A [DefaultTreeExpander] whose Collapse All keeps both the selected preview and its rendered pane exactly as
 * they were — a collapse that starts and ends on the same selected leaf never lets a selection-change
 * notification escape to [PreviewGalleryPanel]'s own [Tree.addTreeSelectionListener].
 *
 * The platform's own implementation (`collapseAll(JTree, Boolean, Int)`, called by the public no-arg
 * `collapseAll()` with `keepSelectionLevel = 1`) delegates to `TreeUtil.collapseAll(tree, strict = true,
 * keepSelectionLevel = 1)`. Bytecode-verified chain: `Tree$ExpandImpl.collapsePath` calls
 * `removeDescendantSelectedPaths` then `addSelectionPath`, and `TreeUtil.collapseAll` itself finishes with
 * `internalSelect(normalize(lead, …))` once the selected leaf's own path is hidden — with
 * [PreviewGalleryPanel]'s tree having `isRootVisible = false`, that re-anchors the lead selection to the module
 * node. Each of those calls fires its own `TreeSelectionEvent`. Left unsuppressed, the intermediate one clears
 * [PreviewGalleryPanel]'s selection (`selectedEntry()` is null for a module/branch node), which publishes an
 * IDLE render state and tears down every comparison tab; this override's own reselect below then fires a
 * *second* event, restarting the render from scratch. Since the selection ends exactly where it started, no
 * notification should escape at all.
 *
 * This override therefore holds [restoringSelection] true for the entire delegated call (the same flag
 * [PreviewGalleryPanel] already uses to suppress its own restore-driven selection churn), so every intermediate
 * event the platform fires is ignored, then reselects the original leaf without expanding its now-collapsed
 * ancestors (the same `expandsSelectedPaths = false` idiom [PreviewGalleryPanel.selectEntry] uses for its own
 * restore path) — itself also silent, for the same reason. The reselect only applies when the originally
 * selected node is a [PreviewNode.PreviewLeaf]: that is the only case this override reasons about; a selected
 * branch or module node is left to the platform's own re-anchoring. The previewed leaf and its render therefore
 * both survive a Collapse All untouched, while its ancestors stay collapsed exactly as Collapse All intends.
 */
private class SelectionPreservingTreeExpander(
    tree: Tree,
    private val treeRoot: DefaultMutableTreeNode,
    private val restoringSelection: KMutableProperty0<Boolean>,
) : DefaultTreeExpander(tree) {

    override fun collapseAll(tree: JTree, strict: Boolean, keepSelectionLevel: Int) {
        val selectedPath = tree.selectionPath
        restoringSelection.set(true)
        try {
            super.collapseAll(tree, strict, keepSelectionLevel)
            // DefaultMutableTreeNode.getRoot() returns the node itself when it has no parent, so this also
            // rejects a detached leaf rather than only checking for a null path.
            val leaf = selectedPath?.lastPathComponent as? DefaultMutableTreeNode ?: return
            if (leaf.root !== treeRoot) return
            // Only a preview leaf is restored (see class KDoc); anything else is left as the platform anchored it.
            if (leaf.userObject !is PreviewNode.PreviewLeaf) return
            val previousExpandsSelectedPaths = tree.expandsSelectedPaths
            tree.expandsSelectedPaths = false
            try {
                tree.selectionPath = selectedPath
            } finally {
                tree.expandsSelectedPaths = previousExpandsSelectedPaths
            }
        } finally {
            restoringSelection.set(false)
        }
    }
}
