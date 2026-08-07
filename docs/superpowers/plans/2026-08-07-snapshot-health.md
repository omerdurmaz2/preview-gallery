# Snapshot Health Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Report the snapshot tests that pass without testing anything — a blank committed golden, and a row named after a component its body never calls.

**Architecture:** Two pure objects (`SnapshotHealth` for the name rule, `HealthReport` for its markdown) plus one that touches disk (`GoldenInspector`, decoding reference PNGs through the existing `RenderedImageInspector.isBlank`). The findings reach a human through a `## Health` section appended to the coverage export, and an agent through a new MCP tool. Nothing new is computed about coverage; nothing existing is rewritten.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install at `platformLocalPath`) · `javax.imageio` · JUnit 4 · `BasePlatformTestCase`

**Spec:** [2026-08-07-snapshot-health-design.md](../specs/2026-08-07-snapshot-health-design.md)

## Global Constraints

- **Never use the Kotlin `!!` operator.** Use `?:`, `?.let`, `requireNotNull` or an explicit null check.
- **All source, comments, docs and test names in English.**
- **Do not add explanatory line comments inside function bodies**, except for a decision a reader would otherwise undo. KDoc on new public declarations is expected — this codebase's KDoc documents *why*, not *what*.
- **Tests come last.** Tasks 1–5 are implementation only. Every test in this feature is written in the **Test & Review** phase at the end, then the code review runs. Do not write a test earlier, and do not skip the phase.
- **Nothing under `mcp/` may import `com.intellij`.** `SnapshotHealthTool` may import the plugin's own `model`/`service` packages only if those files carry no `com.intellij` import — check before writing, as `CoverageReportTool` did.
- Commit message pattern: `[PG18-N] - Task name` (`PG18-0` is the design spec, `PG18-1` is this plan, tasks are `PG18-2` … `PG18-6`, and the final phase is `PG18-7`).
- Commit trailer on every commit: `Co-Authored-By: Claude MODEL <noreply@anthropic.com>`, where `MODEL` is replaced by the model named in **your own** system prompt, with no brackets — e.g. `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`. Never copy another task's value.
- **`RenderedImageInspector` is not modified.** It is `internal` and lives in `render/`, which makes it visible from `service/` in the same module. Call `isBlank`; do not reimplement or widen it.
- `CoverageReport`, `PreviewIndexService`, `SnapshotCoverageResolver`, `ReferenceImageLocator` and every rendering component are **not** modified.
- **No new toolbar button** (spec Non-Goals) and **no tree badge**.
- **Verified against this SDK** (do not substitute unverified values):
  - `RenderedImageInspector.isBlank(image: BufferedImage): Boolean` — `internal object` in `com.devomer.previewgallery.render`.
  - `ReferenceImageLocator.locate(entry: PreviewEntry, roots: List<ReferenceRoots.Root>): List<ReferenceImage>`, and `model.ReferenceImage(sourceSet: String, variant: String, file: VirtualFile)`.
  - `ImageIO.read` returns null for a stream no decoder recognises and throws `IOException` for an IO failure — `PreviewGalleryPanel.readImage` already handles both that way.
  - `PreviewGalleryPanel` holds `private var entries: List<PreviewEntry>` and `private var orphanSnapshots: List<PreviewEntry>`.
- **Build/test command:** `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`. During tasks 1–5 use `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel` — there are no tests to run yet.
- **Never run `./gradlew` while a `runIde` sandbox is live.** Check with exactly `pgrep -f "idea.plugin.in.sandbox.mode=true"` and `pgrep -f "gradlew.*runIde"` before every Gradle invocation; if either prints a pid, stop and report. Do **not** run `./gradlew runIde` — the human runs that gate.
- Baseline before Task 1: **460 tests / 63 classes**, 0 failures.

---

## File Structure

**Create**

| File | Responsibility |
|---|---|
| `src/main/kotlin/com/devomer/previewgallery/service/SnapshotHealth.kt` | The name rule and its finding type, pure |
| `src/main/kotlin/com/devomer/previewgallery/service/GoldenInspector.kt` | Decode reference PNGs, apply `isBlank` |
| `src/main/kotlin/com/devomer/previewgallery/service/HealthReport.kt` | Findings → the `## Health` markdown section, pure |
| `src/main/kotlin/com/devomer/previewgallery/mcp/tools/SnapshotHealthTool.kt` | `snapshot_health` |
| `src/test/kotlin/com/devomer/previewgallery/service/SnapshotHealthTest.kt` | The name rule, including its false-positive guards |
| `src/test/kotlin/com/devomer/previewgallery/service/HealthReportTest.kt` | The section's format |
| `src/test/kotlin/com/devomer/previewgallery/service/GoldenInspectorTest.kt` | Real PNGs through a fixture |
| `src/test/kotlin/com/devomer/previewgallery/mcp/SnapshotHealthToolTest.kt` | The JSON payload |

**Modify**

| File | Change |
|---|---|
| `mcp/ProjectSnapshot.kt` | `PreviewFacts` gains `functionName` and `targets` |
| `mcp/ToolRegistry.kt` | One more tool, and a second provider for the goldens |
| `service/McpServerService.kt` | Fill the new fields, and supply the goldens provider |
| `ui/CoverageReportAction.kt` | Take orphans too, append the health section |
| `ui/PreviewGalleryPanel.kt` | Pass `orphanSnapshots` to the action |

---

### Task 1 (`PG18-2`): The name rule

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/service/SnapshotHealth.kt`

**Interfaces:**
- Consumes: `PreviewRow.indexed.functionName`, `.composableFqn`, `.targets`; `PreviewRow.moduleName`.
- Produces: `SnapshotHealth.NameFinding(composableFqn: String, moduleName: String, namedAfter: String, shows: List<String>)`; `SnapshotHealth.Result(findings: List<NameFinding>, skipped: Int)`; `SnapshotHealth.check(rows: List<PreviewRow>): Result`; `SnapshotHealth.stems(functionName: String): List<String>` (internal, so the test can pin the stem derivation directly).

- [ ] **Step 1: Create the rule**

Create `src/main/kotlin/com/devomer/previewgallery/service/SnapshotHealth.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.PreviewRow

