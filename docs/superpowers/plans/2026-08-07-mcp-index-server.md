# MCP Index Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve the gallery's preview and snapshot index read-only over MCP, so an agent can ask which composables have no snapshot instead of grepping for it.

**Architecture:** A pure `mcp/` package — protocol, tools and an IDE-free `ProjectSnapshot` — with one application service on the IDE side that maps `PreviewEntry` into that snapshot and owns the socket. Transport is the JDK's own `HttpServer`; nothing new is added to the build. The tools compute nothing: every fact already exists in `PreviewIndexService`.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install at `platformLocalPath`) · JDK `com.sun.net.httpserver` · platform-bundled `kotlinx-serialization-json` runtime API · JUnit 4 · `BasePlatformTestCase`

**Spec:** [2026-08-07-mcp-index-server-design.md](../specs/2026-08-07-mcp-index-server-design.md)

## Global Constraints

- **Never use the Kotlin `!!` operator.** Use `?:`, `?.let`, `requireNotNull` or an explicit null check.
- **All source, comments, docs and test names in English.**
- **Do not add explanatory line comments inside function bodies**, except for a decision a reader would otherwise undo. KDoc on new public declarations is expected — this codebase's KDoc documents *why*, not *what*.
- **Tests come last.** Tasks 1–7 are implementation only. Every test in this feature is written in the **Test & Review** phase at the end, then the code review runs. Do not write a test earlier, and do not skip the phase.
- Commit message pattern: `[PG17-N] - Task name` (`PG17-0` is the design spec, `PG17-1` is this plan, tasks are `PG17-2` … `PG17-8`, and the final phase is `PG17-9`).
- Commit trailer on every commit: `Co-Authored-By: Claude MODEL <noreply@anthropic.com>`, where `MODEL` is replaced by the model named in **your own** system prompt, with no brackets — e.g. `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`. Never copy another task's value.
- **Nothing under `mcp/` may import `com.intellij`.** That package is the pure half and is tested with plain JUnit. `ProjectSnapshot` is the only type crossing the boundary, and it holds strings and paths, never `VirtualFile` or `Project`.
- **No tool writes anything** — no file creation, no edit, no Gradle invocation (spec Non-Goals). A tool that mutates is a plan violation, not a feature.
- `PreviewIndexService`, `SnapshotCoverageResolver`, `CoverageReport`, `PreviewIndexService`'s cache, and every rendering component are **not** modified. `CoverageReport.markdown` is called, never rewritten.
- **Verified against this SDK** (do not substitute unverified values):
  - `kotlinx.serialization.json.Json`, `buildJsonObject`, `parseToJsonElement`, `jsonObject`, `put` — compile against the platform's bundled `module-intellij.libraries.kotlinx.serialization.json.jar`. **Confirmed by compiling a probe file on 2026-08-07.** No Gradle dependency and no Kotlin serialization compiler plugin is added; `@Serializable` is therefore unavailable — build JSON with `buildJsonObject` / `buildJsonArray`.
  - `com.sun.net.httpserver.HttpServer` — on the compile classpath, same probe.
  - `AllIcons.General.Web`, `AllIcons.Actions.Lightning`, `AllIcons.Nodes.Plugin` — all present (`javap` against `Android Studio.app/Contents/lib/app.jar`). This plan uses `AllIcons.General.Web`.
- **Build/test command:** `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`. The plain `./gradlew test` serves stale cached test bytecode after a signature change and `clean` alone does not fix it; `--max-workers=1 --no-parallel` is required because `:instrumentCode` and `:instrumentTestCode` race under parallel execution. During tasks 1–7 use `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel` — there are no tests to run yet.
- **Never run `./gradlew` while a `runIde` sandbox is live.** Check with exactly `pgrep -f "idea.plugin.in.sandbox.mode=true"` and `pgrep -f "gradlew.*runIde"` before every Gradle invocation; if either prints a pid, stop and report. Do **not** run `./gradlew runIde` — the human runs that gate.
- Baseline before Task 1: **400 tests / 55 classes**, 0 failures.

---

## File Structure

**Create**

| File | Responsibility |
|---|---|
| `src/main/kotlin/com/devomer/previewgallery/mcp/ProjectSnapshot.kt` | The IDE-free facts one open project contributes |
| `src/main/kotlin/com/devomer/previewgallery/mcp/ProjectSelector.kt` | Resolve the `project` argument to one snapshot, or an error naming the choices |
| `src/main/kotlin/com/devomer/previewgallery/mcp/tools/ListProjectsTool.kt` | `list_projects` |
| `src/main/kotlin/com/devomer/previewgallery/mcp/tools/ListPreviewsTool.kt` | `list_previews` |
| `src/main/kotlin/com/devomer/previewgallery/mcp/tools/ListSnapshotsTool.kt` | `list_snapshots` |
| `src/main/kotlin/com/devomer/previewgallery/mcp/tools/CoverageReportTool.kt` | `coverage_report`, delegating to `CoverageReport.markdown` |
| `src/main/kotlin/com/devomer/previewgallery/mcp/ToolRegistry.kt` | Descriptors + name → execute, and the indexing guard |
| `src/main/kotlin/com/devomer/previewgallery/mcp/McpDispatcher.kt` | JSON-RPC 2.0 router, pure |
| `src/main/kotlin/com/devomer/previewgallery/mcp/McpHttpServer.kt` | JDK `HttpServer`: `POST /mcp`, `GET /health`, loopback, `Origin` guard |
| `src/main/kotlin/com/devomer/previewgallery/service/McpServerService.kt` | Application service: owns the server, maps `PreviewEntry` → `ProjectSnapshot` |
| `src/main/kotlin/com/devomer/previewgallery/service/McpServerStartup.kt` | Restarts the server on IDE start when the user left it on |
| `src/main/kotlin/com/devomer/previewgallery/ui/McpServerAction.kt` | Toolbar action opening the dialog |
| `src/main/kotlin/com/devomer/previewgallery/ui/McpServerDialog.kt` | Status, start/stop, client config snippets |

**Modify**

| File | Change |
|---|---|
| `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` | One action added to the toolbar group |
| `src/main/resources/META-INF/plugin.xml` | `applicationService` + `postStartupActivity` |
| `src/main/resources/messages/PreviewGalleryBundle.properties` | Seven new keys |

**Test (all written in the Test & Review phase, not before)**

| File | Covers |
|---|---|
| `src/test/kotlin/com/devomer/previewgallery/mcp/ProjectSelectorTest.kt` | Project resolution and its error messages |
| `src/test/kotlin/com/devomer/previewgallery/mcp/ToolsTest.kt` | The four tools' payloads and filters |
| `src/test/kotlin/com/devomer/previewgallery/mcp/ToolRegistryTest.kt` | Descriptors, dispatch, unknown tool, indexing guard |
| `src/test/kotlin/com/devomer/previewgallery/mcp/McpDispatcherTest.kt` | JSON-RPC behaviour |
| `src/test/kotlin/com/devomer/previewgallery/mcp/McpHttpServerTest.kt` | Real socket, `Origin` rejection, lifecycle |
| `src/test/kotlin/com/devomer/previewgallery/service/McpServerServiceTest.kt` | `PreviewEntry` → `ProjectSnapshot` mapping |

---

### Task 1 (`PG17-2`): The snapshot model and project selection

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/mcp/ProjectSnapshot.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/mcp/ProjectSelector.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `ProjectSnapshot(name, path, indexing, previews, snapshots)`, `PreviewFacts`, `SnapshotFacts`, `ReferenceImage`; `ProjectSelector.select(snapshots: List<ProjectSnapshot>, requested: String?): SelectionResult` with `SelectionResult.Found(snapshot)` / `SelectionResult.Failure(message)`.

- [ ] **Step 1: Create the snapshot model**

Create `src/main/kotlin/com/devomer/previewgallery/mcp/ProjectSnapshot.kt`:

