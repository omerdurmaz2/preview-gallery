package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.search.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewDetailModelTest {

    private fun valueOf(fields: List<DetailField>, label: String): String? =
        fields.firstOrNull { it.label == label }?.value

    @Test
    fun `reports identity fields`() {
        val row = testRow(displayName = "Dark tab", functionName = "TabsPreview", moduleName = "design")
        val fields = PreviewDetailModel.fields(row, fileName = "Tabs.kt", line = 41)

        assertEquals("Dark tab", valueOf(fields, "Name"))
        assertEquals("com.example.FooKt.TabsPreview", valueOf(fields, "FQN"))
        assertEquals("design", valueOf(fields, "Module"))
        assertEquals("Tabs.kt:42", valueOf(fields, "File"))
    }

    @Test
    fun `a null line omits the line suffix`() {
        val fields = PreviewDetailModel.fields(testRow(), fileName = "Tabs.kt", line = null)
        assertEquals("Tabs.kt", valueOf(fields, "File"))
    }

    @Test
    fun `reports the annotation kind`() {
        val row = testRow()
        val jetbrains = row.copy(indexed = row.indexed.copy(annotationKind = AnnotationKind.JETBRAINS))
        assertEquals("org.jetbrains", valueOf(PreviewDetailModel.fields(jetbrains, "Foo.kt", null), "Annotation"))
        assertEquals("androidx", valueOf(PreviewDetailModel.fields(row, "Foo.kt", null), "Annotation"))
    }

    @Test
    fun `optional fields appear only when set`() {
        val row = testRow()
        assertNull(valueOf(PreviewDetailModel.fields(row, "Foo.kt", null), "Group"))
        assertNull(valueOf(PreviewDetailModel.fields(row, "Foo.kt", null), "Unsupported"))

        val decorated = row.copy(
            indexed = row.indexed.copy(
                previewGroup = "Tabs",
                unsupportedReason = "declared inside a class",
                isPrivate = true,
                hasPreviewParameter = true,
            ),
        )
        val fields = PreviewDetailModel.fields(decorated, "Foo.kt", null)
        assertEquals("Tabs", valueOf(fields, "Group"))
        assertEquals("declared inside a class", valueOf(fields, "Unsupported"))
        assertEquals("yes", valueOf(fields, "Private"))
        assertEquals("yes", valueOf(fields, "PreviewParameter"))
    }
}
