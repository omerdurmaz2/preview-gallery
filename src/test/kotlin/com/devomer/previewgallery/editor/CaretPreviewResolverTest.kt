package com.devomer.previewgallery.editor

import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.IndexedPreview
import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaretPreviewResolverTest {

    // LightVirtualFile is a concrete VirtualFile needing no fixture — the same trick SourceFileDisambiguatorTest
    // uses, since this project wires no mocking framework.
    private val fooFile: VirtualFile = LightVirtualFile("Foo.kt")
    private val barFile: VirtualFile = LightVirtualFile("Bar.kt")

    private fun entry(name: String, offset: Int, file: VirtualFile = fooFile) = PreviewEntry(
        IndexedPreview(
            displayName = name,
            functionName = name,
            packageName = "com.example",
            jvmClassName = "com.example.FooKt",
            composableFqn = "com.example.$name",
            offset = offset,
            annotationKind = AnnotationKind.ANDROIDX,
            isPrivate = false,
            hasPreviewParameter = false,
            previewGroup = null,
            unsupportedReason = null,
        ),
        moduleName = "app",
        file = file,
    )

    @Test fun `a caret inside the second preview resolves to the second preview`() {
        val first = entry("First", 100)
        val second = entry("Second", 300)
        assertEquals(second, CaretPreviewResolver.resolve(listOf(first, second), fooFile, 350))
    }

    @Test fun `a caret exactly on a preview offset resolves to that preview`() {
        val first = entry("First", 100)
        val second = entry("Second", 300)
        assertEquals(second, CaretPreviewResolver.resolve(listOf(first, second), fooFile, 300))
    }

    @Test fun `a caret above every preview falls back to the first preview in the file`() {
        val first = entry("First", 100)
        val second = entry("Second", 300)
        assertEquals(first, CaretPreviewResolver.resolve(listOf(second, first), fooFile, 10))
    }

    @Test fun `entries from other files are ignored`() {
        val other = entry("Other", 10, barFile)
        val mine = entry("Mine", 500)
        assertEquals(mine, CaretPreviewResolver.resolve(listOf(other, mine), fooFile, 900))
    }

    @Test fun `a file with no previews resolves to null`() {
        assertNull(CaretPreviewResolver.resolve(listOf(entry("Other", 10, barFile)), fooFile, 900))
    }

    @Test fun `an empty index resolves to null`() {
        assertNull(CaretPreviewResolver.resolve(emptyList(), fooFile, 0))
    }
}
