package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.render.BuildService
import com.devomer.previewgallery.render.LiveRenderer
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
    private val detailPanel = PreviewDetailPanel(project)
    private val statusLabel = com.intellij.ui.components.JBLabel()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    private var entries: List<PreviewEntry> = emptyList()
    private var lastSelectedEntry: PreviewEntry? = null

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

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = PreviewTreeCellRenderer()
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.addTreeSelectionListener {
            if (restoringSelection) return@addTreeSelectionListener
            val selected = selectedEntry()
            detailPanel.show(selected)
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
        val upper = OnePixelSplitter(false, "PreviewGallery.horizontal", 0.55f).apply {
            firstComponent = treeSide
            secondComponent = JBScrollPane(detailPanel)
        }
        val outer = OnePixelSplitter(true, "PreviewGallery.vertical", 0.6f).apply {
            firstComponent = upper
            secondComponent = renderPanel
        }

        renderPanel.onRender = { pipeline.requestBuildAndRender(it) }
        renderPanel.onOpenFile = { OpenFileDescriptor(project, it.file, it.indexed.offset).navigate(true) }

        val actionGroup = DefaultActionGroup(
            RefreshAction(project) { reload() },
            ModuleFilterToggleAction(project) { applyFilter() },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("PreviewGallery", actionGroup, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)

        statusLabel.border = JBUI.Borders.empty(8)
        add(outer, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        reload()
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
            // No-op if the previously selected entry was filtered out; selection then stays empty.
            if (previousSelectionId != null) selectEntry(previousSelectionId)
        } finally {
            restoringSelection = false
        }

        val currentSelection = selectedEntry()
        detailPanel.show(currentSelection)
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
