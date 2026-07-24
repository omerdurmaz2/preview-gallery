package com.devomer.previewgallery.render

import com.devomer.previewgallery.index.PreviewAnnotationMatcher
import com.devomer.previewgallery.index.ImportInfo
import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Finds the `@Preview` annotation element a [PreviewEntry] was indexed from, so the picker can be pointed at it.
 *
 * The index stores an offset into the file rather than a PSI element (a PSI element could not survive in an
 * index), so the element is re-resolved here. Call under a read action.
 */
object PreviewAnnotationLocator {

    fun findPreviewAnnotation(project: Project, entry: PreviewEntry): KtAnnotationEntry? {
        val ktFile = PsiManager.getInstance(project).findFile(entry.file) as? KtFile ?: return null
        val function = ktFile.findElementAt(entry.indexed.offset)?.parentOfType<KtNamedFunction>() ?: return null
        val imports = ktFile.importDirectives.mapNotNull { directive ->
            val fqn = directive.importedFqName?.asString() ?: return@mapNotNull null
            ImportInfo(fqn, directive.aliasName, directive.isAllUnder)
        }
        return function.annotationEntries.firstOrNull { annotation ->
            val reference = annotation.typeReference?.text?.substringBefore('<')?.trim() ?: return@firstOrNull false
            PreviewAnnotationMatcher.matchPreview(reference, imports) != null
        }
    }
}