/**
 * Finds rows whose name claims a component their body never calls.
 *
 * The specimen this exists for: `DeleteSelectedProductsDialog_Preview` in the reference project rebuilds the
 * dialog's insides with `PrimusDialog(...)` instead of calling `DeleteSelectedProductsDialog`, and its snapshot
 * copies the mistake. Both render a full, convincing PNG, and both would stay green if the component they are
 * named after broke — they never touch it.
 *
 * The naive form of this check fires on every `DarkThemePreview`, so the rule asks the project's own data
 * whether a stem is a real component before it accuses anyone (spec, "The name check").
 */
object SnapshotHealth {

    /** [namedAfter] is the stem that is a known component; [shows] is what the body actually calls. */
    data class NameFinding(
        val composableFqn: String,
        val moduleName: String,
        val namedAfter: String,
        val shows: List<String>,
    )

    /** [skipped] counts rows whose `targets` came back empty — the extractor resolved nothing, so the rule
     *  has no opinion. Reported rather than hidden: a silent extraction failure would otherwise read as a
     *  clean bill of health (spec D4). */
    data class Result(val findings: List<NameFinding>, val skipped: Int)

    private val SUFFIXES = listOf("_Preview", "Preview", "_Snapshot", "Snapshot")

    fun check(rows: List<PreviewRow>): Result {
        val vocabulary = rows.flatMapTo(HashSet()) { it.indexed.targets }
        val findings = mutableListOf<NameFinding>()
        var skipped = 0

        rows.forEach { row ->
            val targets = row.indexed.targets
            if (targets.isEmpty()) {
                skipped++
                return@forEach
            }
            val stems = stems(row.indexed.functionName)
            if (stems.any { it in targets }) return@forEach
            val claimed = stems.firstOrNull { it in vocabulary } ?: return@forEach
            findings += NameFinding(
                composableFqn = row.indexed.composableFqn,
                moduleName = row.moduleName,
                namedAfter = claimed,
                shows = targets,
            )
        }
        return Result(findings.sortedBy { it.composableFqn }, skipped)
    }

    /**
     * Every prefix of the name that could be a component, longest last.
     *
     * `Foo_Bar_Default_Snapshot` yields `Foo`, `Foo_Bar` and `Foo_Bar_Default`, because a component named
     * `Foo_Bar` and one named `Foo` cannot be told apart from the name alone. A match on any of them clears
     * the row, which is the direction that fails safe.
     */
    internal fun stems(functionName: String): List<String> {
        val trimmed = SUFFIXES.firstOrNull { functionName.endsWith(it) }
            ?.let { functionName.removeSuffix(it) }
            ?: functionName
        if (trimmed.isEmpty()) return emptyList()
        val parts = trimmed.split('_').filter { it.isNotEmpty() }
        if (parts.size <= 1) return listOf(trimmed)
        return parts.indices.map { parts.take(it + 1).joinToString("_") }
    }
}
```

- [ ] **Step 2: Compile**

Sandbox check first (both `pgrep` patterns), then:

Run: `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

Write the message to a file under the session scratchpad and use `git commit -F` — a heredoc broke on an apostrophe in an earlier phase.

```bash
git add src/main/kotlin/com/devomer/previewgallery/service/SnapshotHealth.kt
git commit -F <scratchpad>/pg18-2-msg
```

Message body:

```
[PG18-2] - Flag a row named after a component it never calls

The vocabulary is the project's own call targets, so a DarkThemePreview with no
DarkTheme anywhere is a description rather than a claim and is left alone.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 2 (`PG18-3`): The golden inspector

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/service/GoldenInspector.kt`

**Interfaces:**
- Consumes: `RenderedImageInspector.isBlank(image: BufferedImage): Boolean` from `com.devomer.previewgallery.render`; `model.ReferenceImage(sourceSet, variant, file)`.
- Produces: `GoldenInspector.BlankFinding(composableFqn: String, moduleName: String, variant: String, path: String)`; `GoldenInspector.Result(findings: List<BlankFinding>, unreadable: Int)`; `GoldenInspector.inspect(images: List<GoldenInspector.Candidate>): Result`; `GoldenInspector.Candidate(composableFqn: String, moduleName: String, image: ReferenceImage)`.

- [ ] **Step 1: Create the inspector**

Create `src/main/kotlin/com/devomer/previewgallery/service/GoldenInspector.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.ReferenceImage
import com.devomer.previewgallery.render.RenderedImageInspector
import com.intellij.openapi.diagnostic.thisLogger
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Reads committed reference PNGs and reports the ones that cannot be showing anything.
 *
 * The blank rule itself is [RenderedImageInspector.isBlank], unchanged and uncopied: it already encodes
 * "degenerate in size, or every pixel identical", and a second copy for reference images would drift from the
 * one the render path enforces (spec D2).
 *
 * Decoding must not happen on the EDT or inside a read action — `RenderPipeline` calls holding the read lock
 * across long work a prime freeze suspect, and this decodes two device-resolution PNGs per snapshot. The
 * caller is responsible for that; this object only does the work it is handed.
 */
object GoldenInspector {

    /** One reference image to check, carrying enough of its owner to name it in a report. */
    data class Candidate(val composableFqn: String, val moduleName: String, val image: ReferenceImage)

    data class BlankFinding(
        val composableFqn: String,
        val moduleName: String,
        val variant: String,
        val path: String,
    )

    /** [unreadable] counts images that could not be decoded: reported, never fatal (spec D7). */
    data class Result(val findings: List<BlankFinding>, val unreadable: Int)

    fun inspect(images: List<Candidate>): Result {
        val findings = mutableListOf<BlankFinding>()
        var unreadable = 0

        images.forEach { candidate ->
            val decoded = read(candidate)
            if (decoded == null) {
                unreadable++
                return@forEach
            }
            if (RenderedImageInspector.isBlank(decoded)) {
                findings += BlankFinding(
                    composableFqn = candidate.composableFqn,
                    moduleName = candidate.moduleName,
                    variant = candidate.image.variant,
                    path = candidate.image.file.path,
                )
            }
        }
        return Result(findings.sortedBy { it.path }, unreadable)
    }

    private fun read(candidate: Candidate) =
        try {
            candidate.image.file.inputStream.use { ImageIO.read(it) }
        } catch (e: IOException) {
            thisLogger().warn("Could not read reference image ${candidate.image.file.path}", e)
            null
        }
}
```