```kotlin
package com.devomer.previewgallery.mcp

/**
 * Everything one open project contributes to an MCP response, already flattened to strings and paths.
 *
 * This is the seam between the IDE and the protocol: `McpServerService` builds it inside a read action, and
 * nothing under `mcp/` sees a `Project`, a `VirtualFile` or a PSI element. That is what lets every tool be
 * tested by constructing one of these instead of standing up a fixture.
 */
data class ProjectSnapshot(
    val name: String,
    val path: String,
    /** True while the index is still building. The row lists are empty then, and saying so is the point. */
    val indexing: Boolean,
    val previews: List<PreviewFacts> = emptyList(),
    val snapshots: List<SnapshotFacts> = emptyList(),
)

/** One `@Preview` function, with the coverage already resolved. */
data class PreviewFacts(
    val composableFqn: String,
    val displayName: String,
    val moduleName: String,
    val packageName: String,
    val file: String,
    /** 1-based, or null when the file's document could not be loaded. */
    val line: Int?,
    val isPrivate: Boolean,
    val hasPreviewParameter: Boolean,
    /** Non-null when the preview cannot be rendered, e.g. because it is declared inside a class. */
    val unsupportedReason: String?,
    val covered: Boolean,
    /** FQNs of the snapshot functions that cover this preview. */
    val snapshots: List<String> = emptyList(),
)

/** One `@PreviewTest` function, and the reference images committed for it. */
data class SnapshotFacts(
    val snapshotFqn: String,
    val moduleName: String,
    val file: String,
    val line: Int?,
    /** The composables this body shows — how a snapshot is matched to a preview. */
    val targets: List<String> = emptyList(),
    /** True when no preview in the module shows the same composable. */
    val orphan: Boolean = false,
    val referenceImages: List<ReferenceImage> = emptyList(),
)

/** A committed reference PNG. [variant] is null for a reference root that is not under a build variant. */
data class ReferenceImage(val variant: String?, val path: String)
```

- [ ] **Step 2: Create the project selector**

Create `src/main/kotlin/com/devomer/previewgallery/mcp/ProjectSelector.kt`:

```kotlin
package com.devomer.previewgallery.mcp

/**
 * Resolves the optional `project` argument every tool takes.
 *
 * Picking the first open project when the argument is missing would make an agent's answer depend on window
 * order, so an ambiguous call is an error that names the choices instead. The argument matches the project's
 * name first and its base path second: `list_projects` reports both, and an agent should be able to pass back
 * whichever it kept.
 */
object ProjectSelector {

    sealed interface SelectionResult {
        data class Found(val snapshot: ProjectSnapshot) : SelectionResult
        data class Failure(val message: String) : SelectionResult
    }

    fun select(snapshots: List<ProjectSnapshot>, requested: String?): SelectionResult {
        if (requested == null) {
            return when (snapshots.size) {
                0 -> SelectionResult.Failure("No project is open in the IDE.")
                1 -> SelectionResult.Found(snapshots.first())
                else -> SelectionResult.Failure(
                    "More than one project is open. Pass `project` as one of: ${names(snapshots)}.",
                )
            }
        }
        val byName = snapshots.filter { it.name == requested }
        if (byName.size == 1) return SelectionResult.Found(byName.first())
        if (byName.size > 1) {
            return SelectionResult.Failure(
                "More than one open project is named \"$requested\". Pass `project` as one of: " +
                    "${byName.joinToString { it.path }}.",
            )
        }
        val byPath = snapshots.filter { it.path == requested }
        if (byPath.size == 1) return SelectionResult.Found(byPath.first())
        return SelectionResult.Failure(
            "No open project matches \"$requested\". Open projects: ${names(snapshots)}.",
        )
    }

    private fun names(snapshots: List<ProjectSnapshot>): String =
        if (snapshots.isEmpty()) "none" else snapshots.joinToString { it.name }
}
```

- [ ] **Step 3: Compile**

First run `pgrep -f "idea.plugin.in.sandbox.mode=true"` and `pgrep -f "gradlew.*runIde"`; stop and report if either prints a pid.

Run: `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

Write the message to a file and use `git commit -F` — a heredoc broke on an apostrophe in an earlier phase. Write it under the session scratchpad directory, not `/tmp`.

```bash
git add src/main/kotlin/com/devomer/previewgallery/mcp/ProjectSnapshot.kt src/main/kotlin/com/devomer/previewgallery/mcp/ProjectSelector.kt
git commit -F <scratchpad>/pg17-2-msg
```

Message body:

```
[PG17-2] - Model the project facts the MCP tools serve

ProjectSnapshot is the seam: the service fills it inside a read action, and
nothing under mcp/ ever sees a Project or a VirtualFile.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 2 (`PG17-3`): The four tools

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/mcp/tools/ListProjectsTool.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/mcp/tools/ListPreviewsTool.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/mcp/tools/ListSnapshotsTool.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/mcp/tools/CoverageReportTool.kt`

**Interfaces:**
- Consumes: `ProjectSnapshot`, `PreviewFacts`, `SnapshotFacts`, `ReferenceImage` from Task 1; `CoverageReport.markdown(rows: List<PreviewRow>)` from `service/CoverageReport.kt`.
- Produces: `ListProjectsTool.NAME` / `execute(snapshots: List<ProjectSnapshot>): String`; `ListPreviewsTool.NAME` / `execute(snapshot: ProjectSnapshot, module: String?, packagePrefix: String?, uncoveredOnly: Boolean): String`; `ListSnapshotsTool.NAME` / `execute(snapshot: ProjectSnapshot, module: String?, orphansOnly: Boolean): String`; `CoverageReportTool.NAME` / `execute(snapshot: ProjectSnapshot, module: String?): String`. Every `execute` returns the text the tool result carries.

- [ ] **Step 1: Create `list_projects`**

Create `src/main/kotlin/com/devomer/previewgallery/mcp/tools/ListProjectsTool.kt`:

```kotlin
package com.devomer.previewgallery.mcp.tools

import com.devomer.previewgallery.mcp.ProjectSnapshot
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The discovery call, and the one an agent makes first: it is where the `project` argument every other tool
 * takes comes from, and the only place that reports a project whose index is still building.
 */
object ListProjectsTool {

    const val NAME = "list_projects"

    const val DESCRIPTION =
        "Lists the projects currently open in the IDE, with the name and path to pass as `project` to the " +
            "other tools. Call this first. `indexing` true means the index is still building and the other " +
            "tools will refuse rather than answer from an empty index."

    fun execute(snapshots: List<ProjectSnapshot>): String = buildJsonArray {
        snapshots.forEach { snapshot ->
            add(
                buildJsonObject {
                    put("name", snapshot.name)
                    put("path", snapshot.path)
                    put("indexing", snapshot.indexing)
                    put("previewCount", snapshot.previews.size)
                    put("snapshotCount", snapshot.snapshots.size)
                    put("orphanCount", snapshot.snapshots.count { it.orphan })
                    put("uncoveredCount", snapshot.previews.count { !it.covered })
                },
            )
        }
    }.toString()
}
```

- [ ] **Step 2: Create `list_previews`**

Create `src/main/kotlin/com/devomer/previewgallery/mcp/tools/ListPreviewsTool.kt`:

```kotlin
package com.devomer.previewgallery.mcp.tools

import com.devomer.previewgallery.mcp.PreviewFacts
import com.devomer.previewgallery.mcp.ProjectSnapshot
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The work queue, in the form an agent can act on.
 *
 * `unsupportedReason` is carried through rather than dropped: a preview declared inside a class cannot be
 * rendered, so an agent that writes a snapshot test for one has written a test that cannot run.
 */
object ListPreviewsTool {

    const val NAME = "list_previews"

    const val DESCRIPTION =
        "Lists the @Preview functions in a project, with the file and line to open, whether a snapshot test " +
            "covers each one, and which snapshot functions do. Pass `uncoveredOnly` true for the ones that " +
            "need a snapshot written. Filter with `module` (exact match) and `package` (prefix)."

    fun execute(
        snapshot: ProjectSnapshot,
        module: String?,
        packagePrefix: String?,
        uncoveredOnly: Boolean,
    ): String {
        val rows = snapshot.previews
            .filter { module == null || it.moduleName == module }
            .filter { packagePrefix == null || it.packageName.startsWith(packagePrefix) }
            .filter { !uncoveredOnly || !it.covered }
        return buildJsonArray { rows.forEach { add(json(it)) } }.toString()
    }

