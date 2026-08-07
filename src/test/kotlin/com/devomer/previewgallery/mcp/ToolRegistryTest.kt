package com.devomer.previewgallery.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private val ready = ProjectSnapshot("demo", "/src", indexing = false)
    private val building = ProjectSnapshot("busy", "/busy", indexing = true)

    private fun registry(vararg snapshots: ProjectSnapshot) = ToolRegistry { snapshots.toList() }

    @Test
    fun `every tool is advertised with a schema`() {
        val descriptors = registry(ready).descriptors()

        assertEquals(
            listOf("list_projects", "list_previews", "list_snapshots", "coverage_report"),
            descriptors.map { it.name },
        )
        assertTrue(descriptors.all { it.description.isNotBlank() })
        assertTrue(descriptors.all { "type" in it.inputSchema })
    }

    @Test
    fun `list_projects answers with no project open`() {
        val outcome = registry().call("list_projects", JsonObject(emptyMap()))

        assertEquals(ToolOutcome.Text("[]"), outcome)
    }

    @Test
    fun `a tool refuses while the index is building`() {
        val outcome = registry(building).call("list_previews", JsonObject(emptyMap()))

        val message = (outcome as ToolOutcome.Failure).message
        assertTrue(message, message.contains("busy") && message.contains("indexing"))
    }

    @Test
    fun `list_projects answers even while the index is building`() {
        val outcome = registry(building).call("list_projects", JsonObject(emptyMap()))

        assertTrue(outcome is ToolOutcome.Text)
    }

    @Test
    fun `an ambiguous project is a failure, not a guess`() {
        val outcome = registry(ready, building).call("list_previews", JsonObject(emptyMap()))

        assertTrue(outcome is ToolOutcome.Failure)
    }

    @Test
    fun `the project argument selects`() {
        val outcome = registry(ready, building).call(
            "list_previews",
            buildJsonObject { put("project", "demo") },
        )

        assertEquals(ToolOutcome.Text("[]"), outcome)
    }

    @Test
    fun `an unknown tool is reported as unroutable, not as a tool that failed`() {
        val outcome = registry(ready).call("delete_everything", JsonObject(emptyMap()))

        assertEquals(ToolOutcome.UnknownTool("delete_everything"), outcome)
    }

    @Test
    fun `a wrong-typed argument is treated as absent rather than coerced`() {
        val outcome = registry(ready).call(
            "list_previews",
            buildJsonObject { put("module", 42) },
        )

        // Coercing 42 to "42" would filter to nothing and answer "[]", which an agent reads as "no previews
        // in that module" — the same false negative the indexing refusal exists to prevent.
        assertEquals(ToolOutcome.Text("[]"), outcome)
    }
}