- [ ] **Step 2: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL. If `RenderedImageInspector` does not resolve from `service/`, stop and report — it is `internal` in the same module and should, and working around that by copying the rule is forbidden by the Global Constraints.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/service/GoldenInspector.kt
git commit -F <scratchpad>/pg18-3-msg
```

Message body:

```
[PG18-3] - Read the committed goldens for blank ones

The blank rule stays where the render path already enforces it; this only feeds
it reference PNGs and counts the ones that would not decode.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 3 (`PG18-4`): The report section

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/service/HealthReport.kt`

**Interfaces:**
- Consumes: `SnapshotHealth.Result` and `SnapshotHealth.NameFinding` (Task 1); `GoldenInspector.Result` and `GoldenInspector.BlankFinding` (Task 2).
- Produces: `HealthReport.markdown(names: SnapshotHealth.Result, goldens: GoldenInspector.Result): String`.

- [ ] **Step 1: Create the report**

Create `src/main/kotlin/com/devomer/previewgallery/service/HealthReport.kt`:

```kotlin
package com.devomer.previewgallery.service

/**
 * The `## Health` section of the coverage export, and the same text the MCP tool serves.
 *
 * Appended to the coverage document rather than written as a second file (spec D5): "how healthy are this
 * project's snapshots" is one question, and two files each holding half the answer is how a number gets quoted
 * without its caveat.
 *
 * A clean project still gets a section. "Checked and clean" and "not checked" are different facts, and an
 * absent section reads as the second.
 */
object HealthReport {

    private const val TITLE = "## Health"

    fun markdown(names: SnapshotHealth.Result, goldens: GoldenInspector.Result): String = buildString {
        appendLine(TITLE)
        appendLine()
        appendLine(summary(names, goldens))
        if (goldens.findings.isNotEmpty()) {
            appendLine()
            appendLine("### Blank goldens")
            appendLine()
            goldens.findings.forEach { finding ->
                appendLine("- `${finding.composableFqn}` — ${finding.variant}")
                appendLine("  - ${finding.path}")
            }
        }
        if (names.findings.isNotEmpty()) {
            appendLine()
            appendLine("### Named after something they do not show")
            appendLine()
            names.findings.forEach { finding ->
                appendLine("- `${finding.composableFqn}`")
                appendLine("  - named after `${finding.namedAfter}`, shows ${finding.shows.joinToString { "`$it`" }}")
            }
        }
    }

    private fun summary(names: SnapshotHealth.Result, goldens: GoldenInspector.Result): String {
        val notes = buildList {
            if (names.skipped > 0) add("${names.skipped} rows skipped (no call targets resolved)")
            if (goldens.unreadable > 0) add("${goldens.unreadable} reference images could not be read")
        }
        val suffix = if (notes.isEmpty()) "" else " · ${notes.joinToString(" · ")}"
        if (goldens.findings.isEmpty() && names.findings.isEmpty()) {
            return "No blank goldens, and every row shows what it is named after.$suffix"
        }
        val counts = buildList {
            if (goldens.findings.isNotEmpty()) add("${goldens.findings.size} ${blankWord(goldens.findings.size)}")
            if (names.findings.isNotEmpty()) add("${names.findings.size} ${namedWord(names.findings.size)}")
        }
        return "**${counts.joinToString(" · ")}**$suffix"
    }

    private fun blankWord(count: Int): String = if (count == 1) "blank golden" else "blank goldens"

    private fun namedWord(count: Int): String =
        if (count == 1) "row named after something it does not show" else "rows named after something they do not show"
}
```

- [ ] **Step 2: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/service/HealthReport.kt
git commit -F <scratchpad>/pg18-4-msg
```

Message body:

```
[PG18-4] - Write the health section

A clean project still gets the section: "checked and clean" and "not checked"
are different facts, and an absent section reads as the second.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 4 (`PG18-5`): Wire it into the export

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/CoverageReportAction.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` — the toolbar group only

**Interfaces:**
- Consumes: `SnapshotHealth.check`, `GoldenInspector.inspect`, `HealthReport.markdown` from Tasks 1–3; `ReferenceImageLocator.locate(entry, roots)`; `ReferenceRoots.of(moduleDirectory)`; `ModuleDirectoryResolver.resolve(project, file)`.
- Produces: `CoverageReportAction(project: Project, rows: () -> List<PreviewEntry>, orphans: () -> List<PreviewEntry>)`.

- [ ] **Step 1: Read what you are wiring into**

Read these first and follow their real signatures:

- `src/main/kotlin/com/devomer/previewgallery/ui/CoverageReportAction.kt` — the current shape, including that it is deliberately **not** `DumbAware` and already moves the write to a pooled thread
- `src/main/kotlin/com/devomer/previewgallery/service/ReferenceImageLocator.kt` — `locate(entry: PreviewEntry, roots: List<ReferenceRoots.Root>)`
- `src/main/kotlin/com/devomer/previewgallery/service/ModuleDirectoryResolver.kt` — the resolver is `resolve(project, file)`, **not** `of(...)`
- `src/main/kotlin/com/devomer/previewgallery/service/McpServerService.kt` — its `referenceImages` shows the locate/roots call already assembled correctly, inside a read action

Note the constructor change: `rows` becomes `() -> List<PreviewEntry>` (it was `() -> List<PreviewRow>`) because the golden half needs the `VirtualFile` a `PreviewRow` does not carry. `CoverageReport.markdown` takes `List<PreviewRow>` and `PreviewEntry` implements it, so that call still compiles unchanged.

- [ ] **Step 2: Collect the candidates and append the section**