    private fun json(row: PreviewFacts) = buildJsonObject {
        put("composableFqn", row.composableFqn)
        put("displayName", row.displayName)
        put("module", row.moduleName)
        put("package", row.packageName)
        put("file", row.file)
        row.line?.let { put("line", it) } ?: put("line", JsonNull)
        put("isPrivate", row.isPrivate)
        put("hasPreviewParameter", row.hasPreviewParameter)
        row.unsupportedReason?.let { put("unsupportedReason", it) } ?: put("unsupportedReason", JsonNull)
        put("covered", row.covered)
        put("snapshots", buildJsonArray { row.snapshots.forEach { add(it) } })
    }
}
```

Note on `buildJsonArray { add(it) }` with a `String`: `add` accepts a `String` overload, so no explicit
`JsonPrimitive` wrapper is needed. If the overload does not resolve, wrap with `JsonPrimitive(it)` and add the
`kotlinx.serialization.json.JsonPrimitive` import.

- [ ] **Step 3: Create `list_snapshots`**

Create `src/main/kotlin/com/devomer/previewgallery/mcp/tools/ListSnapshotsTool.kt`:

```kotlin
package com.devomer.previewgallery.mcp.tools

import com.devomer.previewgallery.mcp.ProjectSnapshot
import com.devomer.previewgallery.mcp.SnapshotFacts
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The other half of the picture: what is already snapshotted, and which snapshots match no preview at all.
 *
 * An orphan is usually a renamed composable or a dead test — the defect class the gallery discovered and the
 * one an agent cannot find by looking at previews alone. Reference images are paths, never bytes: every client
 * here can read a file, and base64 in a JSON-RPC response would spend a context window doing it worse.
 */
object ListSnapshotsTool {

    const val NAME = "list_snapshots"

    const val DESCRIPTION =
        "Lists the @PreviewTest snapshot functions in a project, the composables each one shows, and the " +
            "absolute paths of the reference PNGs committed for it. Pass `orphansOnly` true for the snapshots " +
            "that match no preview. Filter with `module` (exact match)."

    fun execute(snapshot: ProjectSnapshot, module: String?, orphansOnly: Boolean): String {
        val rows = snapshot.snapshots
            .filter { module == null || it.moduleName == module }
            .filter { !orphansOnly || it.orphan }
        return buildJsonArray { rows.forEach { add(json(it)) } }.toString()
    }

    private fun json(row: SnapshotFacts) = buildJsonObject {
        put("snapshotFqn", row.snapshotFqn)
        put("module", row.moduleName)
        put("file", row.file)
        row.line?.let { put("line", it) } ?: put("line", JsonNull)
        put("targets", buildJsonArray { row.targets.forEach { add(it) } })
        put("orphan", row.orphan)
        put(
            "referenceImages",
            buildJsonArray {
                row.referenceImages.forEach { image ->
                    add(
                        buildJsonObject {
                            image.variant?.let { put("variant", it) }
                            put("path", image.path)
                        },
                    )
                }
            },
        )
    }
}
```

- [ ] **Step 4: Create `coverage_report`**

`CoverageReport.markdown` takes `List<PreviewRow>`, which `PreviewFacts` is not. Rather than widen
`CoverageReport` or duplicate its format, adapt: `PreviewFacts` carries everything a row needs for the report —
module name, FQN and coverage.

Create `src/main/kotlin/com/devomer/previewgallery/mcp/tools/CoverageReportTool.kt`:

```kotlin
package com.devomer.previewgallery.mcp.tools

import com.devomer.previewgallery.mcp.PreviewFacts
import com.devomer.previewgallery.mcp.ProjectSnapshot
import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.IndexedPreview
import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.model.SnapshotCoverage
import com.devomer.previewgallery.service.CoverageReport

/**
 * The same markdown the toolbar's export writes, so a number an agent quotes and a number pasted from the IDE
 * cannot disagree. The format lives in [CoverageReport] and is pinned by its own tests; this only adapts rows.
 */
object CoverageReportTool {

    const val NAME = "coverage_report"

    const val DESCRIPTION =
        "Returns the project's snapshot coverage as markdown: X/Y covered overall and per module, with the " +
            "uncovered composables listed by FQN. Filter with `module` (exact match). For machine-readable " +
            "output use list_previews with uncoveredOnly instead."

    fun execute(snapshot: ProjectSnapshot, module: String?): String {
        val rows = snapshot.previews
            .filter { module == null || it.moduleName == module }
            .map(::asRow)
        return CoverageReport.markdown(rows)
    }

    private fun asRow(facts: PreviewFacts): PreviewRow = ReportRow(
        indexed = IndexedPreview(
            displayName = facts.displayName,
            functionName = facts.composableFqn.substringAfterLast('.'),
            packageName = facts.packageName,
            jvmClassName = facts.composableFqn.substringBeforeLast('.'),
            composableFqn = facts.composableFqn,
            offset = 0,
            annotationKind = AnnotationKind.ANDROIDX,
            isPrivate = facts.isPrivate,
            hasPreviewParameter = facts.hasPreviewParameter,
            previewGroup = null,
            unsupportedReason = facts.unsupportedReason,
        ),
        moduleName = facts.moduleName,
        coverage = if (facts.covered) {
            SnapshotCoverage.Covered(facts.snapshots.size)
        } else {
            SnapshotCoverage.Uncovered
        },
    )

    private data class ReportRow(
        override val indexed: IndexedPreview,
        override val moduleName: String,
        override val coverage: SnapshotCoverage,
    ) : PreviewRow
}
```

This file is the one exception to "nothing under `mcp/` imports outside itself": it imports the plugin's own
`model` and `service` packages, which are pure Kotlin with no `com.intellij` types. The constraint is about
IDE types, and this stays on the right side of it. Confirm before committing that `model/IndexedPreview.kt`,
`model/PreviewRow.kt`, `model/SnapshotCoverage.kt` and `service/CoverageReport.kt` import no `com.intellij`
package; if any of them does, stop and report rather than dragging an IDE type into `mcp/`.

`AnnotationKind.ANDROIDX` is used because `IndexedPreview` requires the field and the report never reads it.
Check `model/AnnotationKind.kt` for the exact constant name before writing this — if `ANDROIDX` is not a
member, use the first constant the enum declares and note the substitution.

- [ ] **Step 5: Compile**

Sandbox check first (both `pgrep` patterns), then:

Run: `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/mcp/tools/
git commit -F <scratchpad>/pg17-3-msg
```

Message body:

```
[PG17-3] - Add the four MCP tools

coverage_report adapts rows onto the existing CoverageReport rather than
growing a second coverage format that would drift from the one the export
already writes.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 3 (`PG17-4`): The tool registry

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/mcp/ToolRegistry.kt`

**Interfaces:**
- Consumes: all four tools from Task 2; `ProjectSelector.select` from Task 1.
- Produces: `ToolRegistry(snapshots: () -> List<ProjectSnapshot>)` with `descriptors(): List<ToolDescriptor>` and `call(name: String, arguments: JsonObject): ToolOutcome`; `ToolDescriptor(name, description, inputSchema: JsonObject)`; `ToolOutcome.Text(text)` / `ToolOutcome.Failure(message)`.

- [ ] **Step 1: Create the registry**

Create `src/main/kotlin/com/devomer/previewgallery/mcp/ToolRegistry.kt`:

```kotlin
package com.devomer.previewgallery.mcp

import com.devomer.previewgallery.mcp.tools.CoverageReportTool
import com.devomer.previewgallery.mcp.tools.ListPreviewsTool
import com.devomer.previewgallery.mcp.tools.ListProjectsTool
import com.devomer.previewgallery.mcp.tools.ListSnapshotsTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One entry of the `tools/list` response. */
data class ToolDescriptor(val name: String, val description: String, val inputSchema: JsonObject)

