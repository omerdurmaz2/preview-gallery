package com.devomer.previewgallery.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager

/** Tracks the module of the file currently open in the editor. */
class ActiveModuleTracker(
    private val project: Project,
    parentDisposable: Disposable,
    private val onChange: () -> Unit,
) {

    val activeModuleName: String?
        get() {
            val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return null
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
            return ModuleUtilCore.findModuleForPsiElement(psiFile)?.name
        }

    init {
        project.messageBus.connect(parentDisposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = onChange()
            },
        )
    }
}