In `CoverageReportAction.kt`, change the constructor and the body. The whole file after the edit:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.service.CoverageReport
import com.devomer.previewgallery.service.GoldenInspector
import com.devomer.previewgallery.service.HealthReport
import com.devomer.previewgallery.service.ModuleDirectoryResolver
import com.devomer.previewgallery.service.ReferenceRoots
import com.devomer.previewgallery.service.ReferenceImageLocator
import com.devomer.previewgallery.service.SnapshotHealth
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Writes the project's snapshot coverage and health to a markdown file the user picks.
 *
 * [rows] is the panel's **unfiltered** list, never what the tree is showing (PG16 spec D5): a report titled
 * "snapshot coverage" that quietly described whatever the search box had narrowed it to is a number that
 * lands in a ticket with nobody remembering why it was wrong.
 *
 * [orphans] is passed alongside because a snapshot matching no preview is the population most likely to be
 * misnamed, and because the name rule learns which stems are real components from every row's call targets —
 * including theirs (PG18 spec D9).
 *
 * Deliberately **not** `DumbAware`: during indexing the panel holds no rows, and a file on disk saying the
 * project has no preview is a worse answer than a disabled button.
 */
class CoverageReportAction(
    private val project: Project,
    private val rows: () -> List<PreviewEntry>,
    private val orphans: () -> List<PreviewEntry>,
) : AnAction(
    PreviewGalleryBundle.message("action.coverageReport.text"),
    PreviewGalleryBundle.message("action.coverageReport.text"),
    AllIcons.ToolbarDecorator.Export,
) {

    override fun actionPerformed(event: AnActionEvent) {
        val descriptor = FileSaverDescriptor(PreviewGalleryBundle.message("action.coverageReport.text"), "", "md")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as VirtualFile?, DEFAULT_NAME) ?: return
        // Read the rows on the EDT, where the panel owns them, and leave everything else off it: the grouping,
        // the PNG decoding and the write all scale with the project.
        val previews = rows()
        val orphanRows = orphans()
        ApplicationManager.getApplication().executeOnPooledThread {
            val document = try {
                buildDocument(previews, orphanRows)
            } catch (e: IOException) {
                thisLogger().warn("Failed to build the coverage report", e)
                notifyFailure()
                return@executeOnPooledThread
            }
            try {
                wrapper.file.writeText(document)
            } catch (e: IOException) {
                thisLogger().warn("Failed to write the coverage report to ${wrapper.file}", e)
                notifyFailure()
            }
        }
    }

    private fun buildDocument(previews: List<PreviewEntry>, orphanRows: List<PreviewEntry>): String {
        val all = previews + previews.flatMap { it.snapshots } + orphanRows
        val names = SnapshotHealth.check(all)
        val goldens = GoldenInspector.inspect(candidates(previews.flatMap { it.snapshots } + orphanRows))
        return CoverageReport.markdown(previews) + "\n" + HealthReport.markdown(names, goldens)
    }

    /**
     * Resolving a module directory and its reference roots reads the project model, so it takes a read action;
     * decoding the images afterwards deliberately does not (see [GoldenInspector]).
     */
    private fun candidates(snapshots: List<PreviewEntry>): List<GoldenInspector.Candidate> =
        ReadAction.compute<List<GoldenInspector.Candidate>, RuntimeException> {
            snapshots.flatMap { snapshot ->
                val moduleDirectory = ModuleDirectoryResolver.resolve(project, snapshot.file)
                    ?: return@flatMap emptyList()
                ReferenceImageLocator.locate(snapshot, ReferenceRoots.of(moduleDirectory)).map {
                    GoldenInspector.Candidate(snapshot.indexed.composableFqn, snapshot.moduleName, it)
                }
            }
        }

    private fun notifyFailure() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Compose Preview Gallery")
            .createNotification(PreviewGalleryBundle.message("report.saveFailed"), NotificationType.WARNING)
            .notify(project)
    }

    private companion object {
        private const val DEFAULT_NAME = "snapshot-coverage.md"
    }
}
```

If `ReferenceImageLocator.locate` turns out to need something `PreviewEntry` does not carry, stop and report
rather than inventing a second locator — `McpServerService.referenceImages` calls it successfully today and is
the reference for how.

- [ ] **Step 3: Pass the orphans from the panel**

In `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`, in the `DefaultActionGroup(...)`
around line 211, the action currently reads:

```kotlin
            CoverageReportAction(project) { entries },
```

Replace it with:

```kotlin
            CoverageReportAction(project, { entries }, { orphanSnapshots }),
```

`entries` and `orphanSnapshots` are both `List<PreviewEntry>` fields on the panel, so no conversion is needed.

- [ ] **Step 4: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/CoverageReportAction.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt
git commit -F <scratchpad>/pg18-5-msg
```

Message body:

```
[PG18-5] - Put health in the coverage export

One document, because "how healthy are this project's snapshots" is one
question. The orphans go in with the previews: they are the rows most likely to
be misnamed, and the name rule learns its vocabulary from their call targets.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 5 (`PG18-6`): Serve it over MCP

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/mcp/ProjectSnapshot.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/mcp/tools/SnapshotHealthTool.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/mcp/ToolRegistry.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/service/McpServerService.kt`

**Interfaces:**
- Consumes: `SnapshotHealth.check` (Task 1); `GoldenInspector.inspect` (Task 2); the existing `ProjectSnapshot`, `PreviewFacts`, `SnapshotFacts`.
- Produces: `SnapshotHealthTool.NAME` = `"snapshot_health"`, `SnapshotHealthTool.DESCRIPTION`, `SnapshotHealthTool.execute(snapshot: ProjectSnapshot, module: String?, blankGoldens: List<GoldenInspector.BlankFinding>): String`; `McpServerService.blankGoldens(projectName: String): List<GoldenInspector.BlankFinding>`; `ToolRegistry(snapshots, blankGoldens)`.

- [ ] **Step 1: Carry the facts the name rule needs**

In `src/main/kotlin/com/devomer/previewgallery/mcp/ProjectSnapshot.kt`, add `val functionName: String` and
`val targets: List<String> = emptyList()` to `PreviewFacts`. Read the file first and match its existing KDoc
style. `SnapshotFacts` already carries `targets`; do not duplicate it.