/** What a tool call produced. A [Failure] becomes a JSON-RPC error, not an empty result. */
sealed interface ToolOutcome {
    data class Text(val text: String) : ToolOutcome
    data class Failure(val message: String) : ToolOutcome
}

/**
 * Maps tool names onto the four tools, and owns the two rules every one of them shares: resolve `project`
 * (spec D4), and refuse while the index is building (spec D10).
 *
 * The indexing refusal is the load-bearing one. `PreviewIndexService` answers with an empty list in dumb mode,
 * and an agent handed `[]` concludes the project has no previews and acts on it. An error makes it wait.
 */
class ToolRegistry(private val snapshots: () -> List<ProjectSnapshot>) {

    fun descriptors(): List<ToolDescriptor> = listOf(
        ToolDescriptor(ListProjectsTool.NAME, ListProjectsTool.DESCRIPTION, schema()),
        ToolDescriptor(
            ListPreviewsTool.NAME,
            ListPreviewsTool.DESCRIPTION,
            schema(
                "project" to STRING,
                "module" to STRING,
                "package" to STRING,
                "uncoveredOnly" to BOOLEAN,
            ),
        ),
        ToolDescriptor(
            ListSnapshotsTool.NAME,
            ListSnapshotsTool.DESCRIPTION,
            schema("project" to STRING, "module" to STRING, "orphansOnly" to BOOLEAN),
        ),
        ToolDescriptor(
            CoverageReportTool.NAME,
            CoverageReportTool.DESCRIPTION,
            schema("project" to STRING, "module" to STRING),
        ),
    )

    fun call(name: String, arguments: JsonObject): ToolOutcome {
        val open = snapshots()
        if (name == ListProjectsTool.NAME) return ToolOutcome.Text(ListProjectsTool.execute(open))

        val selection = ProjectSelector.select(open, string(arguments, "project"))
        val project = when (selection) {
            is ProjectSelector.SelectionResult.Failure -> return ToolOutcome.Failure(selection.message)
            is ProjectSelector.SelectionResult.Found -> selection.snapshot
        }
        if (project.indexing) {
            return ToolOutcome.Failure(
                "The index for \"${project.name}\" is still building. Retry once list_projects reports " +
                    "indexing false.",
            )
        }

        val module = string(arguments, "module")
        return when (name) {
            ListPreviewsTool.NAME -> ToolOutcome.Text(
                ListPreviewsTool.execute(
                    project,
                    module,
                    string(arguments, "package"),
                    boolean(arguments, "uncoveredOnly"),
                ),
            )
            ListSnapshotsTool.NAME -> ToolOutcome.Text(
                ListSnapshotsTool.execute(project, module, boolean(arguments, "orphansOnly")),
            )
            CoverageReportTool.NAME -> ToolOutcome.Text(CoverageReportTool.execute(project, module))
            else -> ToolOutcome.Failure("Unknown tool: $name")
        }
    }

