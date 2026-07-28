package com.devomer.previewgallery.editor

import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// LightVirtualFile is a concrete VirtualFile needing no fixture — the same trick CaretPreviewResolverTest uses,
// since handlesFile only looks at the file's extension.
class PreviewToolbarInjectorTest {

    @Test fun `a Kotlin file is handled`() {
        assertTrue(PreviewToolbarInjector.handlesFile(LightVirtualFile("Foo.kt")))
    }

    @Test fun `an XML file is not handled`() {
        assertFalse(PreviewToolbarInjector.handlesFile(LightVirtualFile("activity_main.xml")))
    }

    @Test fun `a Java file is not handled`() {
        assertFalse(PreviewToolbarInjector.handlesFile(LightVirtualFile("Foo.java")))
    }
}