**Do not add a blank-golden field to `SnapshotFacts`.** `ProjectSnapshot` is rebuilt on every tool call — it is
deliberately not cached (F8 spec D11) — so a field there would decode every reference PNG on every
`list_previews` and `list_projects` too. The goldens come in through their own provider instead (Step 2).

- [ ] **Step 2: Fill the fields, and add the goldens provider**

In `src/main/kotlin/com/devomer/previewgallery/service/McpServerService.kt`:

1. `previewFacts` sets `functionName = entry.indexed.functionName` and `targets = entry.indexed.targets`.
2. Add a public method that decodes on demand, called only by the health tool:

```kotlin
    /**
     * The blank goldens of one open project, decoded on demand.
     *
     * Deliberately not part of [ProjectSnapshot]: that is rebuilt on every tool call, so a field there would
     * decode every reference PNG for `list_previews` too. Only the health tool pays this cost, and only when
     * an agent asks for it.
     *
     * The locate half needs the project model and takes a read action; the decode half must not hold one
     * (spec D7), so the two are split rather than nested.
     */
    fun blankGoldens(projectName: String): List<GoldenInspector.BlankFinding> {
        val project = ProjectManager.getInstance().openProjects
            .firstOrNull { !it.isDisposed && it.name == projectName }
            ?: return emptyList()
        val candidates = ReadAction.compute<List<GoldenInspector.Candidate>, RuntimeException> {
            if (DumbService.isDumb(project)) return@compute emptyList()
            val index = PreviewIndexService.getInstance(project)
            val snapshots = index.findAll().flatMap { it.snapshots } + index.findOrphanSnapshots()
            snapshots.flatMap { snapshot ->
                val moduleDirectory = ModuleDirectoryResolver.resolve(project, snapshot.file)
                    ?: return@flatMap emptyList()
                ReferenceImageLocator.locate(snapshot, ReferenceRoots.of(moduleDirectory)).map {
                    GoldenInspector.Candidate(snapshot.indexed.composableFqn, snapshot.moduleName, it)
                }
            }
        }
        return GoldenInspector.inspect(candidates).findings
    }
```

Read the file's existing `referenceImages` before writing this — it already assembles the locate/roots call
correctly, and this must match it rather than invent a second way.

- [ ] **Step 3: Create the tool**

Create `src/main/kotlin/com/devomer/previewgallery/mcp/tools/SnapshotHealthTool.kt`:

```kotlin
package com.devomer.previewgallery.mcp.tools

import com.devomer.previewgallery.mcp.ProjectSnapshot
import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.IndexedPreview
import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.model.SnapshotCoverage
import com.devomer.previewgallery.service.GoldenInspector
import com.devomer.previewgallery.service.SnapshotHealth
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The snapshot tests that pass without testing anything.
 *
 * An agent about to write snapshots for a module should be able to see that the module's existing ones are
 * suspect — otherwise it copies the pattern it finds. The blank-golden half is decided when the snapshot is
 * taken, so this tool reports it rather than reading any file.
 */
object SnapshotHealthTool {

    const val NAME = "snapshot_health"

    const val DESCRIPTION =
        "Reports snapshot tests that pass without testing anything: committed reference PNGs that are blank " +
            "or a single colour, and rows whose name claims a composable their body never calls. Filter with " +
            "`module` (exact match). Call before writing new snapshots in a module, so an existing mistake " +
            "does not get copied."

    fun execute(
        snapshot: ProjectSnapshot,
        module: String?,
        blankGoldens: List<GoldenInspector.BlankFinding>,
    ): String {
        val previews = snapshot.previews.filter { module == null || it.moduleName == module }
        val snapshots = snapshot.snapshots.filter { module == null || it.moduleName == module }
        val names = SnapshotHealth.check(previews.map(::asRow) + snapshots.map(::asSnapshotRow))
        val blanks = blankGoldens.filter { module == null || it.moduleName == module }
        return buildJsonObject {
            put(
                "blankGoldens",
                buildJsonArray {
                    blanks.forEach { finding ->
                        add(
                            buildJsonObject {
                                put("snapshotFqn", finding.composableFqn)
                                put("module", finding.moduleName)
                                put("variant", finding.variant)
                                put("path", finding.path)
                            },
                        )
                    }
                },
            )
            put(
                "namedAfterSomethingElse",
                buildJsonArray {
                    names.findings.forEach { finding ->
                        add(
                            buildJsonObject {
                                put("composableFqn", finding.composableFqn)
                                put("module", finding.moduleName)
                                put("namedAfter", finding.namedAfter)
                                put("shows", buildJsonArray { finding.shows.forEach { add(it) } })
                            },
                        )
                    }
                },
            )
            put("skippedRows", names.skipped)
        }.toString()
    }

    private fun asRow(facts: com.devomer.previewgallery.mcp.PreviewFacts): PreviewRow =
        HealthRow(indexed(facts.composableFqn, facts.functionName, facts.packageName, facts.targets), facts.moduleName)

    private fun asSnapshotRow(facts: com.devomer.previewgallery.mcp.SnapshotFacts): PreviewRow {
        val functionName = facts.snapshotFqn.substringAfterLast('.')
        return HealthRow(
            indexed(facts.snapshotFqn, functionName, facts.snapshotFqn.substringBeforeLast('.'), facts.targets),
            facts.moduleName,
        )
    }

    private fun indexed(fqn: String, functionName: String, packageName: String, targets: List<String>) =
        IndexedPreview(
            displayName = functionName,
            functionName = functionName,
            packageName = packageName,
            jvmClassName = fqn.substringBeforeLast('.'),
            composableFqn = fqn,
            offset = 0,
            annotationKind = AnnotationKind.ANDROIDX,
            isPrivate = false,
            hasPreviewParameter = false,
            previewGroup = null,
            unsupportedReason = null,
            targets = targets,
        )

    /** The rule takes `PreviewRow`s and the snapshot carries flat facts, so this is the adapter between them.
     *  Only `functionName`, `composableFqn`, `targets` and `moduleName` are read; the rest is filler the rule
     *  never touches, exactly as in `CoverageReportTool`. */
    private data class HealthRow(
        override val indexed: IndexedPreview,
        override val moduleName: String,
        override val coverage: SnapshotCoverage = SnapshotCoverage.Uncovered,
    ) : PreviewRow
}
```