    private fun string(arguments: JsonObject, key: String): String? =
        (arguments[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun boolean(arguments: JsonObject, key: String): Boolean =
        (arguments[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun schema(vararg properties: Pair<String, String>): JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                properties.forEach { (name, type) ->
                    put(name, buildJsonObject { put("type", type) })
                }
            },
        )
        put("required", buildJsonArray { })
    }

    private companion object {
        const val STRING = "string"
        const val BOOLEAN = "boolean"
    }
}
```

- [ ] **Step 2: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/mcp/ToolRegistry.kt
git commit -F <scratchpad>/pg17-4-msg
```

Message body:

```
[PG17-4] - Dispatch the MCP tools

Refusing while the index builds is the load-bearing rule here: an agent handed
an empty list concludes the project has no previews and acts on it.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 4 (`PG17-5`): The JSON-RPC dispatcher

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/mcp/McpDispatcher.kt`

**Interfaces:**
- Consumes: `ToolRegistry`, `ToolDescriptor`, `ToolOutcome` from Task 3.
- Produces: `McpDispatcher(serverName: String, serverVersion: String, tools: ToolRegistry)` with `handle(requestBody: String): DispatchResult`; `DispatchResult.Json(body)` / `DispatchResult.NoContent`.

- [ ] **Step 1: Create the dispatcher**

Create `src/main/kotlin/com/devomer/previewgallery/mcp/McpDispatcher.kt`:

```kotlin
package com.devomer.previewgallery.mcp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** What the HTTP layer should send back. */
sealed interface DispatchResult {
    /** HTTP 200, `application/json`. */
    data class Json(val body: String) : DispatchResult

    /** HTTP 202, empty: a JSON-RPC notification carries no id and takes no response. */
    data object NoContent : DispatchResult
}

/**
 * MCP over JSON-RPC 2.0, as a pure function of the request body.
 *
 * No socket and no IntelliJ type appears here, so every protocol behaviour — version negotiation, a malformed
 * body, an unknown method, a tool failure — is a `String` in and a [DispatchResult] out in a plain JUnit test.
 */
class McpDispatcher(
    private val serverName: String,
    private val serverVersion: String,
    private val tools: ToolRegistry,
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun handle(requestBody: String): DispatchResult {
        val root = try {
            json.parseToJsonElement(requestBody)
        } catch (e: SerializationException) {
            return error(JsonNull, PARSE_ERROR, "Parse error")
        }
        if (root !is JsonObject) return error(JsonNull, INVALID_REQUEST, "Invalid Request")
        if ("id" !in root) return DispatchResult.NoContent

        val id = root["id"] ?: JsonNull
        val method = (root["method"] as? JsonPrimitive)?.contentOrNull
            ?: return error(id, INVALID_REQUEST, "Invalid Request: missing method")
        val params = root["params"] as? JsonObject

        return when (method) {
            "initialize" -> ok(id, initializeResult(params))
            "ping" -> ok(id, buildJsonObject { })
            "tools/list" -> ok(id, toolsListResult())
            "tools/call" -> toolsCall(id, params)
            else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun initializeResult(params: JsonObject?): JsonObject {
        val requested = (params?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull
        val version = if (requested != null && requested in SUPPORTED_VERSIONS) requested else DEFAULT_VERSION
        return buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", buildJsonObject { put("tools", buildJsonObject { }) })
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", serverName)
                    put("version", serverVersion)
                },
            )
        }
    }

    private fun toolsListResult(): JsonObject = buildJsonObject {
        put(
            "tools",
            buildJsonArray {
                tools.descriptors().forEach { descriptor ->
                    add(
                        buildJsonObject {
                            put("name", descriptor.name)
                            put("description", descriptor.description)
                            put("inputSchema", descriptor.inputSchema)
                        },
                    )
                }
            },
        )
    }

    private fun toolsCall(id: JsonElement, params: JsonObject?): DispatchResult {
        val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull
            ?: return error(id, INVALID_PARAMS, "Invalid params: missing tool name")
        val arguments = (params["arguments"] as? JsonObject) ?: JsonObject(emptyMap())
        return when (val outcome = tools.call(name, arguments)) {
            is ToolOutcome.Failure -> error(id, TOOL_ERROR, outcome.message)
            is ToolOutcome.Text -> ok(
                id,
                buildJsonObject {
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", outcome.text)
                                },
                            )
                        },
                    )
                    put("isError", false)
                },
            )
        }
    }

    private fun ok(id: JsonElement, result: JsonObject) = DispatchResult.Json(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString(),
    )

    private fun error(id: JsonElement, code: Int, message: String) = DispatchResult.Json(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        }.toString(),
    )

    private companion object {
        val SUPPORTED_VERSIONS = setOf("2025-06-18", "2025-03-26", "2024-11-05")
        const val DEFAULT_VERSION = "2025-06-18"
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        /** Server-defined range: the call was well-formed, the server could not answer it. */
        const val TOOL_ERROR = -32000
    }
}
```

- [ ] **Step 2: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/mcp/McpDispatcher.kt
git commit -F <scratchpad>/pg17-5-msg
```

Message body:

```
[PG17-5] - Route MCP over JSON-RPC

The dispatcher is a pure function of the request body, so protocol behaviour is
testable without a socket.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 5 (`PG17-6`): The HTTP transport

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/mcp/McpHttpServer.kt`

**Interfaces:**
- Consumes: `DispatchResult` from Task 4.
- Produces: `McpHttpServer(port: Int, handle: (String) -> DispatchResult)` with `start()`, `stop()`, `isRunning: Boolean`, `boundPort: Int`. `start()` throws `java.io.IOException` (a `BindException` in practice) when the port is taken — the caller reports it.

- [ ] **Step 1: Create the server**

Create `src/main/kotlin/com/devomer/previewgallery/mcp/McpHttpServer.kt`:

```kotlin
package com.devomer.previewgallery.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Streamable HTTP for MCP, on the JDK's own server: `POST /mcp` and `GET /health`, bound to the loopback
 * address only.
 *
 * A request carrying an `Origin` header is refused with 403 (spec D9). MCP clients do not set it and browsers
 * always do, so this is what stops any page the user has open from reading the project's structure off a
 * loopback socket. It is the only access control a local, read-only server needs, and it is three lines.
 *
 * [handle] is the pure dispatcher: this class owns the socket and nothing else.
 */
class McpHttpServer(
    private val port: Int,
    private val handle: (String) -> DispatchResult,
) {

    private var server: HttpServer? = null

    val isRunning: Boolean get() = server != null

    val boundPort: Int get() = server?.address?.port ?: port

    /** @throws IOException when [port] is already bound — the caller surfaces it, rather than a silent no-op. */
    fun start() {
        if (server != null) return
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        started.createContext("/health") { exchange -> respond(exchange, 200, "ok", TEXT) }
        started.createContext("/mcp") { exchange -> mcp(exchange) }
        started.executor = Executors.newFixedThreadPool(2)
        started.start()
        server = started
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun mcp(exchange: HttpExchange) {
        exchange.use {
            if (exchange.requestHeaders.getFirst("Origin") != null) {
                respond(exchange, 403, "Forbidden", TEXT)
                return
            }
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, "Method Not Allowed", TEXT)
                return
            }
            val body = exchange.requestBody.readBytes().decodeToString()
            when (val result = handle(body)) {
                is DispatchResult.Json -> respond(exchange, 200, result.body, JSON)
                DispatchResult.NoContent -> respond(exchange, 202, "", TEXT)
            }
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, contentType: String) {
        val bytes = body.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
    }

    private fun HttpExchange.use(block: () -> Unit) {
        try {
            block()
        } finally {
            close()
        }
    }

    private companion object {
        const val JSON = "application/json"
        const val TEXT = "text/plain"
    }
}
```

`HttpExchange` does not implement `Closeable` in every JDK, which is why the private `use` above is defined
rather than relying on the stdlib's. If it turns out to implement `Closeable` on this JDK, delete the private
extension and use the stdlib `use` — do not leave both.

- [ ] **Step 2: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/mcp/McpHttpServer.kt
git commit -F <scratchpad>/pg17-6-msg
```

Message body:

```
[PG17-6] - Serve MCP over the JDK HTTP server

Two endpoints do not earn Ktor and Netty inside the IDE. A request carrying an
Origin header is refused: MCP clients do not send one, browsers always do.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 6 (`PG17-7`): The application service

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/service/McpServerService.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/service/McpServerStartup.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Consumes: `McpHttpServer` (Task 5), `McpDispatcher` (Task 4), `ToolRegistry` (Task 3), `ProjectSnapshot` / `PreviewFacts` / `SnapshotFacts` / `ReferenceImage` (Task 1); `PreviewIndexService.getInstance(project).findAll()` and `.findOrphanSnapshots()`; `ReferenceRoots.of(moduleDirectory)`; `ModuleDirectoryResolver`.
- Produces: `McpServerService.getInstance(): McpServerService` with `start(): StartResult`, `stop()`, `isRunning: Boolean`, `port: Int`, `snapshots(): List<ProjectSnapshot>`; `StartResult.Started` / `StartResult.PortInUse(port)` / `StartResult.AlreadyRunning`.

- [ ] **Step 1: Read the APIs this task consumes**

Before writing, read these and confirm the exact signatures — the mapping below depends on them:

- `src/main/kotlin/com/devomer/previewgallery/service/PreviewIndexService.kt` — `findAll()`, `findOrphanSnapshots()`, `getInstance(project)`
- `src/main/kotlin/com/devomer/previewgallery/service/ReferenceRoots.kt` — `of(moduleDirectory: VirtualFile): List<Root>`, `Root(sourceSetName, buildVariant, directory)`
- `src/main/kotlin/com/devomer/previewgallery/service/ModuleDirectoryResolver.kt` — how a row's module directory is resolved from its file
- `src/main/kotlin/com/devomer/previewgallery/model/PreviewEntry.kt` — `indexed`, `moduleName`, `file`, `coverage`, `snapshots`

If a signature differs from what this task assumes, follow the real one and note the difference in your report.

- [ ] **Step 2: Create the service**

Create `src/main/kotlin/com/devomer/previewgallery/service/McpServerService.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.devomer.previewgallery.mcp.McpDispatcher
import com.devomer.previewgallery.mcp.McpHttpServer
import com.devomer.previewgallery.mcp.PreviewFacts
import com.devomer.previewgallery.mcp.ProjectSnapshot
import com.devomer.previewgallery.mcp.ReferenceImage
import com.devomer.previewgallery.mcp.SnapshotFacts
import com.devomer.previewgallery.mcp.ToolRegistry
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.SnapshotCoverage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Owns the MCP socket and turns the IDE's live index into the flat [ProjectSnapshot] the protocol half serves.
 *
 * Application-level on purpose (spec D4): this workflow runs two IDEs at once — the main one and the plugin's
 * `runIde` sandbox — and a project-level server would make the second fight for the port. One server, one port,
 * and a `project` argument on every tool.
 */
@Service(Service.Level.APP)
class McpServerService {

    sealed interface StartResult {
        data object Started : StartResult
        data object AlreadyRunning : StartResult
        data class PortInUse(val port: Int) : StartResult
    }

    val port: Int = PORT

    private var server: McpHttpServer? = null

    val isRunning: Boolean get() = server?.isRunning == true

