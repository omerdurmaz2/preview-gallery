package com.devomer.previewgallery.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private val preview = PreviewFacts(
        composableFqn = "com.example.FooKt.FooPreview",
        displayName = "FooPreview",
        functionName = "FooPreview",
        moduleName = "app.main",
        packageName = "com.example",
        file = "/src/Foo.kt",
        line = 12,
        isPrivate = false,
        hasPreviewParameter = false,
        unsupportedReason = null,
        covered = false,
    )
    private val ready = ProjectSnapshot("demo", "/src", indexing = false, previews = listOf(preview))
    private val building = ProjectSnapshot("busy", "/busy", indexing = true)

    private fun registry(vararg snapshots: ProjectSnapshot) = ToolRegistry({ snapshots.toList() }, { emptyList() })

    @Test
    fun `every tool is advertised with a schema`() {
        val descriptors = registry(ready).descriptors()

        assertEquals(
            listOf("list_projects", "list_previews", "list_snapshots", "coverage_report", "snapshot_health"),
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

        val text = (outcome as ToolOutcome.Text).text
        assertTrue(text, text.contains("FooPreview"))
    }

    @Test
    fun `an unknown tool is reported as unroutable, not as a tool that failed`() {
        val outcome = registry(ready).call("delete_everything", JsonObject(emptyMap()))

        assertEquals(ToolOutcome.UnknownTool("delete_everything"), outcome)
    }

    @Test
    fun `a wrong-typed argument is refused rather than silently dropped`() {
        val outcome = registry(ready).call(
            "list_previews",
            buildJsonObject { put("module", 42) },
        )

        // Coercing 42 to "42" would filter to nothing and answer "[]" — the same false negative the indexing
        // refusal exists to prevent, just triggered by a type mismatch instead of a busy index. `ready` carries
        // a real preview row so a silently-absent filter would answer "[]" here too, and the assertion would
        // not have caught it.
        val message = (outcome as ToolOutcome.Failure).message
        assertTrue(message, message.contains("module") && message.contains("string") && message.contains("number"))
    }

    @Test
    fun `a blank string argument is treated as absent, not refused`() {
        val outcome = registry(ready).call(
            "list_previews",
            buildJsonObject { put("module", "  ") },
        )

        val text = (outcome as ToolOutcome.Text).text
        assertTrue(text, text.contains("FooPreview"))
    }

    @Test
    fun `a wrong-typed boolean argument is refused rather than silently dropped`() {
        val outcome = registry(ready).call(
            "list_previews",
            buildJsonObject { put("uncoveredOnly", "true") },
        )

        val message = (outcome as ToolOutcome.Failure).message
        assertTrue(message, message.contains("uncoveredOnly") && message.contains("boolean") && message.contains("string"))
    }
}