Import `PreviewFacts` and `SnapshotFacts` properly at the top rather than writing them fully qualified in the
signatures — the fully qualified form above is only to make the types unambiguous while you read.

- [ ] **Step 4: Register it**

In `src/main/kotlin/com/devomer/previewgallery/mcp/ToolRegistry.kt`:

- widen the constructor to `ToolRegistry(private val snapshots: () -> List<ProjectSnapshot>, private val blankGoldens: (String) -> List<GoldenInspector.BlankFinding>)`
- add a `ToolDescriptor(SnapshotHealthTool.NAME, SnapshotHealthTool.DESCRIPTION, schema("project" to STRING, "module" to STRING))` to `descriptors()`
- add `SnapshotHealthTool.NAME` to `KNOWN_TOOLS`
- add the arm `SnapshotHealthTool.NAME -> ToolOutcome.Text(SnapshotHealthTool.execute(project, module, blankGoldens(project.name)))` to `call`

The second provider is a lambda for the same reason the first one is: `mcp/` cannot import `com.intellij`, so
the decode lives in the service and arrives here as a function. It is called **only** from this one arm, which
is the whole point — no other tool pays for decoding PNGs.

Then update the one construction site, `McpServerService.start`, to pass `::blankGoldens` alongside
`::snapshots`.

Read the file first: the tool-name check runs before project resolution, and `list_projects` keeps its own
short-circuit ahead of both.

- [ ] **Step 5: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/mcp/ src/main/kotlin/com/devomer/previewgallery/service/McpServerService.kt
git commit -F <scratchpad>/pg18-6-msg
```

Message body:

```
[PG18-6] - Serve snapshot health over MCP

An agent about to write snapshots for a module should see that the module's
existing ones are suspect, or it copies the pattern it finds.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

## Test & Review phase (`PG18-7`)

Everything above shipped without a test. This phase is where the feature earns them, and it is not optional.

- [ ] **Step 1: The name rule**

Create `src/test/kotlin/com/devomer/previewgallery/service/SnapshotHealthTest.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.devomer.previewgallery.search.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The specimen is real: `DeleteSelectedProductsDialog_Preview` in the reference project rebuilds the dialog
 * with `PrimusDialog` instead of calling the component it is named after, and a sibling snapshot does call it.
 */
class SnapshotHealthTest {

    private val misnamedPreview = testRow(
        displayName = "DeleteSelectedProductsDialog_Preview",
        functionName = "DeleteSelectedProductsDialog_Preview",
        targets = listOf("PrimusDialog"),
    )

    private val honestSnapshot = testRow(
        displayName = "DeleteSelectedProductsDialog_Direct_Snapshot",
        functionName = "DeleteSelectedProductsDialog_Direct_Snapshot",
        isSnapshotTest = true,
        targets = listOf("DeleteSelectedProductsDialog"),
    )

    @Test
    fun `a row named after a component it does not call is flagged with both sides`() {
        val result = SnapshotHealth.check(listOf(misnamedPreview, honestSnapshot))

        val finding = result.findings.single()
        assertEquals("com.example.FooKt.DeleteSelectedProductsDialog_Preview", finding.composableFqn)
        assertEquals("DeleteSelectedProductsDialog", finding.namedAfter)
        assertEquals(listOf("PrimusDialog"), finding.shows)
    }

    @Test
    fun `the row that does call it is not flagged`() {
        val result = SnapshotHealth.check(listOf(misnamedPreview, honestSnapshot))

        assertTrue(result.findings.toString(), result.findings.none { it.composableFqn.endsWith("_Direct_Snapshot") })
    }

    @Test
    fun `a name that is not a component anywhere is a description, not a claim`() {
        // Nothing in the project renders `DarkTheme`, so `DarkThemePreview` is telling you about the
        // configuration rather than claiming to show a component.
        val row = testRow(
            displayName = "DarkThemePreview",
            functionName = "DarkThemePreview",
            targets = listOf("HomeScreen"),
        )

        assertEquals(emptyList<SnapshotHealth.NameFinding>(), SnapshotHealth.check(listOf(row)).findings)
    }

    @Test
    fun `a preview that shows what it is named after passes`() {
        val row = testRow(displayName = "WidgetPreview", functionName = "WidgetPreview", targets = listOf("Widget"))

        assertEquals(emptyList<SnapshotHealth.NameFinding>(), SnapshotHealth.check(listOf(row)).findings)
    }

    @Test
    fun `a longer stem clears the row when the shorter one would not`() {
        // A component really named `Foo_Bar` must not be reported for failing to call `Foo`.
        val row = testRow(
            displayName = "Foo_Bar_Default_Snapshot",
            functionName = "Foo_Bar_Default_Snapshot",
            isSnapshotTest = true,
            targets = listOf("Foo_Bar"),
        )
        val other = testRow(displayName = "FooPreview", functionName = "FooPreview", targets = listOf("Foo"))

        assertEquals(emptyList<SnapshotHealth.NameFinding>(), SnapshotHealth.check(listOf(row, other)).findings)
    }

    @Test
    fun `a row with no resolved targets is skipped and counted, not accused`() {
        val row = testRow(displayName = "MysteryPreview", functionName = "MysteryPreview")

        val result = SnapshotHealth.check(listOf(row))

        assertEquals(emptyList<SnapshotHealth.NameFinding>(), result.findings)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `stems yield every prefix, so the longest can clear a row`() {
        assertEquals(listOf("Widget"), SnapshotHealth.stems("WidgetPreview"))
        assertEquals(listOf("DeleteSelectedProductsDialog"), SnapshotHealth.stems("DeleteSelectedProductsDialog_Preview"))
        assertEquals(
            listOf("Foo", "Foo_Bar", "Foo_Bar_Default"),
            SnapshotHealth.stems("Foo_Bar_Default_Snapshot"),
        )
    }

    @Test
    fun `findings are ordered so two runs of the same project diff cleanly`() {
        val z = testRow(displayName = "ZPreview", functionName = "ZPreview", targets = listOf("Other"))
        val a = testRow(displayName = "APreview", functionName = "APreview", targets = listOf("Other"))
        val vocabulary = testRow(displayName = "Seed", functionName = "Seed", targets = listOf("Z", "A", "Other"))

        val findings = SnapshotHealth.check(listOf(z, a, vocabulary)).findings

        assertEquals(findings.map { it.composableFqn }.sorted(), findings.map { it.composableFqn })
    }
}
```