    fun start(): StartResult {
        if (isRunning) return StartResult.AlreadyRunning
        val dispatcher = McpDispatcher(SERVER_NAME, SERVER_VERSION, ToolRegistry(::snapshots))
        val started = McpHttpServer(PORT) { dispatcher.handle(it) }
        return try {
            started.start()
            server = started
            StartResult.Started
        } catch (e: IOException) {
            thisLogger().warn("Failed to bind the MCP server to port $PORT", e)
            StartResult.PortInUse(PORT)
        }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    /** One entry per open project, each read under its own read action. */
    fun snapshots(): List<ProjectSnapshot> =
        ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .map { project -> ReadAction.compute<ProjectSnapshot, RuntimeException> { snapshot(project) } }

    private fun snapshot(project: Project): ProjectSnapshot {
        val name = project.name
        val path = project.basePath ?: ""
        if (DumbService.isDumb(project)) return ProjectSnapshot(name, path, indexing = true)

        val index = PreviewIndexService.getInstance(project)
        val previews = index.findAll()
        val orphans = index.findOrphanSnapshots()
        return ProjectSnapshot(
            name = name,
            path = path,
            indexing = false,
            previews = previews.map(::previewFacts),
            snapshots = snapshotFacts(project, previews, orphans),
        )
    }

    private fun previewFacts(entry: PreviewEntry) = PreviewFacts(
        composableFqn = entry.indexed.composableFqn,
        displayName = entry.indexed.displayName,
        moduleName = entry.moduleName,
        packageName = entry.indexed.packageName,
        file = entry.file.path,
        line = lineOf(entry.file, entry.indexed.offset),
        isPrivate = entry.indexed.isPrivate,
        hasPreviewParameter = entry.indexed.hasPreviewParameter,
        unsupportedReason = entry.indexed.unsupportedReason,
        covered = entry.coverage is SnapshotCoverage.Covered,
        snapshots = entry.snapshots.map { it.indexed.composableFqn },
    )

    private fun snapshotFacts(
        project: Project,
        previews: List<PreviewEntry>,
        orphans: List<PreviewEntry>,
    ): List<SnapshotFacts> {
        val covering = previews.flatMap { it.snapshots }.distinctBy { it.indexed.composableFqn }
        return (covering.map { facts(project, it, orphan = false) } +
            orphans.map { facts(project, it, orphan = true) })
    }

    private fun facts(project: Project, entry: PreviewEntry, orphan: Boolean) = SnapshotFacts(
        snapshotFqn = entry.indexed.composableFqn,
        moduleName = entry.moduleName,
        file = entry.file.path,
        line = lineOf(entry.file, entry.indexed.offset),
        targets = entry.indexed.targets,
        orphan = orphan,
        referenceImages = referenceImages(project, entry.file),
    )

    /**
     * Reference PNGs as absolute paths (spec D7). A missing directory is an empty list, not an error: a
     * snapshot whose `update…ScreenshotTest` has never run is a real state an agent should be able to see.
     */
    private fun referenceImages(project: Project, file: VirtualFile): List<ReferenceImage> {
        val moduleDirectory = ModuleDirectoryResolver.of(project, file) ?: return emptyList()
        return ReferenceRoots.of(moduleDirectory).flatMap { root ->
            val children: Array<VirtualFile> = root.directory.children ?: emptyArray()
            children
                .filter { it.extension == "png" }
                .map { ReferenceImage(root.buildVariant, it.path) }
        }
    }

    /** 1-based, or null when the document is unavailable — a line number is worth a null, never a failed call. */
    private fun lineOf(file: VirtualFile, offset: Int): Int? {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        if (offset < 0 || offset > document.textLength) return null
        return document.getLineNumber(offset) + 1
    }

    companion object {
        const val PORT = 7891
        private const val SERVER_NAME = "preview-gallery"
        private const val SERVER_VERSION = "0.0.1"

        fun getInstance(): McpServerService =
            ApplicationManager.getApplication().getService(McpServerService::class.java)
    }
}
```

`ModuleDirectoryResolver.of(project, file)` is the signature this assumes — Step 1 is where you confirm it. If
the real one differs (for example it takes only a `VirtualFile`), use the real one.

- [ ] **Step 3: Create the startup activity**

Create `src/main/kotlin/com/devomer/previewgallery/service/McpServerStartup.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Brings the server back on IDE start when the user left it on.
 *
 * A project activity for an application-level server: there is no application-level "started" hook a plugin
 * can use, and the first project to open is the earliest point an agent could be waiting. Starting twice is
 * a no-op ([McpServerService.StartResult.AlreadyRunning]), so the second project changes nothing.
 */
class McpServerStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!PropertiesComponent.getInstance().getBoolean(ENABLED_KEY, false)) return
        McpServerService.getInstance().start()
    }

    companion object {
        const val ENABLED_KEY = "com.devomer.previewgallery.mcpServerEnabled"
    }
}
```

- [ ] **Step 4: Register both in `plugin.xml`**

In `src/main/resources/META-INF/plugin.xml`, inside the existing `<extensions defaultExtensionNs="com.intellij">`
block, next to the existing `<projectService>` entries:

```xml
        <applicationService
                serviceImplementation="com.devomer.previewgallery.service.McpServerService"/>
        <postStartupActivity
                implementation="com.devomer.previewgallery.service.McpServerStartup"/>
```

Read the file first: it already has a `postStartupActivity` entry, so match its attribute style exactly.

- [ ] **Step 5: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/service/McpServerService.kt src/main/kotlin/com/devomer/previewgallery/service/McpServerStartup.kt src/main/resources/META-INF/plugin.xml
git commit -F <scratchpad>/pg17-7-msg
```

Message body:

```
[PG17-7] - Own the MCP socket from an application service

Application-level because this workflow runs two IDEs at once: one server, one
port, and a project argument on every tool. Snapshots are taken inside a read
action, which is what lets the protocol half stay free of PSI.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 7 (`PG17-8`): The toolbar action and the config dialog

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/McpServerAction.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/McpServerDialog.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` — the toolbar group
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties`

**Interfaces:**
- Consumes: `McpServerService.getInstance()`, `McpServerService.StartResult`, `McpServerStartup.ENABLED_KEY` from Task 6.
- Produces: `McpServerAction(project: Project)`.

- [ ] **Step 1: Add the bundle keys**

In `src/main/resources/messages/PreviewGalleryBundle.properties`, after the `action.coverageReport.text` line:

```properties
action.mcpServer.text=MCP server for agents
mcp.dialog.title=Preview Gallery MCP Server
mcp.status.running=Running on http://localhost:{0}/mcp
mcp.status.stopped=Not running
mcp.start=Start
mcp.stop=Stop
mcp.portInUse=Port {0} is already in use — another IDE is probably serving it
mcp.configHint=Add one of these to your client, then restart it:
```

- [ ] **Step 2: Create the dialog**

Create `src/main/kotlin/com/devomer/previewgallery/ui/McpServerDialog.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.service.McpServerService
import com.devomer.previewgallery.service.McpServerStartup
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.awt.Toolkit
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The whole MCP surface in one dialog: whether the server is up, one button to change that, and the client
 * configuration snippets.
 *
 * One toolbar button rather than two: the toggle and the configuration are the same question the first time
 * ("how do I point Claude at this?") and the toolbar already carries six controls.
 */
class McpServerDialog(project: Project) : DialogWrapper(project) {

    private val service = McpServerService.getInstance()
    private val status = JBLabel()
    private val toggle = JButton()

    init {
        title = PreviewGalleryBundle.message("mcp.dialog.title")
        init()
        refresh()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(360))

        val header = JPanel(BorderLayout(JBUI.scale(8), 0))
        header.add(status, BorderLayout.CENTER)
        toggle.addActionListener { onToggle() }
        header.add(toggle, BorderLayout.EAST)
        panel.add(header, BorderLayout.NORTH)

        val tabs = JBTabbedPane()
        tabs.addTab("Claude Code / Codex", snippetPanel(REMOTE_CONFIG))
        tabs.addTab("Cursor", snippetPanel(REMOTE_CONFIG))
        tabs.addTab("Raw URL", snippetPanel("http://localhost:${service.port}/mcp"))
        panel.add(tabs, BorderLayout.CENTER)
        panel.add(JBLabel(PreviewGalleryBundle.message("mcp.configHint")), BorderLayout.SOUTH)
        return panel
    }

    override fun createActions() = arrayOf(okAction)

    private fun onToggle() {
        if (service.isRunning) {
            service.stop()
            PropertiesComponent.getInstance().setValue(McpServerStartup.ENABLED_KEY, false)
        } else {
            when (val result = service.start()) {
                is McpServerService.StartResult.PortInUse -> Messages.showWarningDialog(
                    contentPanel,
                    PreviewGalleryBundle.message("mcp.portInUse", result.port),
                    title,
                )
                else -> PropertiesComponent.getInstance().setValue(McpServerStartup.ENABLED_KEY, true)
            }
        }
        refresh()
    }

    private fun refresh() {
        status.text = if (service.isRunning) {
            PreviewGalleryBundle.message("mcp.status.running", service.port)
        } else {
            PreviewGalleryBundle.message("mcp.status.stopped")
        }
        toggle.text = PreviewGalleryBundle.message(if (service.isRunning) "mcp.stop" else "mcp.start")
    }

    private fun snippetPanel(snippet: String): JComponent {
        val text = JBTextArea(snippet)
        text.isEditable = false
        text.lineWrap = false
        val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        panel.add(JBScrollPane(text), BorderLayout.CENTER)
        val copy = JButton("Copy")
        copy.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(snippet), null)
        }
        panel.add(copy, BorderLayout.SOUTH)
        return panel
    }

    private val REMOTE_CONFIG: String
        get() = """
        {
          "mcpServers": {
            "preview-gallery": {
              "command": "npx",
              "args": ["-y", "mcp-remote", "http://localhost:${service.port}/mcp"]
            }
          }
        }
        """.trimIndent()
}
```

Both `JBTabbedPane` and `JBTextArea` live in `com.intellij.ui.components`. If either import does not resolve
against this SDK, fall back to `javax.swing.JTabbedPane` / `javax.swing.JTextArea` and note the substitution —
do not invent a different component.

- [ ] **Step 3: Create the action**

Create `src/main/kotlin/com/devomer/previewgallery/ui/McpServerAction.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

