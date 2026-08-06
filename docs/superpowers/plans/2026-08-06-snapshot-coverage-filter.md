# Snapshot Coverage Filter and Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A toolbar toggle that hides every preview which already has a snapshot, and an action that writes the project's coverage to a markdown file.

**Architecture:** Two pure objects — `PreviewCoverageFilter` (one `filter` call, mirroring `PreviewModuleFilter`) and `CoverageReport` (rows in, markdown string out) — plus the UI to drive them. The two toolbar toggles share a new `PersistentToggleAction` base rather than duplicating their `PropertiesComponent` plumbing. Nothing new is computed: `SnapshotCoverage` is already resolved per row and already drawn by the tree.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install at `platformLocalPath`) · JUnit 4 · `BasePlatformTestCase`

**Spec:** [2026-08-06-snapshot-coverage-filter-design.md](../specs/2026-08-06-snapshot-coverage-filter-design.md)

## Global Constraints

- **Never use the Kotlin `!!` operator.** Use `?:`, `?.let`, `requireNotNull` or an explicit null check.
- **All source, comments, docs and test names in English.**
- **Do not add explanatory line comments inside function bodies**, except for a decision a reader would otherwise undo. KDoc on new public declarations is expected — this codebase's KDoc documents *why*, not *what*.
- Commit message pattern: `[PG16-N] - Task name` (this feature's task ids are `PG16-2` … `PG16-5`; `PG16-0` is the design spec and `PG16-1` is this plan).
- Commit trailer on every commit: `Co-Authored-By: Claude MODEL <noreply@anthropic.com>`, where `MODEL` is replaced by the model named in **your own** system prompt, with no brackets around it — `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`. Never copy another task's value: the trailer records who wrote that commit.
- `SnapshotCoverage`, `SnapshotCoverageResolver`, `PreviewIndexService`, `PreviewTreeModelBuilder`, `PreviewTreeCellRenderer` and every rendering component are **not** modified.
- The report never reads the filtered view. It takes the panel's unfiltered `entries` (spec D5).
- Both icons used here are verified present in this SDK: `AllIcons.General.InspectionsWarning` and `AllIcons.ToolbarDecorator.Export` (checked with `javap` against `Android Studio.app/Contents/lib/app.jar`). Do not substitute an icon you have not verified.
- Tests needing a project or PSI use `BasePlatformTestCase` with backticked names starting `test `. Pure-logic tests use plain JUnit 4 (`@Test` + `org.junit.Assert`).
- **Build/test command:** `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`. The plain `./gradlew test` serves stale cached test bytecode after a signature change and `clean` alone does not fix it; `--max-workers=1 --no-parallel` is required because `:instrumentCode` and `:instrumentTestCode` race under parallel execution. Do **not** run `./gradlew runIde` — the human runs that gate, and no Gradle task may run while a `runIde` sandbox is live.
- Baseline before Task 1: **384 tests / 52 classes**, 0 failures.

---

## File Structure

**Create**

| File | Responsibility |
|---|---|
| `src/main/kotlin/com/devomer/previewgallery/search/PreviewCoverageFilter.kt` | Drop every row that is not `Uncovered` |
| `src/main/kotlin/com/devomer/previewgallery/service/CoverageReport.kt` | Rows → markdown string, pure |
| `src/main/kotlin/com/devomer/previewgallery/ui/PersistentToggleAction.kt` | The `PropertiesComponent`-backed toggle both filters share |
| `src/main/kotlin/com/devomer/previewgallery/ui/CoverageFilterToggleAction.kt` | The toolbar toggle |
| `src/main/kotlin/com/devomer/previewgallery/ui/CoverageReportAction.kt` | Save dialog + write |
| `src/test/kotlin/com/devomer/previewgallery/search/PreviewCoverageFilterTest.kt` | The filter, pure |
| `src/test/kotlin/com/devomer/previewgallery/service/CoverageReportTest.kt` | The markdown format, pure |
| `src/test/kotlin/com/devomer/previewgallery/ui/PersistentToggleActionTest.kt` | Persistence and key isolation |

**Modify**

| File | Change |
|---|---|
| `ui/ModuleFilterToggleAction.kt` | Extends `PersistentToggleAction`; behaviour unchanged |
| `ui/PreviewGalleryPanel.kt` | `applyFilter` gains one stage; the toolbar gains two actions |
| `src/main/resources/messages/PreviewGalleryBundle.properties` | Three new keys |
| `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt` | Filter cases added |

---

### Task 1 (`PG16-2`): The coverage filter

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/search/PreviewCoverageFilter.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/search/PreviewCoverageFilterTest.kt`

**Interfaces:**
- Consumes: `PreviewRow.coverage: SnapshotCoverage` (already on the interface); `SnapshotCoverage.Covered(count)`, `SnapshotCoverage.Uncovered`, `SnapshotCoverage.NotApplicable`.
- Produces: `PreviewCoverageFilter.apply(rows: List<T>, enabled: Boolean): List<T>` where `T : PreviewRow`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/devomer/previewgallery/search/PreviewCoverageFilterTest.kt`:

```kotlin
package com.devomer.previewgallery.search

import com.devomer.previewgallery.model.SnapshotCoverage
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewCoverageFilterTest {

    private val covered = testRow(displayName = "CoveredPreview").copy(coverage = SnapshotCoverage.Covered(2))
    private val uncovered = testRow(displayName = "UncoveredPreview").copy(coverage = SnapshotCoverage.Uncovered)
    private val notApplicable = testRow(displayName = "OtherModulePreview")

    private val rows = listOf(covered, uncovered, notApplicable)

    @Test
    fun `disabled passes every row through unchanged`() {
        assertEquals(rows, PreviewCoverageFilter.apply(rows, enabled = false))
    }

    @Test
    fun `enabled keeps only the uncovered rows`() {
        assertEquals(listOf(uncovered), PreviewCoverageFilter.apply(rows, enabled = true))
    }

    @Test
    fun `a module that never adopted screenshot testing is not work to do`() {
        // NotApplicable means the module has no src/screenshotTest at all, so the question has no answer for
        // it — a work queue holding every such module is one nobody reads (spec D2).
        assertEquals(emptyList<Any>(), PreviewCoverageFilter.apply(listOf(notApplicable), enabled = true))
    }

    @Test
    fun `an empty input yields an empty result either way`() {
        assertEquals(emptyList<Any>(), PreviewCoverageFilter.apply(emptyList<TestPreviewRow>(), enabled = true))
        assertEquals(emptyList<Any>(), PreviewCoverageFilter.apply(emptyList<TestPreviewRow>(), enabled = false))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel --tests "com.devomer.previewgallery.search.PreviewCoverageFilterTest"`
Expected: compilation failure — `Unresolved reference: PreviewCoverageFilter`.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/devomer/previewgallery/search/PreviewCoverageFilter.kt`:

```kotlin
package com.devomer.previewgallery.search

import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.model.SnapshotCoverage

/**
 * Restricts rows to the previews whose composable has no snapshot test — the work queue the coverage badge
 * provokes and cannot itself answer.
 *
 * [SnapshotCoverage.NotApplicable] is dropped alongside [SnapshotCoverage.Covered] (spec D2). It is not
 * "uncovered": it means the module has no `src/screenshotTest` at all, so the question has no answer there,
 * and a queue holding every module that never adopted screenshot testing is one nobody reads. That is the
 * same reasoning that leaves those rows unbadged.
 */
object PreviewCoverageFilter {

    fun <T : PreviewRow> apply(rows: List<T>, enabled: Boolean): List<T> =
        if (enabled) rows.filter { it.coverage is SnapshotCoverage.Uncovered } else rows
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel --tests "com.devomer.previewgallery.search.PreviewCoverageFilterTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

Write the message to a file and use `git commit -F` — a heredoc broke on an apostrophe in an earlier phase.

```bash
git add src/main/kotlin/com/devomer/previewgallery/search/PreviewCoverageFilter.kt src/test/kotlin/com/devomer/previewgallery/search/PreviewCoverageFilterTest.kt
git commit -F /tmp/pg16-2-msg
```

Message body:

```
[PG16-2] - Filter previews down to the uncovered ones

NotApplicable is dropped with Covered: a module with no src/screenshotTest has
no answer to the question, and a work queue holding every such module is one
nobody reads.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 2 (`PG16-3`): The markdown report

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/service/CoverageReport.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/service/CoverageReportTest.kt`

**Interfaces:**
- Consumes: `PreviewRow.coverage`, `PreviewRow.moduleName`, `PreviewRow.indexed.composableFqn`.
- Produces: `CoverageReport.markdown(rows: List<PreviewRow>): String`. Takes no `Project` and no `VirtualFile`, so it is testable without a fixture.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/devomer/previewgallery/service/CoverageReportTest.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.SnapshotCoverage
import com.devomer.previewgallery.search.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageReportTest {

    private fun covered(name: String, module: String = "app.main") =
        testRow(displayName = name, functionName = name, moduleName = module)
            .copy(coverage = SnapshotCoverage.Covered(1))

    private fun uncovered(name: String, module: String = "app.main") =
        testRow(displayName = name, functionName = name, moduleName = module)
            .copy(coverage = SnapshotCoverage.Uncovered)

    @Test
    fun `one module reports its totals and its uncovered rows`() {
        val markdown = CoverageReport.markdown(listOf(covered("APreview"), uncovered("BPreview")))

        assertEquals(
            """
            # Snapshot coverage

            **1/2 covered** across 1 module

            ## app.main — 1/2

            - `com.example.FooKt.BPreview`

            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun `modules are listed alphabetically and counted independently`() {
        val markdown = CoverageReport.markdown(
            listOf(
                uncovered("ZPreview", module = "zeta.main"),
                covered("APreview", module = "alpha.main"),
                uncovered("BPreview", module = "alpha.main"),
            ),
        )

        assertTrue(markdown, markdown.contains("**1/3 covered** across 2 modules"))
        assertTrue(markdown, markdown.indexOf("## alpha.main — 1/2") < markdown.indexOf("## zeta.main — 0/1"))
    }

    @Test
    fun `uncovered rows are listed by FQN and sorted`() {
        val markdown = CoverageReport.markdown(listOf(uncovered("ZPreview"), uncovered("APreview")))

        assertTrue(markdown, markdown.indexOf("`com.example.FooKt.APreview`") < markdown.indexOf("`com.example.FooKt.ZPreview`"))
    }

    @Test
    fun `a module that never adopted screenshot testing is left out of the body and the totals`() {
        val markdown = CoverageReport.markdown(
            listOf(covered("APreview"), testRow(displayName = "CPreview", moduleName = "legacy.main")),
        )

        // NotApplicable modules would otherwise pin the percentage near zero forever: the reference project
        // has 1371 modules and one of them has adopted screenshot testing (spec D6).
        assertTrue(markdown, markdown.contains("**1/1 covered** across 1 module"))
        assertFalse(markdown, markdown.contains("legacy.main"))
    }

    @Test
    fun `a fully covered module keeps its heading and loses its bullets`() {
        val markdown = CoverageReport.markdown(listOf(covered("APreview"), covered("BPreview")))

        assertTrue(markdown, markdown.contains("## app.main — 2/2"))
        assertFalse(markdown, markdown.contains("- `"))
    }

    @Test
    fun `a project with no applicable module says so instead of emitting an empty report`() {
        val markdown = CoverageReport.markdown(listOf(testRow(displayName = "APreview")))

        assertTrue(markdown, markdown.contains("No module in this project has a `src/screenshotTest` source set."))
        assertFalse(markdown, markdown.contains("##"))
    }

    @Test
    fun `no rows at all is the same as no applicable module`() {
        assertEquals(CoverageReport.markdown(emptyList()), CoverageReport.markdown(listOf(testRow())))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel --tests "com.devomer.previewgallery.service.CoverageReportTest"`
Expected: compilation failure — `Unresolved reference: CoverageReport`.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/devomer/previewgallery/service/CoverageReport.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.model.SnapshotCoverage

/**
 * The project's snapshot coverage as markdown, for pasting into a ticket or a channel before the consuming
 * project's CI job exists.
 *
 * Pure on purpose: it takes rows, not a `Project`, so the format is pinned by tests that need no IDE fixture.
 *
 * Only **applicable** modules are counted and listed (spec D6). A module with no `src/screenshotTest` has no
 * answer to the question, and the reference project has 1371 modules of which one has adopted screenshot
 * testing — a percentage taken over all of them would read as zero forever and be discarded as noise.
 */
object CoverageReport {

    private const val TITLE = "# Snapshot coverage"
    private const val NOTHING_APPLICABLE =
        "No module in this project has a `src/screenshotTest` source set."

    fun markdown(rows: List<PreviewRow>): String {
        val applicable = rows.filter { it.coverage !is SnapshotCoverage.NotApplicable }
        if (applicable.isEmpty()) return "$TITLE\n\n$NOTHING_APPLICABLE\n"

        val byModule = applicable.groupBy { it.moduleName }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val covered = applicable.count { it.coverage is SnapshotCoverage.Covered }
        return buildString {
            appendLine(TITLE)
            appendLine()
            appendLine("**$covered/${applicable.size} covered** across ${byModule.size} ${moduleWord(byModule.size)}")
            byModule.forEach { (module, moduleRows) ->
                appendLine()
                appendLine("## $module — ${moduleRows.count { it.coverage is SnapshotCoverage.Covered }}/${moduleRows.size}")
                val uncovered = moduleRows
                    .filter { it.coverage is SnapshotCoverage.Uncovered }
                    .map { it.indexed.composableFqn }
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
                if (uncovered.isNotEmpty()) {
                    appendLine()
                    uncovered.forEach { appendLine("- `$it`") }
                }
            }
        }
    }

    private fun moduleWord(count: Int): String = if (count == 1) "module" else "modules"
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel --tests "com.devomer.previewgallery.service.CoverageReportTest"`
Expected: PASS, 7 tests.

If the exact-match test in Step 1 fails on whitespace, do **not** relax it to a `contains` check — the format is the contract this task ships. Compare the two strings character by character and fix the builder.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/service/CoverageReport.kt src/test/kotlin/com/devomer/previewgallery/service/CoverageReportTest.kt
git commit -F /tmp/pg16-3-msg
```

Message body:

```
[PG16-3] - Format the coverage report

Only applicable modules are counted: a percentage taken over 1371 modules of
which one has adopted screenshot testing reads as zero forever.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 3 (`PG16-4`): The toolbar toggle, wired

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/PersistentToggleAction.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/CoverageFilterToggleAction.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/ui/PersistentToggleActionTest.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/ModuleFilterToggleAction.kt` (rewritten in full below)
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` — `applyFilter` and the toolbar group
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties`
- Test: `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt`

**Interfaces:**
- Consumes: `PreviewCoverageFilter.apply(rows, enabled)` from Task 1.
- Produces: `PersistentToggleAction(project, storageKey, text, icon, onToggle)` (abstract) with `PersistentToggleAction.isEnabled(project: Project, storageKey: String): Boolean` in its companion; `CoverageFilterToggleAction(project, onToggle)` with `CoverageFilterToggleAction.isEnabled(project: Project): Boolean`. `ModuleFilterToggleAction`'s public shape is unchanged: same constructor, same `isEnabled(project)`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/devomer/previewgallery/ui/PersistentToggleActionTest.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The persistence both toolbar filters share. `ModuleFilterToggleAction` carried this behaviour before it was
 * extracted and had no test of its own, so this file is what keeps the extraction honest.
 */
class PersistentToggleActionTest : BasePlatformTestCase() {

    fun `test a toggle defaults to off`() {
        assertFalse(ModuleFilterToggleAction.isEnabled(project))
        assertFalse(CoverageFilterToggleAction.isEnabled(project))
    }

    fun `test setting a toggle is readable through its own isEnabled`() {
        val action = ModuleFilterToggleAction(project) {}

        action.setSelected(createEvent(action), true)

        assertTrue(ModuleFilterToggleAction.isEnabled(project))
    }

    fun `test the two toggles do not see each other's state`() {
        val moduleFilter = ModuleFilterToggleAction(project) {}

        moduleFilter.setSelected(createEvent(moduleFilter), true)

        assertTrue(ModuleFilterToggleAction.isEnabled(project))
        assertFalse(CoverageFilterToggleAction.isEnabled(project))
    }

    fun `test toggling calls back`() {
        var calls = 0
        val action = CoverageFilterToggleAction(project) { calls++ }

        action.setSelected(createEvent(action), true)

        assertEquals(1, calls)
    }

    private fun createEvent(action: com.intellij.openapi.actionSystem.AnAction) =
        com.intellij.testFramework.TestActionEvent.createTestEvent(action)
}
```

Add to `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt`:

```kotlin
    fun `test the coverage filter hides the previews that already have a snapshot`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()
        assertTrue(panel.visibleRowLabelsForTest().toString(), panel.visibleRowLabelsForTest().contains("WidgetPreview"))

        CoverageFilterToggleAction(project) {}.setSelected(
            com.intellij.testFramework.TestActionEvent.createTestEvent(),
            true,
        )
        panel.applyQueryForTest("")

        // WidgetPreview has Widget_Default_Snapshot, so it is covered and drops out of the work queue.
        assertFalse(panel.visibleRowLabelsForTest().toString(), panel.visibleRowLabelsForTest().contains("WidgetPreview"))
    }

    fun `test the coverage filter leaves an uncovered preview in place`() {
        projectWithSnapshot()
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/Lonely.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun LonelyPreview() = PreviewComponent { Lonely() }
            """.trimIndent(),
        )
        val panel = panel()
        panel.reloadSynchronously()

        CoverageFilterToggleAction(project) {}.setSelected(
            com.intellij.testFramework.TestActionEvent.createTestEvent(),
            true,
        )
        panel.applyQueryForTest("")

        assertTrue(panel.visibleRowLabelsForTest().toString(), panel.visibleRowLabelsForTest().contains("LonelyPreview"))
    }

    fun `test the coverage filter and the module filter compose`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()

        // Both on: the module filter alone would keep WidgetPreview (it is in the fixture's only module) and
        // the coverage filter alone would drop it. Neither may win outright (spec D4).
        ModuleFilterToggleAction(project) {}.setSelected(
            com.intellij.testFramework.TestActionEvent.createTestEvent(),
            true,
        )
        CoverageFilterToggleAction(project) {}.setSelected(
            com.intellij.testFramework.TestActionEvent.createTestEvent(),
            true,
        )
        panel.applyQueryForTest("")

        assertFalse(panel.visibleRowLabelsForTest().toString(), panel.visibleRowLabelsForTest().contains("WidgetPreview"))
    }
```

If the module filter makes the tree empty in this fixture regardless of coverage — `ActiveModuleTracker` has
no open editor to read an active module from, and `PreviewModuleFilter` returns nothing for a null active
module — then the assertion above passes for the wrong reason. In that case open the preview file with
`myFixture.openFileInEditor(...)` before toggling, so the active module is the fixture's, and report it in
your notes.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel --tests "com.devomer.previewgallery.ui.*"`
Expected: compilation failure — `Unresolved reference: CoverageFilterToggleAction`.

- [ ] **Step 3: Add the bundle keys**

In `src/main/resources/messages/PreviewGalleryBundle.properties`, after the `action.moduleFilter.text` line:

```properties
action.coverageFilter.text=Show only previews without a snapshot
action.coverageReport.text=Export snapshot coverage report
report.saveFailed=Failed to write the coverage report
```

`action.coverageReport.text` and `report.saveFailed` belong to Task 4; adding all three now keeps the bundle edited once.

- [ ] **Step 4: Extract the shared toggle**

Create `src/main/kotlin/com/devomer/previewgallery/ui/PersistentToggleAction.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import javax.swing.Icon

/**
 * A toolbar toggle whose state lives in [PropertiesComponent], so it is per project and survives a restart.
 *
 * The two filters in this window differ only in their storage key, their text and their icon. Keeping the
 * persistence in one place is what stops them from drifting apart — a change to how one is remembered cannot
 * apply to only one of them.
 */
abstract class PersistentToggleAction(
    private val project: Project,
    private val storageKey: String,
    text: String,
    icon: Icon,
    private val onToggle: () -> Unit,
) : ToggleAction(text, text, icon),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(event: AnActionEvent): Boolean = isEnabled(project, storageKey)

    override fun setSelected(event: AnActionEvent, selected: Boolean) {
        PropertiesComponent.getInstance(project).setValue(storageKey, selected)
        onToggle()
    }

    companion object {
        /** Read without constructing the action: the panel asks for the state on every filter pass. */
        fun isEnabled(project: Project, storageKey: String): Boolean =
            PropertiesComponent.getInstance(project).getBoolean(storageKey, false)
    }
}
```

Replace `src/main/kotlin/com/devomer/previewgallery/ui/ModuleFilterToggleAction.kt` in full:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project

class ModuleFilterToggleAction(
    project: Project,
    onToggle: () -> Unit,
) : PersistentToggleAction(
    project,
    KEY,
    PreviewGalleryBundle.message("action.moduleFilter.text"),
    AllIcons.General.Filter,
    onToggle,
) {

    companion object {
        private const val KEY = "com.devomer.previewgallery.moduleFilter"

        fun isEnabled(project: Project): Boolean = PersistentToggleAction.isEnabled(project, KEY)
    }
}
```

The `PersistentToggleAction.` qualifier is required: a subclass's companion does not inherit the base companion's members.

Create `src/main/kotlin/com/devomer/previewgallery/ui/CoverageFilterToggleAction.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project

/**
 * Hides every preview that already has a snapshot, leaving the work queue.
 *
 * A different icon from the module filter beside it: two toggles carrying `AllIcons.General.Filter` would be
 * told apart only by position.
 */
class CoverageFilterToggleAction(
    project: Project,
    onToggle: () -> Unit,
) : PersistentToggleAction(
    project,
    KEY,
    PreviewGalleryBundle.message("action.coverageFilter.text"),
    AllIcons.General.InspectionsWarning,
    onToggle,
) {

    companion object {
        private const val KEY = "com.devomer.previewgallery.coverageFilter"

        fun isEnabled(project: Project): Boolean = PersistentToggleAction.isEnabled(project, KEY)
    }
}
```

- [ ] **Step 5: Wire the filter into the panel**

In `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`, inside `applyFilter`, replace the block that computes `visible`:

```kotlin
        val moduleFilterOn = ModuleFilterToggleAction.isEnabled(project)
        val moduleFiltered = PreviewModuleFilter.apply(
            entries,
            moduleTracker.activeModuleName,
            moduleFilterOn,
        )
        val visible = PreviewCoverageFilter.apply(moduleFiltered, CoverageFilterToggleAction.isEnabled(project))
```

Leave the `visibleOrphans` block exactly as it is, and extend the comment above it to name the reason:

```kotlin
        // The orphan branch goes through the same module filter as the previews: "show only the active editor's
        // module" that still showed another module's snapshots would not be that filter at all. The query is
        // applied to the two independently, inside the builder (PG13 spec D11). It deliberately does NOT go
        // through the coverage filter: an orphan is a snapshot matching no preview, which is the mirror of what
        // that filter selects for and the same kind of defect, so hiding it would make the filter tell half the
        // truth (PG16 spec D3).
```

Add the import `com.devomer.previewgallery.search.PreviewCoverageFilter`.

In the toolbar group, add the toggle after the module filter:

```kotlin
            ModuleFilterToggleAction(project) { applyFilter() },
            CoverageFilterToggleAction(project) { applyFilter() },
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`
Expected: PASS, the whole suite. Baseline was 384 tests / 52 classes. Task 1 added 4 tests and a class, Task 2 added 7 and a class, and this task adds 4 + 3 = 7 tests and one class, so expect **402 tests / 55 classes**.

If `PersistentToggleActionTest` leaks state between tests — a toggle set in one test still on in the next — note it and set the key back to `false` at the end of the test that sets it, rather than reordering the tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PersistentToggleAction.kt src/main/kotlin/com/devomer/previewgallery/ui/CoverageFilterToggleAction.kt src/main/kotlin/com/devomer/previewgallery/ui/ModuleFilterToggleAction.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt src/main/resources/messages/PreviewGalleryBundle.properties src/test/kotlin/com/devomer/previewgallery/ui/PersistentToggleActionTest.kt src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt
git commit -F /tmp/pg16-4-msg
```

Message body:

```
[PG16-4] - Hide the previews that already have a snapshot

The two toolbar filters now share one PropertiesComponent-backed base rather
than carrying a copy each. The orphan branch stays visible: it is the mirror of
what the filter selects for.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 4 (`PG16-5`): Write the report to a file

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/CoverageReportAction.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` — the toolbar group

**Interfaces:**
- Consumes: `CoverageReport.markdown(rows: List<PreviewRow>): String` from Task 2; the bundle keys `action.coverageReport.text` and `report.saveFailed` added in Task 3.
- Produces: `CoverageReportAction(project: Project, rows: () -> List<PreviewRow>)`.

- [ ] **Step 1: Write the action**

Create `src/main/kotlin/com/devomer/previewgallery/ui/CoverageReportAction.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.service.CoverageReport
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Writes the project's snapshot coverage to a markdown file the user picks.
 *
 * [rows] is the panel's **unfiltered** list, never what the tree is showing (spec D5): a report titled
 * "snapshot coverage" that quietly described whatever the search box had narrowed it to is a number that
 * lands in a ticket with nobody remembering why it was wrong. Reading the panel's own list rather than
 * asking the index again also keeps this off a read action it would have to take on the EDT.
 */
class CoverageReportAction(
    private val project: Project,
    private val rows: () -> List<PreviewRow>,
) : DumbAwareAction(
    PreviewGalleryBundle.message("action.coverageReport.text"),
    PreviewGalleryBundle.message("action.coverageReport.text"),
    AllIcons.ToolbarDecorator.Export,
) {

    override fun actionPerformed(event: AnActionEvent) {
        val descriptor = FileSaverDescriptor(PreviewGalleryBundle.message("action.coverageReport.text"), "", "md")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as VirtualFile?, DEFAULT_NAME) ?: return
        try {
            wrapper.file.writeText(CoverageReport.markdown(rows()))
        } catch (e: IOException) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Compose Preview Gallery")
                .createNotification(PreviewGalleryBundle.message("report.saveFailed"), NotificationType.WARNING)
                .notify(project)
        }
    }

    private companion object {
        private const val DEFAULT_NAME = "snapshot-coverage.md"
    }
}
```

- [ ] **Step 2: Add it to the toolbar**

In `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`, in the toolbar group, after the coverage toggle:

```kotlin
            CoverageFilterToggleAction(project) { applyFilter() },
            CoverageReportAction(project) { entries },
```

`entries` is the panel's unfiltered row list, which is what spec D5 requires. Do **not** pass the filtered
`visible` list — that is local to `applyFilter` and is exactly what the report must not describe.

- [ ] **Step 3: Run the full suite**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`
Expected: PASS, unchanged from Task 3's count. This task adds no test: `CoverageReport.markdown` is already
pinned by Task 2, and what remains here is the platform's save dialog, which has no logic of its own.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/CoverageReportAction.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt
git commit -F /tmp/pg16-5-msg
```

Message body:

```
[PG16-5] - Export the coverage report

The action takes the panel's unfiltered rows, never the tree's: a coverage
number that quietly described whatever the search box had narrowed it to is
worse than no number.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

## Manual gate

Against `hepsi-android`, from a `runIde` sandbox, after Task 4:

1. Open the gallery. The toolbar has two filter toggles with different icons and distinct tooltips.
2. Press the coverage toggle. Every preview that showed a covered badge disappears; the previews reading
   `· no snapshot` remain. Modules with no `src/screenshotTest` disappear entirely.
3. The orphan branch — snapshots matching no preview — is still visible with the toggle on.
4. Turn on the module filter as well. The two compose: the tree shows the active module's uncovered previews
   only, and neither toggle overrides the other.
5. Type in the search box with the toggle on. The query narrows what is left rather than resetting the filter.
6. With both filters on and a query typed, run Export snapshot coverage report. The written file describes
   the **whole project**, not the three rows on screen.
7. Restart the IDE. Both toggles come back in the state they were left in.

## Roadmap

After the gate passes, mark **F2** shipped in `docs/snapshot-testing-roadmap.md`: annotate its heading the way
F1 and H1 are annotated, drop it from the priority table so F8 becomes item 1, and note in the F2 section that
only the uncovered direction was built and why. Commit as `[PG16-6] - Record the coverage filter in the roadmap`.
