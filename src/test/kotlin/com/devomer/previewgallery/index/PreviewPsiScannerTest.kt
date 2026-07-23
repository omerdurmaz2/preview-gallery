package com.devomer.previewgallery.index

import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.IndexedPreview
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtFile

class PreviewPsiScannerTest : BasePlatformTestCase() {

    private fun scan(fileName: String, text: String): List<IndexedPreview> {
        val file = myFixture.configureByText(fileName, text) as KtFile
        return PreviewPsiScanner.scan(file)
    }

    fun `test androidx top-level preview`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals(1, previews.size)
        val preview = previews.single()
        assertEquals("BarPreview", preview.functionName)
        assertEquals("BarPreview", preview.displayName)
        assertEquals("com.example", preview.packageName)
        assertEquals("com.example.FooKt", preview.jvmClassName)
        assertEquals("com.example.FooKt.BarPreview", preview.composableFqn)
        assertEquals(AnnotationKind.ANDROIDX, preview.annotationKind)
        assertFalse(preview.isPrivate)
        assertFalse(preview.hasPreviewParameter)
        assertNull(preview.previewGroup)
        assertNull(preview.unsupportedReason)
    }

    fun `test jetbrains preview`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import org.jetbrains.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals(AnnotationKind.JETBRAINS, previews.single().annotationKind)
    }

    fun `test private preview is indexed and flagged`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            private fun BarPreview() {}
            """.trimIndent(),
        )
        assertTrue(previews.single().isPrivate)
    }

    fun `test name and group arguments`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview(name = "Dark tab", group = "Tabs")
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals("Dark tab", previews.single().displayName)
        assertEquals("Tabs", previews.single().previewGroup)
    }

    fun `test positional name argument`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview("Positional")
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals("Positional", previews.single().displayName)
    }

    fun `test non-literal name falls back to the function name`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            const val NAME = "Constant"

            @Preview(name = NAME)
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals("BarPreview", previews.single().displayName)
    }

    fun `test JvmName renames the facade`() {
        val previews = scan(
            "Foo.kt",
            """
            @file:JvmName("Custom")

            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals("com.example.Custom.BarPreview", previews.single().composableFqn)
    }

    fun `test object member uses the object name`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            object Previews {
                @Preview
                fun BarPreview() {}
            }
            """.trimIndent(),
        )
        assertEquals("com.example.Previews.BarPreview", previews.single().composableFqn)
        assertNull(previews.single().unsupportedReason)
    }

    fun `test class member is indexed but unsupported`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            class Holder {
                @Preview
                fun BarPreview() {}
            }
            """.trimIndent(),
        )
        assertEquals(1, previews.size)
        assertEquals("declared inside a class", previews.single().unsupportedReason)
    }

    fun `test PreviewParameter is flagged`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview
            import androidx.compose.ui.tooling.preview.PreviewParameter

            @Preview
            fun BarPreview(@PreviewParameter(Provider::class) value: String) {}
            """.trimIndent(),
        )
        assertTrue(previews.single().hasPreviewParameter)
    }

    fun `test aliased import`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview as P

            @P
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals(AnnotationKind.ANDROIDX, previews.single().annotationKind)
    }

    fun `test both packages star-imported yields UNKNOWN`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.*
            import org.jetbrains.compose.ui.tooling.preview.*

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals(AnnotationKind.UNKNOWN, previews.single().annotationKind)
    }

    fun `test composable without preview is ignored`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.runtime.Composable

            @Composable
            fun Bar() {}
            """.trimIndent(),
        )
        assertTrue(previews.isEmpty())
    }

    fun `test multipreview wrapper is not resolved in v1`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview(name = "light")
            @Preview(name = "dark")
            annotation class ThemePreview

            @ThemePreview
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertTrue(previews.none { it.functionName == "BarPreview" })
    }

    fun `test offset points at the function name`() {
        val text = """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
        """.trimIndent()
        val previews = scan("Foo.kt", text)
        assertEquals(text.indexOf("BarPreview"), previews.single().offset)
    }
}