/**
 * Opens the MCP dialog. `DumbAware` because the dialog only reports and toggles the socket — it reads no index,
 * and an agent that connects during indexing is told so by the tools themselves (spec D10).
 */
class McpServerAction(private val project: Project) : DumbAwareAction(
    PreviewGalleryBundle.message("action.mcpServer.text"),
    PreviewGalleryBundle.message("action.mcpServer.text"),
    AllIcons.General.Web,
) {

    override fun actionPerformed(event: AnActionEvent) {
        McpServerDialog(project).show()
    }
}
```

- [ ] **Step 4: Add it to the toolbar**

In `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`, in the `DefaultActionGroup(...)`
around line 211, after `CoverageReportAction(project) { entries },`:

```kotlin
            McpServerAction(project),
```

- [ ] **Step 5: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/McpServerAction.kt src/main/kotlin/com/devomer/previewgallery/ui/McpServerDialog.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt src/main/resources/messages/PreviewGalleryBundle.properties
git commit -F <scratchpad>/pg17-8-msg
```

Message body:

```
[PG17-8] - Start the MCP server from the gallery toolbar

One button rather than two: the toggle and the client configuration are the
same question the first time anyone asks it.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

## Test & Review phase (`PG17-9`)

Everything above shipped without a test. This phase is where the feature earns them, and it is not optional.

- [ ] **Step 1: Write the pure tests**

Create `src/test/kotlin/com/devomer/previewgallery/mcp/ProjectSelectorTest.kt`:

```kotlin
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
```

Create `src/test/kotlin/com/devomer/previewgallery/mcp/ToolsTest.kt`:

```kotlin
package com.devomer.previewgallery.mcp

