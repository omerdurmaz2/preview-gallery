package com.devomer.previewgallery.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSelectorTest {

    private val alpha = ProjectSnapshot("alpha", "/src/alpha", indexing = false)
    private val beta = ProjectSnapshot("beta", "/src/beta", indexing = false)

    @Test
    fun `one open project resolves without the argument`() {
        val result = ProjectSelector.select(listOf(alpha), null)

        assertEquals(ProjectSelector.SelectionResult.Found(alpha), result)
    }

    @Test
    fun `two open projects and no argument is an error naming both`() {
        val result = ProjectSelector.select(listOf(alpha, beta), null)

        val message = (result as ProjectSelector.SelectionResult.Failure).message
        assertTrue(message, message.contains("alpha") && message.contains("beta"))
    }

    @Test
    fun `a name resolves`() {
        assertEquals(
            ProjectSelector.SelectionResult.Found(beta),
            ProjectSelector.select(listOf(alpha, beta), "beta"),
        )
    }

    @Test
    fun `a path resolves when no name matches`() {
        assertEquals(
            ProjectSelector.SelectionResult.Found(beta),
            ProjectSelector.select(listOf(alpha, beta), "/src/beta"),
        )
    }

    @Test
    fun `two projects sharing a name are disambiguated by path`() {
        val other = ProjectSnapshot("alpha", "/elsewhere/alpha", indexing = false)

        val ambiguous = ProjectSelector.select(listOf(alpha, other), "alpha")
        val resolved = ProjectSelector.select(listOf(alpha, other), "/elsewhere/alpha")

        val message = (ambiguous as ProjectSelector.SelectionResult.Failure).message
        assertTrue(message, message.contains("/src/alpha") && message.contains("/elsewhere/alpha"))
        assertEquals(ProjectSelector.SelectionResult.Found(other), resolved)
    }

    @Test
    fun `an unknown name lists what is open`() {
        val result = ProjectSelector.select(listOf(alpha), "gamma")

        val message = (result as ProjectSelector.SelectionResult.Failure).message
        assertTrue(message, message.contains("gamma") && message.contains("alpha"))
    }

    @Test
    fun `no open project says so`() {
        assertTrue(ProjectSelector.select(emptyList(), null) is ProjectSelector.SelectionResult.Failure)
    }
}
