package com.devomer.previewgallery.editor

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtFile

/**
 * Which editor lines get the gutter icon (PG24-3), and — just as important — which do not: the platform calls
 * [ShowAllPreviewsLineMarkerProvider.getLineMarkerInfo] for every PSI leaf in the visible range, so a provider
 * that answers for the wrong kind of element either litters the gutter or costs a file walk per token.
 *
 * The click itself is not covered here: it opens a tool window and reveals a tree row, which needs the whole
 * gallery. That path is shared verbatim with [ShowAllPreviewsAction] through [PreviewGalleryNavigator], which is
 * why sharing it was worth doing — the toolbar route has been exercised at the gate since PG7.
 */
class ShowAllPreviewsLineMarkerProviderTest : BasePlatformTestCase() {

    private val provider = ShowAllPreviewsLineMarkerProvider()

    /** Every leaf of the configured file, which is exactly what the platform feeds the provider. */
    private fun leaves(fileName: String, text: String): List<PsiElement> {
        val file = myFixture.configureByText(fileName, text) as KtFile
        return PsiTreeUtil.collectElements(file) { it.firstChild == null }.toList()
    }

    private fun markedFunctionNames(fileName: String, text: String): List<String> =
        leaves(fileName, text).mapNotNull { leaf ->
            provider.getLineMarkerInfo(leaf)?.let { leaf.text }
        }

    fun `test a preview function is marked, on its own name identifier`() {
        val marked = markedFunctionNames(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )

        assertEquals(listOf("BarPreview"), marked)
    }

    fun `test a function with no preview annotation is not marked`() {
        val marked = markedFunctionNames(
            "Foo.kt",
            """
            package com.example

            fun NotAPreview() {}
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), marked)
    }

    /** The gutter must mark what the tree lists, and the tree lists a `@PreviewTest` function whose only preview
     *  annotation is a custom multipreview the indexer cannot resolve. */
    fun `test a snapshot test function is marked even with no direct Preview`() {
        val marked = markedFunctionNames(
            "FooSnapshots.kt",
            """
            package com.example

            import com.android.tools.screenshot.PreviewTest

            @PreviewTest
            @SnapshotPreviews
            fun BarSnapshot() {}
            """.trimIndent(),
        )

        assertEquals(listOf("BarSnapshot"), marked)
    }

    fun `test only one leaf in the whole file carries the marker`() {
        val text = """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {
                val greeting = "BarPreview"
                println(greeting)
            }
        """.trimIndent()

        val marked = leaves("Foo.kt", text).mapNotNull { provider.getLineMarkerInfo(it) }

        assertEquals(1, marked.size)
    }

    /** The marker must sit on a leaf: `LineMarkersPass` reports an info anchored on a composite element as an
     *  error, and the anchor's offset is also what the click reveals. */
    fun `test the marked element is a leaf whose offset is the function's indexed offset`() {
        val file = myFixture.configureByText(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        ) as KtFile
        val indexed = com.devomer.previewgallery.index.PreviewPsiScanner.scan(file).single()

        val marked = PsiTreeUtil.collectElements(file) { it.firstChild == null }
            .single { provider.getLineMarkerInfo(it) != null }

        assertEquals(indexed.offset, marked.textOffset)
    }
}
