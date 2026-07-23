package com.devomer.previewgallery.index

import com.devomer.previewgallery.model.IndexedPreview
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import org.jetbrains.kotlin.psi.KtFile

/**
 * Persistent, incremental index of every directly `@Preview`-annotated function, keyed by its composable FQN.
 *
 * Only file-local facts are stored. Module membership is a project-model property, so storing it here would let a
 * Gradle sync invalidate correctness without invalidating the index.
 *
 * PSI-dependence is declared via [dependsOnFileContent] returning `true`, which is what makes the platform
 * rebuild content (and thus PSI) for this index on every change. `com.intellij.util.indexing.PsiDependentIndex`
 * does not exist in this SDK — see task-5-report.md for how that was confirmed — so it is not implemented here.
 */
class PreviewIndex : FileBasedIndexExtension<String, List<IndexedPreview>>() {

    override fun getName(): ID<String, List<IndexedPreview>> = NAME

    override fun getVersion(): Int = VERSION

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<IndexedPreview>> = PreviewValueExternalizer

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file -> file.extension == KOTLIN_EXTENSION }

    override fun getIndexer(): DataIndexer<String, List<IndexedPreview>, FileContent> =
        DataIndexer { content ->
            if (!content.contentAsText.contains(MARKER)) return@DataIndexer emptyMap()
            val ktFile = content.psiFile as? KtFile ?: return@DataIndexer emptyMap()
            PreviewPsiScanner.scan(ktFile).groupBy { it.composableFqn }
        }

    companion object {
        val NAME: ID<String, List<IndexedPreview>> = ID.create("com.devomer.previewgallery.PreviewIndex")

        /** Bump on any change to [PreviewValueExternalizer] or to what the scanner produces. */
        const val VERSION = 1

        private const val KOTLIN_EXTENSION = "kt"

        /** Cheap text gate: files that never mention Preview are skipped before PSI is built. */
        private const val MARKER = "Preview"
    }
}