import com.devomer.previewgallery.mcp.tools.CoverageReportTool
import com.devomer.previewgallery.mcp.tools.ListPreviewsTool
import com.devomer.previewgallery.mcp.tools.ListProjectsTool
import com.devomer.previewgallery.mcp.tools.ListSnapshotsTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {

    private val covered = PreviewFacts(
        composableFqn = "com.example.FooKt.CoveredPreview",
        displayName = "CoveredPreview",
        moduleName = "app.main",
        packageName = "com.example",
        file = "/src/Foo.kt",
        line = 12,
        isPrivate = false,
        hasPreviewParameter = false,
        unsupportedReason = null,
        covered = true,
        snapshots = listOf("com.example.FooSnapshotsKt.Covered_Snapshot"),
    )

    private val uncovered = covered.copy(
        composableFqn = "com.example.deep.BarKt.UncoveredPreview",
        displayName = "UncoveredPreview",
        packageName = "com.example.deep",
        covered = false,
        snapshots = emptyList(),
    )

    private val otherModule = uncovered.copy(moduleName = "legacy.main")

    private val snapshot = SnapshotFacts(
        snapshotFqn = "com.example.FooSnapshotsKt.Covered_Snapshot",
        moduleName = "app.main",
        file = "/src/FooSnapshots.kt",
        line = 30,
        targets = listOf("Covered"),
        orphan = false,
        referenceImages = listOf(ReferenceImage("debug", "/src/reference/Covered.png")),
    )

    private val orphan = SnapshotFacts(
        snapshotFqn = "com.example.FooSnapshotsKt.Renamed_Snapshot",
        moduleName = "app.main",
        file = "/src/FooSnapshots.kt",
        line = 44,
        targets = listOf("Renamed"),
        orphan = true,
        referenceImages = emptyList(),
    )

    private val project = ProjectSnapshot(
        name = "demo",
        path = "/src",
        indexing = false,
        previews = listOf(covered, uncovered, otherModule),
        snapshots = listOf(snapshot, orphan),
    )

    @Test
    fun `list_projects reports the counts an agent decides on`() {
        val json = ListProjectsTool.execute(listOf(project))

        assertTrue(json, json.contains("\"name\":\"demo\""))
        assertTrue(json, json.contains("\"previewCount\":3"))
        assertTrue(json, json.contains("\"snapshotCount\":2"))
        assertTrue(json, json.contains("\"orphanCount\":1"))
        assertTrue(json, json.contains("\"uncoveredCount\":2"))
        assertTrue(json, json.contains("\"indexing\":false"))
    }

    @Test
    fun `list_previews returns every row with no filter`() {
        val json = ListPreviewsTool.execute(project, null, null, uncoveredOnly = false)

        assertTrue(json, json.contains("CoveredPreview"))
        assertTrue(json, json.contains("UncoveredPreview"))
    }

    @Test
    fun `list_previews uncoveredOnly drops the covered rows`() {
        val json = ListPreviewsTool.execute(project, null, null, uncoveredOnly = true)

        assertFalse(json, json.contains("CoveredPreview"))
        assertTrue(json, json.contains("UncoveredPreview"))
    }

    @Test
    fun `list_previews filters compose`() {
        val json = ListPreviewsTool.execute(project, "app.main", "com.example.deep", uncoveredOnly = true)

        assertTrue(json, json.contains("UncoveredPreview"))
        assertFalse(json, json.contains("legacy.main"))
    }

    @Test
    fun `list_previews carries the facts a snapshot author needs`() {
        val json = ListPreviewsTool.execute(project, null, null, uncoveredOnly = false)

        assertTrue(json, json.contains("\"file\":\"/src/Foo.kt\""))
        assertTrue(json, json.contains("\"line\":12"))
        assertTrue(json, json.contains("com.example.FooSnapshotsKt.Covered_Snapshot"))
        assertTrue(json, json.contains("\"unsupportedReason\":null"))
    }

    @Test
    fun `list_snapshots orphansOnly selects exactly the orphans`() {
        val json = ListSnapshotsTool.execute(project, null, orphansOnly = true)

        assertTrue(json, json.contains("Renamed_Snapshot"))
        assertFalse(json, json.contains("Covered_Snapshot"))
    }

    @Test
    fun `list_snapshots reports reference images as paths`() {
        val json = ListSnapshotsTool.execute(project, null, orphansOnly = false)

        assertTrue(json, json.contains("\"path\":\"/src/reference/Covered.png\""))
        assertTrue(json, json.contains("\"variant\":\"debug\""))
        assertTrue(json, json.contains("\"referenceImages\":[]"))
    }

    @Test
    fun `coverage_report matches the export the toolbar writes`() {
        val json = CoverageReportTool.execute(project, "app.main")

        assertTrue(json, json.startsWith("# Snapshot coverage"))
        assertTrue(json, json.contains("**1/2 covered** across 1 module"))
        assertTrue(json, json.contains("- `com.example.deep.BarKt.UncoveredPreview`"))
    }
}
```

Create `src/test/kotlin/com/devomer/previewgallery/mcp/ToolRegistryTest.kt`:

```kotlin
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
    fun `an unknown tool fails rather than returning nothing`() {
        val outcome = registry(ready).call("delete_everything", JsonObject(emptyMap()))

        assertTrue(outcome is ToolOutcome.Failure)
    }
}
```

Create `src/test/kotlin/com/devomer/previewgallery/mcp/McpDispatcherTest.kt`:

```kotlin
package com.devomer.previewgallery.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpDispatcherTest {

    private val dispatcher = McpDispatcher("preview-gallery", "0.0.1", ToolRegistry { emptyList() })

    private fun body(request: String): String =
        (dispatcher.handle(request) as DispatchResult.Json).body

    @Test
    fun `initialize echoes a supported protocol version`() {
        val response = body(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}""",
        )

        assertTrue(response, response.contains("\"protocolVersion\":\"2024-11-05\""))
        assertTrue(response, response.contains("preview-gallery"))
    }

    @Test
    fun `initialize falls back for a version it does not speak`() {
        val response = body(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""",
        )

        assertTrue(response, response.contains("\"protocolVersion\":\"2025-06-18\""))
    }

    @Test
    fun `tools_list names all four tools`() {
        val response = body("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")

        listOf("list_projects", "list_previews", "list_snapshots", "coverage_report").forEach {
            assertTrue(response, response.contains(it))
        }
    }

    @Test
    fun `a notification takes no response`() {
        assertEquals(
            DispatchResult.NoContent,
            dispatcher.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""),
        )
    }

    @Test
    fun `a malformed body is a parse error`() {
        assertTrue(body("not json at all").contains("-32700"))
    }

    @Test
    fun `an unknown method is method not found`() {
        assertTrue(body("""{"jsonrpc":"2.0","id":3,"method":"resources/list"}""").contains("-32601"))
    }

    @Test
    fun `a tool failure is an error, not an empty result`() {
        val response = body(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nope","arguments":{}}}""",
        )

        // An agent that reads isError:false and an empty content array concludes the project is empty.
        assertTrue(response, response.contains("-32000"))
    }

    @Test
    fun `a tool call carries its text back`() {
        val response = body(
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"list_projects"}}""",
        )

        assertTrue(response, response.contains("\"type\":\"text\""))
        assertTrue(response, response.contains("\"isError\":false"))
    }

    @Test
    fun `ping answers`() {
        assertTrue(body("""{"jsonrpc":"2.0","id":6,"method":"ping"}""").contains("\"result\""))
    }
}
```

Create `src/test/kotlin/com/devomer/previewgallery/mcp/McpHttpServerTest.kt`:

```kotlin
package com.devomer.previewgallery.mcp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI

class McpHttpServerTest {

    private var server: McpHttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun start(handle: (String) -> DispatchResult): Int {
        val port = freePort()
        server = McpHttpServer(port, handle).also { it.start() }
        return port
    }

    private fun post(port: Int, body: String, origin: String? = null): Pair<Int, String> {
        val connection = URI("http://127.0.0.1:$port/mcp").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        origin?.let { connection.setRequestProperty("Origin", it) }
        connection.outputStream.use { it.write(body.encodeToByteArray()) }
        val status = connection.responseCode
        val text = (if (status < 400) connection.inputStream else connection.errorStream)
            ?.readBytes()?.decodeToString().orEmpty()
        return status to text
    }

    @Test
    fun `health answers while the server runs`() {
        val port = start { DispatchResult.Json("{}") }

        val connection = URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection

        assertEquals(200, connection.responseCode)
    }

    @Test
    fun `a post reaches the dispatcher and its body comes back`() {
        val port = start { request -> DispatchResult.Json("""{"saw":${request.length}}""") }

        val (status, text) = post(port, """{"jsonrpc":"2.0"}""")

        assertEquals(200, status)
        assertEquals("""{"saw":17}""", text)
    }

    @Test
    fun `a notification answers 202 with no body`() {
        val port = start { DispatchResult.NoContent }

        assertEquals(202, post(port, "{}").first)
    }

    @Test
    fun `a request carrying an Origin header is refused`() {
        val port = start { DispatchResult.Json("""{"leaked":true}""") }

        val (status, text) = post(port, "{}", origin = "https://evil.example")

        // A browser always sends Origin and an MCP client never does, so this is what keeps a page the user
        // has open from reading the project's structure off the loopback socket.
        assertEquals(403, status)
        assertEquals(false, text.contains("leaked"))
    }

    @Test
    fun `stop releases the port`() {
        val port = start { DispatchResult.Json("{}") }

        server?.stop()
        server = null

        ServerSocket(port).use { assertEquals(port, it.localPort) }
    }
}
```

- [ ] **Step 2: Write the fixture test**

Create `src/test/kotlin/com/devomer/previewgallery/service/McpServerServiceTest.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The mapping from the IDE's live index onto the flat snapshot the protocol serves — the one part of this
 * feature that cannot be tested without a project.
 */
class McpServerServiceTest : BasePlatformTestCase() {

    fun `test an indexed project maps its previews and its snapshots`() {
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/Widgets.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun WidgetPreview() = PreviewComponent { Widget() }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
            """
            package com.example

            import com.android.tools.screenshot.PreviewTest

            @PreviewTest
            fun Widget_Default_Snapshot() = PreviewComponent { Widget() }
            """.trimIndent(),
        )

        val snapshot = McpServerService.getInstance().snapshots().single { it.name == project.name }

        assertFalse(snapshot.indexing)
        val preview = snapshot.previews.single()
        assertEquals("com.example.WidgetsKt.WidgetPreview", preview.composableFqn)
        assertTrue(preview.covered)
        assertEquals(listOf("com.example.WidgetSnapshotsKt.Widget_Default_Snapshot"), preview.snapshots)
        assertNotNull(preview.line)
        assertTrue(preview.file, preview.file.endsWith("Widgets.kt"))
        assertEquals(1, snapshot.snapshots.size)
    }

    fun `test a project with no preview maps to an empty snapshot rather than failing`() {
        val snapshot = McpServerService.getInstance().snapshots().single { it.name == project.name }

        assertTrue(snapshot.previews.isEmpty())
        assertTrue(snapshot.snapshots.isEmpty())
    }
}
```

If `snapshots()` returns more than one entry because another fixture project is open in the same JVM, the
`single { it.name == project.name }` filter above is what keeps this honest. If the fixture project's `name`
is not unique, select by `path` instead and note it.

- [ ] **Step 3: Run the whole suite**

Sandbox check first, then:

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`

Expected: PASS. Baseline was 400 tests / 55 classes. This phase adds 7 + 8 + 7 + 8 + 5 + 2 = **37 tests and 6
classes**, so expect **437 tests / 61 classes**.

Iterate until green. If an assertion about exact JSON text fails on key order, fix the assertion to check the
key and value together rather than relaxing it to a bare `contains(key)` — the payload shape is the contract
this feature ships.

- [ ] **Step 4: Commit the tests**

```bash
git add src/test/kotlin/com/devomer/previewgallery/mcp/ src/test/kotlin/com/devomer/previewgallery/service/McpServerServiceTest.kt
git commit -F <scratchpad>/pg17-9-msg
```

Message body:

```
[PG17-9] - Test the MCP index server

The protocol half is covered without a fixture, which is what the mcp/ package
boundary was for. Only the index-to-snapshot mapping needs a project.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

- [ ] **Step 5: Code review**

Run a review over the whole feature (`PG17-1..PG17-9`) with `superpowers:requesting-code-review`, or ask the
human to run `/code-review`. Fix what it reports before the gate. Pay attention to: a tool that could write
anything, a path where the `Origin` guard can be bypassed, PSI touched outside a read action, and any `!!`.

---

## Manual gate

Against `hepsi-android`, from a `runIde` sandbox, after the review:

1. Open the gallery. The toolbar has a new globe icon. Click it: the dialog says **Not running**.
2. Press **Start**. The status becomes `Running on http://localhost:7891/mcp`.
3. From a terminal: `curl -s http://localhost:7891/health` prints `ok`.
4. `curl -s -XPOST http://localhost:7891/mcp -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'` lists four tools.
5. `curl -s -XPOST -H 'Origin: https://evil.example' http://localhost:7891/mcp -d '{}'` is refused with 403.
6. Point a client at it — the dialog's snippet — and ask it which composables have no snapshot. The answer
   matches what the coverage toggle shows in the tree.
7. `coverage_report` returns the same document the toolbar's export writes for the same project.
8. Restart the IDE. The server comes back up on its own, because it was left on.
9. Open a second project. `list_projects` reports both, and a tool call without `project` errors naming them.
10. Press **Stop**. `curl http://localhost:7891/health` fails to connect.

## Roadmap

After the gate passes, mark **F8** shipped in `docs/snapshot-testing-roadmap.md`: annotate its heading the way
F1, H1 and F2 are annotated, drop it from the priority table so F3 becomes item 1, and record in the F8 section
whether it makes F3 unnecessary — that was the open question the roadmap asked this feature to answer. Commit
as `[PG17-10] - Record the MCP server in the roadmap`.
