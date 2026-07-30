package com.devomer.previewgallery.index

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

class TargetExtractorTest : BasePlatformTestCase() {

    private fun extract(body: String): List<String> {
        val file = myFixture.configureByText(
            "Snapshots.kt",
            """
            package com.example

            fun Subject() $body
            """.trimIndent(),
        ) as KtFile
        val function = file.declarations.filterIsInstance<KtNamedFunction>().single()
        return TargetExtractor.extract(function)
    }

    fun `test wrapper chain yields the innermost call`() {
        assertEquals(
            listOf("ErrorRetryRow"),
            extract("= PreviewComponent { PrimusTheme { ErrorRetryRow(onRetry = {}) } }"),
        )
    }

    fun `test block body is descended the same way`() {
        assertEquals(
            listOf("ErrorRetryRow"),
            extract("{ PreviewComponent { PrimusTheme { ErrorRetryRow() } } }"),
        )
    }

    fun `test argument lists are not entered`() {
        assertEquals(
            listOf("FavoritesContent"),
            extract("= PreviewComponent { PrimusTheme { FavoritesContent(state = FakeState(), onAction = {}) } }"),
        )
    }

    fun `test several calls in the innermost lambda are all targets`() {
        assertEquals(
            listOf("Header", "Row"),
            extract("= PreviewComponent { PrimusTheme { Column { Header(); Row() } } }"),
        )
    }

    fun `test camel case callees are ignored`() {
        assertEquals(
            listOf("ListRowRenderer"),
            extract("= PreviewComponent { PrimusTheme { fakeScope { ListRowRenderer() } } }"),
        )
    }

    fun `test a body with no call yields nothing`() {
        assertEquals(emptyList<String>(), extract("{ }"))
    }

    fun `test duplicate calls are reported once`() {
        assertEquals(
            listOf("LiteProductCard"),
            extract("= PreviewComponent { Column { LiteProductCard(); LiteProductCard() } }"),
        )
    }
}
