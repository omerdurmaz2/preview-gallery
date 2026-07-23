package com.devomer.previewgallery.index

import com.devomer.previewgallery.model.AnnotationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewAnnotationMatcherTest {

    private val androidxImport = ImportInfo("androidx.compose.ui.tooling.preview.Preview", null, false)
    private val jetbrainsImport = ImportInfo("org.jetbrains.compose.ui.tooling.preview.Preview", null, false)
    private val androidxStar = ImportInfo("androidx.compose.ui.tooling.preview", null, true)
    private val jetbrainsStar = ImportInfo("org.jetbrains.compose.ui.tooling.preview", null, true)

    @Test
    fun `fully qualified androidx reference`() {
        assertEquals(
            AnnotationKind.ANDROIDX,
            PreviewAnnotationMatcher.matchPreview("androidx.compose.ui.tooling.preview.Preview", emptyList()),
        )
    }

    @Test
    fun `fully qualified jetbrains reference`() {
        assertEquals(
            AnnotationKind.JETBRAINS,
            PreviewAnnotationMatcher.matchPreview("org.jetbrains.compose.ui.tooling.preview.Preview", emptyList()),
        )
    }

    @Test
    fun `explicit androidx import`() {
        assertEquals(AnnotationKind.ANDROIDX, PreviewAnnotationMatcher.matchPreview("Preview", listOf(androidxImport)))
    }

    @Test
    fun `explicit jetbrains import`() {
        assertEquals(AnnotationKind.JETBRAINS, PreviewAnnotationMatcher.matchPreview("Preview", listOf(jetbrainsImport)))
    }

    @Test
    fun `aliased import`() {
        val aliased = ImportInfo("androidx.compose.ui.tooling.preview.Preview", "P", false)
        assertEquals(AnnotationKind.ANDROIDX, PreviewAnnotationMatcher.matchPreview("P", listOf(aliased)))
        // The bare name `Preview` is bound to nothing here, which is the design's "no matching import" row.
        assertEquals(AnnotationKind.UNKNOWN, PreviewAnnotationMatcher.matchPreview("Preview", listOf(aliased)))
    }

    @Test
    fun `star import`() {
        assertEquals(AnnotationKind.ANDROIDX, PreviewAnnotationMatcher.matchPreview("Preview", listOf(androidxStar)))
    }

    @Test
    fun `both packages star-imported is ambiguous`() {
        assertEquals(
            AnnotationKind.UNKNOWN,
            PreviewAnnotationMatcher.matchPreview("Preview", listOf(androidxStar, jetbrainsStar)),
        )
    }

    @Test
    fun `short name with no matching import is unknown`() {
        assertEquals(AnnotationKind.UNKNOWN, PreviewAnnotationMatcher.matchPreview("Preview", emptyList()))
    }

    @Test
    fun `an unrelated Preview import is not a compose preview`() {
        val unrelated = ImportInfo("com.example.Preview", null, false)
        assertNull(PreviewAnnotationMatcher.matchPreview("Preview", listOf(unrelated)))
    }

    @Test
    fun `an unrelated annotation is not a preview`() {
        assertNull(PreviewAnnotationMatcher.matchPreview("Composable", listOf(androidxImport)))
    }

    @Test
    fun `preview parameter is detected through its import`() {
        val parameterImport = ImportInfo("androidx.compose.ui.tooling.preview.PreviewParameter", null, false)
        assertTrue(PreviewAnnotationMatcher.isPreviewParameter("PreviewParameter", listOf(parameterImport)))
        assertFalse(PreviewAnnotationMatcher.isPreviewParameter("Preview", listOf(parameterImport)))
    }
}
