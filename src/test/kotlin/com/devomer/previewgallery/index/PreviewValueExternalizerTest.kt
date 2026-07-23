package com.devomer.previewgallery.index

import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.IndexedPreview
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class PreviewValueExternalizerTest {

    private fun roundTrip(values: List<IndexedPreview>): List<IndexedPreview> {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { PreviewValueExternalizer.save(it, values) }
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { PreviewValueExternalizer.read(it) }
    }

    private fun preview(name: String) = IndexedPreview(
        displayName = name,
        functionName = name,
        packageName = "com.example",
        jvmClassName = "com.example.FooKt",
        composableFqn = "com.example.FooKt.$name",
        offset = 42,
        annotationKind = AnnotationKind.JETBRAINS,
        isPrivate = true,
        hasPreviewParameter = true,
        previewGroup = "Tabs",
        unsupportedReason = "declared inside a class",
    )

    @Test
    fun `round trips a populated entry`() {
        val values = listOf(preview("BarPreview"))
        assertEquals(values, roundTrip(values))
    }

    @Test
    fun `round trips null fields`() {
        val values = listOf(preview("BarPreview").copy(previewGroup = null, unsupportedReason = null))
        assertEquals(values, roundTrip(values))
    }

    @Test
    fun `round trips several entries`() {
        val values = listOf(preview("One"), preview("Two"), preview("Three"))
        assertEquals(values, roundTrip(values))
    }

    @Test
    fun `round trips an empty list`() {
        assertEquals(emptyList<IndexedPreview>(), roundTrip(emptyList()))
    }

    @Test
    fun `round trips non-ascii text`() {
        val values = listOf(preview("BarPreview").copy(displayName = "Ödeme ekranı"))
        assertEquals(values, roundTrip(values))
    }
}