If `testRow` does not accept `functionName` or `targets`, read
`src/test/kotlin/com/devomer/previewgallery/search/TestPreviewRow.kt` and use the parameters it really has.

- [ ] **Step 2: The report**

Create `src/test/kotlin/com/devomer/previewgallery/service/HealthReportTest.kt`:

```kotlin
package com.devomer.previewgallery.service

import org.junit.Assert.assertTrue
import org.junit.Test

class HealthReportTest {

    private val blank = GoldenInspector.BlankFinding(
        composableFqn = "com.example.SheetSnapshotsKt.Sheet_Collapsed_Snapshot",
        moduleName = "app.main",
        variant = "phone",
        path = "/src/reference/Sheet_Collapsed_0.png",
    )

    private val misnamed = SnapshotHealth.NameFinding(
        composableFqn = "com.example.DialogKt.DeleteSelectedProductsDialog_Preview",
        moduleName = "app.main",
        namedAfter = "DeleteSelectedProductsDialog",
        shows = listOf("PrimusDialog"),
    )

    @Test
    fun `both kinds render under their own heading`() {
        val markdown = HealthReport.markdown(
            SnapshotHealth.Result(listOf(misnamed), skipped = 0),
            GoldenInspector.Result(listOf(blank), unreadable = 0),
        )

        assertTrue(markdown, markdown.startsWith("## Health"))
        assertTrue(markdown, markdown.contains("### Blank goldens"))
        assertTrue(markdown, markdown.contains("/src/reference/Sheet_Collapsed_0.png"))
        assertTrue(markdown, markdown.contains("### Named after something they do not show"))
        assertTrue(markdown, markdown.contains("named after `DeleteSelectedProductsDialog`, shows `PrimusDialog`"))
    }

    @Test
    fun `a clean project says so rather than going silent`() {
        val markdown = HealthReport.markdown(
            SnapshotHealth.Result(emptyList(), skipped = 0),
            GoldenInspector.Result(emptyList(), unreadable = 0),
        )

        assertTrue(markdown, markdown.contains("No blank goldens, and every row shows what it is named after."))
        assertTrue(markdown, !markdown.contains("###"))
    }

    @Test
    fun `the skipped count is reported, because it is the report's own confidence`() {
        val markdown = HealthReport.markdown(
            SnapshotHealth.Result(emptyList(), skipped = 14),
            GoldenInspector.Result(emptyList(), unreadable = 2),
        )

        assertTrue(markdown, markdown.contains("14 rows skipped (no call targets resolved)"))
        assertTrue(markdown, markdown.contains("2 reference images could not be read"))
    }

    @Test
    fun `one finding of each kind reads in the singular`() {
        val markdown = HealthReport.markdown(
            SnapshotHealth.Result(listOf(misnamed), skipped = 0),
            GoldenInspector.Result(listOf(blank), unreadable = 0),
        )

        assertTrue(markdown, markdown.contains("1 blank golden ·"))
        assertTrue(markdown, markdown.contains("1 row named after something it does not show"))
    }
}
```

- [ ] **Step 3: The MCP tool**

Create `src/test/kotlin/com/devomer/previewgallery/mcp/SnapshotHealthToolTest.kt`:

```kotlin
package com.devomer.previewgallery.mcp

import com.devomer.previewgallery.mcp.tools.SnapshotHealthTool
import com.devomer.previewgallery.service.GoldenInspector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotHealthToolTest {

    private val misnamed = PreviewFacts(
        composableFqn = "com.example.DialogKt.DeleteSelectedProductsDialog_Preview",
        displayName = "DeleteSelectedProductsDialog_Preview",
        functionName = "DeleteSelectedProductsDialog_Preview",
        moduleName = "app.main",
        packageName = "com.example",
        file = "/src/Dialog.kt",
        line = 58,
        isPrivate = false,
        hasPreviewParameter = false,
        unsupportedReason = null,
        covered = true,
        targets = listOf("PrimusDialog"),
    )

    private val honest = SnapshotFacts(
        snapshotFqn = "com.example.DialogSnapshotsKt.DeleteSelectedProductsDialog_Direct_Snapshot",
        moduleName = "app.main",
        file = "/src/DialogSnapshots.kt",
        line = 12,
        targets = listOf("DeleteSelectedProductsDialog"),
        orphan = true,
    )

    private val blankGolden = SnapshotFacts(
        snapshotFqn = "com.example.SheetSnapshotsKt.Sheet_Collapsed_Snapshot",
        moduleName = "app.main",
        file = "/src/SheetSnapshots.kt",
        line = 30,
        targets = listOf("Sheet"),
    )

    private val goldens = listOf(
        GoldenInspector.BlankFinding(
            composableFqn = "com.example.SheetSnapshotsKt.Sheet_Collapsed_Snapshot",
            moduleName = "app.main",
            variant = "phone",
            path = "/src/reference/Sheet_Collapsed_0.png",
        ),
    )

    private val project = ProjectSnapshot(
        name = "demo",
        path = "/src",
        indexing = false,
        previews = listOf(misnamed),
        snapshots = listOf(honest, blankGolden),
    )

    @Test
    fun `a name finding carries both sides`() {
        val json = SnapshotHealthTool.execute(project, module = null, blankGoldens = goldens)

        assertTrue(json, json.contains("\"namedAfter\":\"DeleteSelectedProductsDialog\""))
        assertTrue(json, json.contains("PrimusDialog"))
    }

    @Test
    fun `a blank golden carries the path to look at`() {
        val json = SnapshotHealthTool.execute(project, module = null, blankGoldens = goldens)

        assertTrue(json, json.contains("/src/reference/Sheet_Collapsed_0.png"))
    }

    @Test
    fun `the module filter applies to both halves`() {
        val json = SnapshotHealthTool.execute(project, module = "other.main", blankGoldens = goldens)

        assertTrue(json, json.contains("\"blankGoldens\":[]"))
        assertTrue(json, json.contains("\"namedAfterSomethingElse\":[]"))
    }

    @Test
    fun `the skipped count reaches the agent`() {
        val json = SnapshotHealthTool.execute(project, module = null, blankGoldens = goldens)

        assertFalse(json, json.contains("\"skippedRows\":null"))
        assertTrue(json, json.contains("\"skippedRows\":"))
    }
}
```

If `PreviewFacts` / `SnapshotFacts` take their parameters in a different order or with different names, read
`src/main/kotlin/com/devomer/previewgallery/mcp/ProjectSnapshot.kt` and use the real ones.

- [ ] **Step 4: The golden inspector**

Create `src/test/kotlin/com/devomer/previewgallery/service/GoldenInspectorTest.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.ReferenceImage
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Real PNG bytes through the real decoder: the point of this check is what is on disk, so a mocked image
 * would test nothing that can break.
 */
