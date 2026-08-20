package com.devomer.previewgallery.editor

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.index.PreviewPsiScanner
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * PG24-3: a **Show all previews** icon in the editor gutter beside every `@Preview`-annotated function, next to
 * Android Studio's own run-preview icon, doing exactly what the preview toolbar's button of the same name does.
 *
 * ## Why a `LineMarkerProvider` and not a `RunLineMarkerContributor`
 *
 * A run-line-marker contributes to the *run* gutter group, where Android Studio's own preview icon lives; a second
 * entry there collapses both into one popup menu that has to be opened before either can be clicked. An ordinary
 * line marker gets its own icon, clickable in one press, which is the point of putting it in the gutter at all.
 *
 * ## The performance contract, which is the whole reason for the guard below
 *
 * The platform calls [getLineMarkerInfo] for **every PSI leaf** in the visible range, and requires the marker to
 * be anchored on a leaf (an info anchored on a composite element is reported as an error by
 * `LineMarkersPass`). So the first thing this does is reject everything that is not a function's own name
 * identifier — three field reads — and only what survives pays for
 * [PreviewPsiScanner.isPreviewFunction]'s import-list walk.
 *
 * Anchoring on the name identifier is also what makes the reveal exact: [com.devomer.previewgallery.model.IndexedPreview.offset]
 * is `function.nameIdentifier.textOffset`, the same position, so [CaretPreviewResolver] resolves this marker to
 * this function and never to the one above it.
 *
 * ## Which functions get one
 *
 * Whatever [PreviewPsiScanner] indexes — a direct `@Preview`, or a bare `@PreviewTest` whose only preview
 * annotation is a custom multipreview. Sharing that check is deliberate: a gutter that marked a different set
 * than the tree lists would be worse than no gutter, because it would be read as the truth about coverage.
 */
class ShowAllPreviewsLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.elementType != KtTokens.IDENTIFIER) return null
        val function = element.parent as? KtNamedFunction ?: return null
        if (function.nameIdentifier !== element) return null
        if (!PreviewPsiScanner.isPreviewFunction(function)) return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Actions.ListFiles,
            { PreviewGalleryBundle.message("action.showAllPreviews.text") },
            { _, clicked -> openGallery(clicked) },
            GutterIconRenderer.Alignment.LEFT,
            { PreviewGalleryBundle.message("action.showAllPreviews.text") },
        )
    }

    /**
     * The click. [clicked] is the very name identifier the marker was anchored on, so its own offset is what the
     * gallery is asked to reveal — the caret may be anywhere else in the file, and using it would open the
     * gallery on whichever preview the caret happened to sit under instead of the one whose icon was pressed.
     *
     * The editor handed to [PreviewGalleryNavigator] is the selected one for this project rather than the click's:
     * a gutter belongs to a plain text editor, while the pane that has to collapse is the `SplitEditor` wrapping
     * it, which is what `FileEditorManager.selectedEditor` returns. A null (no selected editor, or one that is not
     * a [TextEditor]) skips the collapse and still opens the gallery — the same independence design D7 already
     * requires of the toolbar route.
     */
    private fun openGallery(clicked: PsiElement) {
        val project = clicked.project
        val file = clicked.containingFile?.virtualFile
        val editor = FileEditorManager.getInstance(project).selectedEditor as? TextEditor
        PreviewGalleryNavigator.openAt(project, editor, file, clicked.textOffset)
    }
}
