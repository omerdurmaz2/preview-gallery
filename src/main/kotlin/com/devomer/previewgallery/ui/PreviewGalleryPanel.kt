package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.service.PreviewIndexService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
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

    enum class State { INDEXING, NO_PREVIEWS, NO_MATCH, LOADED }

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

    /** Set by [PreviewGalleryToolWindowFactory]; consumed by the module filter action in Task 10. */
    var moduleFilter: (List<PreviewEntry>) -> List<PreviewEntry> = { it }

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = PreviewTreeCellRenderer()
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.addTreeSelectionListener { detailPanel.show(selectedEntry()) }

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
            secondComponent = PreviewRenderPlaceholder()
        }

        statusLabel.border = JBUI.Borders.empty(8)
        add(outer, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        reload()
    }

    /** Reloads the index off the EDT. Safe to call repeatedly. */
    fun reload() {
        if (DumbService.isDumb(project)) {
            setState(State.INDEXING)
            DumbService.getInstance(project).runWhenSmart { reload() }
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

    fun selectEntry(entryId: String) {
        val path = findPath(entryId) ?: return
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun applyFilter() {
        val visible = moduleFilter(entries)
        val modules = PreviewTreeModelBuilder.build(visible, searchField.text)
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
        detailPanel.show(selectedEntry())

        setState(
            when {
                entries.isEmpty() -> State.NO_PREVIEWS
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