class GoldenInspectorTest : BasePlatformTestCase() {

    private fun png(width: Int, height: Int, paint: (BufferedImage) -> Unit): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        paint(image)
        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, "png", bytes)
        return bytes.toByteArray()
    }

    private fun write(name: String, bytes: ByteArray): VirtualFile {
        val file = myFixture.addFileToProject("reference/$name", "").virtualFile
        runWriteAction { file.setBinaryContent(bytes) }
        return file
    }

    private fun candidate(file: VirtualFile) = GoldenInspector.Candidate(
        composableFqn = "com.example.FooKt.Foo_Default_Snapshot",
        moduleName = "app.main",
        image = ReferenceImage(sourceSet = "screenshotTest", variant = "phone", file = file),
    )

    fun `test a single-colour golden is reported blank`() {
        val flat = write("flat.png", png(40, 40) { image ->
            for (y in 0 until 40) for (x in 0 until 40) image.setRGB(x, y, 0xFF112233.toInt())
        })

        val result = GoldenInspector.inspect(listOf(candidate(flat)))

        assertEquals(1, result.findings.size)
        assertEquals("phone", result.findings.single().variant)
        assertTrue(result.findings.single().path, result.findings.single().path.endsWith("flat.png"))
    }

    fun `test a golden with two colours is left alone`() {
        val drawn = write("drawn.png", png(40, 40) { image ->
            for (y in 0 until 40) for (x in 0 until 40) image.setRGB(x, y, 0xFF112233.toInt())
            image.setRGB(20, 20, 0xFFFFFFFF.toInt())
        })

        assertEquals(emptyList<GoldenInspector.BlankFinding>(), GoldenInspector.inspect(listOf(candidate(drawn))).findings)
    }

    fun `test a file that is not an image is counted rather than thrown`() {
        val garbage = write("garbage.png", "not a png at all".toByteArray())

        val result = GoldenInspector.inspect(listOf(candidate(garbage)))

        assertEquals(emptyList<GoldenInspector.BlankFinding>(), result.findings)
        assertEquals(1, result.unreadable)
    }
}
```

`runWriteAction` comes from `com.intellij.openapi.application.runWriteAction`. If `setBinaryContent` is not
reachable that way in this SDK, write the bytes with `myFixture.tempDirFixture` instead and note the change.

- [ ] **Step 5: Run the whole suite**

Sandbox check first, then:

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`

Expected: PASS. Baseline was 460 tests / 63 classes. This phase adds 8 + 4 + 4 + 3 = **19 tests and 4
classes**, so expect **479 tests / 67 classes**.

Iterate until green. If an assertion about exact markdown fails on spacing, fix the builder rather than
relaxing the assertion — the format is the contract this feature ships.

- [ ] **Step 6: Commit the tests**

```bash
git add src/test/kotlin/com/devomer/previewgallery/service/ src/test/kotlin/com/devomer/previewgallery/mcp/SnapshotHealthToolTest.kt
git commit -F <scratchpad>/pg18-7-msg
```

Message body:

```
[PG18-7] - Test the snapshot health checks

The name rule's false-positive guards get the most of it: a DarkThemePreview
with no DarkTheme anywhere, a component genuinely named Foo_Bar, and a row whose
targets the extractor could not resolve.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

- [ ] **Step 7: Code review**

Run a review over the whole feature (`PG18-2..PG18-7`) with `superpowers:requesting-code-review`, or ask the
human to run `/code-review`. Fix what it reports before the gate. Pay attention to: PNG decoding that ended up
inside a read action or on the EDT, a name-rule path that could accuse a row the extractor failed on, and any
`!!`.

---

## Manual gate

Against `hepsi-android`, from a `runIde` sandbox, after the review:

1. Open the gallery, run **Export snapshot coverage report**. The written file has both a coverage section and
   a `## Health` section.
2. The health section names `DeleteSelectedProductsDialog_Preview` — the specimen this feature was designed
   around. It should say it is named after `DeleteSelectedProductsDialog` and shows `PrimusDialog`.
3. Read the rest of the name findings. **This is the calibration run:** if it is mostly noise, the rule is too
   loose and tightens before this ships. Report what it found.
4. The skipped count is present and plausible — the reference project has rows whose targets do not resolve.
5. With the MCP server on: `curl -s -XPOST http://localhost:7891/mcp -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"snapshot_health","arguments":{"module":"hepsi-android.features.favorites.ui.main"}}}'`
   returns the same findings the report shows for that module.
6. Blank goldens: if the run reports none, that is a real answer, not a broken check — confirm by pointing the
   check at a deliberately blanked PNG if you want certainty.

## Roadmap

After the gate passes, mark **F7** shipped in `docs/snapshot-testing-roadmap.md`: annotate its heading the way
F1, H1, F2 and F8 are annotated, drop it from the priority table so the F5 spike becomes item 1, and record
what the calibration run found — how many name findings, and how many were noise. Commit as
`[PG18-8] - Record the health checks in the roadmap`.
