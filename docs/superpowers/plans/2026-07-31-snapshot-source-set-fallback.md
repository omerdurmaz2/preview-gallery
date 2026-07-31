# Snapshot Source Set Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make snapshot rows independent of whether the `screenshotTest` source set reached the IDE's project model, by discovering and parsing those files from the VFS instead of querying them out of `FileBasedIndex`.

**Architecture:** A new `SnapshotSourceScanner` probes each module's content roots for a `src/screenshotTest` directory, attributes it to the module that owns the previews, walks it for `.kt` files and runs the existing `PreviewPsiScanner` over each. `PreviewIndexService` drops the index's own `isSnapshotTest` rows and takes them from the scanner instead. `ScreenshotModuleDetector` disappears — the scanner answers the same question.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install at `platformLocalPath`) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase`

**Spec:** [2026-07-31-snapshot-source-set-fallback-design.md](../specs/2026-07-31-snapshot-source-set-fallback-design.md)

## Global Constraints

- **Never use the Kotlin `!!` operator.** Use `?:`, `?.let`, or an explicit null check.
- **All source, comments, docs and test names in English.**
- Commit message pattern: `[PG14-N] - Task name` (this feature's task ids are `PG14-2` … `PG14-4`; `PG14-0` and `PG14-1` are the design spec and this plan).
- `PreviewPsiScanner` and `TargetExtractor` are **not** modified. Only their input channel changes.
- `SnapshotCoverageResolver`, every `ui/` component, `ReferenceImageLocator` and `ReferenceStripView` are **not** modified.
- No `com.android.tools.*` import outside `render/`.
- `ExternalSystemApiUtil` is **not available** in this SDK — verified by scanning every jar under `Contents/lib` and `Contents/plugins/*/lib`. Do not reach for it; the content-root probe exists because of that.
- Tests needing a project or PSI use `BasePlatformTestCase` with backticked names starting `test `. Pure-logic tests use plain JUnit 4 (`@Test` + `org.junit.Assert`).
- **Build/test command:** `./gradlew clean test --no-build-cache --rerun-tasks`. The plain `./gradlew test` serves stale cached test bytecode after a signature change and `clean` alone does not fix it. Do **not** run `./gradlew runIde` — the human runs that gate.

---

## File Structure

**Create**

| File | Responsibility |
|---|---|
| `src/main/kotlin/com/devomer/previewgallery/service/SnapshotSourceScanner.kt` | Probe content roots, attribute directories to modules, parse the `.kt` files into snapshot rows |

**Modify**

| File | Change |
|---|---|
| `service/PreviewIndexService.kt` | Drop the index's `isSnapshotTest` rows; take them from the scanner; applicable modules come from the scanner |

**Delete**

| File | Why |
|---|---|
| `service/ScreenshotModuleDetector.kt` | Its two inputs are now one; the scanner answers "is this module applicable?" by construction (spec D5) |
| `src/test/kotlin/com/devomer/previewgallery/service/ScreenshotModuleDetectorTest.kt` | Tests of the deleted corroboration logic; the cases that still matter move to the scanner's tests |

---

### Task 1: Discover and parse the `screenshotTest` sources

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/service/SnapshotSourceScanner.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/service/SnapshotSourceScannerTest.kt`

**Interfaces:**
- Consumes: `PreviewPsiScanner.scan(file: KtFile): List<IndexedPreview>`, `IndexedPreview.isSnapshotTest`, `PreviewEntry(indexed, moduleName, file)`.
- Produces:
  - `SnapshotSourceScanner.Source(val moduleName: String, val directory: VirtualFile)`
  - `SnapshotSourceScanner.directories(project: Project): List<Source>`
  - `SnapshotSourceScanner.pickOwningModule(moduleNames: List<String>): String?`
  - `SnapshotSourceScanner.scan(project: Project): List<PreviewEntry>`

- [ ] **Step 1: Write the failing tests**

`pickOwningModule` is pure, so it belongs in its own plain JUnit 4 class rather than inside the
fixture-based one — the project's convention is that pure logic is testable without an IDE fixture:

`src/test/kotlin/com/devomer/previewgallery/service/SnapshotOwningModuleTest.kt`

```kotlin
package com.devomer.previewgallery.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotOwningModuleTest {

    @Test
    fun `the main source-set module wins`() {
        assertEquals(
            "app.features.favorites.ui.main",
            SnapshotSourceScanner.pickOwningModule(
                listOf("app.features.favorites.ui", "app.features.favorites.ui.main"),
            ),
        )
    }

    @Test
    fun `the shortest name wins when no module is a main source set`() {
        assertEquals(
            "app.features.favorites.ui",
            SnapshotSourceScanner.pickOwningModule(
                listOf("app.features.favorites.ui.unitTest", "app.features.favorites.ui"),
            ),
        )
    }

    @Test
    fun `a single module is its own owner`() {
        assertEquals("app", SnapshotSourceScanner.pickOwningModule(listOf("app")))
    }

    @Test
    fun `no modules means no owner`() {
        assertNull(SnapshotSourceScanner.pickOwningModule(emptyList()))
    }
}
```

`src/test/kotlin/com/devomer/previewgallery/service/SnapshotSourceScannerTest.kt` — needs PSI and a
project, so `BasePlatformTestCase`:

```kotlin
package com.devomer.previewgallery.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SnapshotSourceScannerTest : BasePlatformTestCase() {

    private fun addSnapshotFile() {
        myFixture.addFileToProject(
            "src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
            """
            package com.example

            import com.android.tools.screenshot.PreviewTest

            @PreviewTest
            fun Widget_Default_Snapshot() = PreviewComponent { Widget() }
            """.trimIndent(),
        )
    }

    fun `test a screenshotTest file outside every source root still produces a row`() {
        addSnapshotFile()
        val rows = SnapshotSourceScanner.scan(project)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertTrue(row.indexed.isSnapshotTest)
        assertEquals("Widget_Default_Snapshot", row.indexed.functionName)
        assertEquals(listOf("Widget"), row.indexed.targets)
    }

    fun `test a directory with no kotlin files still marks the module applicable`() {
        myFixture.addFileToProject("src/screenshotTest/README.md", "no snapshots yet")
        assertEquals(1, SnapshotSourceScanner.directories(project).size)
        assertEquals(emptyList<Any>(), SnapshotSourceScanner.scan(project))
    }

    fun `test a project with no screenshotTest directory yields nothing`() {
        myFixture.addFileToProject("src/main/kotlin/com/example/Widgets.kt", "package com.example")
        assertEquals(emptyList<Any>(), SnapshotSourceScanner.directories(project))
        assertEquals(emptyList<Any>(), SnapshotSourceScanner.scan(project))
    }

    fun `test a file with no PreviewTest function contributes nothing`() {
        myFixture.addFileToProject(
            "src/screenshotTest/kotlin/com/example/Helpers.kt",
            """
            package com.example

            fun fakeState() = Unit
            """.trimIndent(),
        )
        assertEquals(1, SnapshotSourceScanner.directories(project).size)
        assertEquals(emptyList<Any>(), SnapshotSourceScanner.scan(project))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "*SnapshotSourceScannerTest*" --tests "*SnapshotOwningModuleTest*"`
Expected: FAIL — `Unresolved reference: SnapshotSourceScanner`.

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/devomer/previewgallery/service/SnapshotSourceScanner.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.devomer.previewgallery.index.PreviewPsiScanner
import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile

/**
 * Reads the snapshot functions of the Compose Preview Screenshot Testing plugin straight from disk.
 *
 * `PreviewIndex` cannot be relied on for them: the screenshot plugin is applied only under
 * `-Pandroid.experimental.enableScreenshotTest=true`, so a project synced without that flag may leave
 * `src/screenshotTest` outside `GlobalSearchScope.projectScope` — verified against the reference project, where
 * the index produced zero snapshot rows while the files were plainly present and readable. So this walks the
 * VFS and parses the files itself, running the **same** [PreviewPsiScanner] the index runs, so a preview and its
 * snapshot can never be read by two different sets of rules.
 *
 * Callers must invoke [scan] and [directories] under a read action and off the EDT — both touch PSI and the
 * project model.
 */
object SnapshotSourceScanner {

    private const val SRC = "src"
    private const val SCREENSHOT_TEST = "screenshotTest"
    private const val KOTLIN_EXTENSION = "kt"
    private const val MAIN_SUFFIX = ".main"

    /** One module's `screenshotTest` directory, already attributed to the module that owns its previews. */
    data class Source(val moduleName: String, val directory: VirtualFile)

    /** Every `screenshotTest` directory in the project. A module with one is what "applicable" now means. */
    fun directories(project: Project): List<Source> {
        val byDirectory = LinkedHashMap<VirtualFile, MutableList<String>>()
        for (module in ModuleManager.getInstance(project).modules) {
            for (root in ModuleRootManager.getInstance(module).contentRoots) {
                val directory = probe(root) ?: continue
                byDirectory.getOrPut(directory) { mutableListOf() }.add(module.name)
            }
        }
        return byDirectory.mapNotNull { (directory, modules) ->
            pickOwningModule(modules)?.let { Source(it, directory) }
        }
    }

    /**
     * `<root>/src/screenshotTest` when the content root is the module directory, `<root>/../screenshotTest` when
     * the import split the source sets and the root is `<moduleDir>/src/main`. Those are the two layouts the
     * Gradle importer produces; anything else yields no directory, and the module is simply not applicable.
     */
    private fun probe(root: VirtualFile): VirtualFile? {
        root.findFileByRelativePath("$SRC/$SCREENSHOT_TEST")?.takeIf { it.isDirectory }?.let { return it }
        val parent = root.parent ?: return null
        if (parent.name != SRC) return null
        return parent.findChild(SCREENSHOT_TEST)?.takeIf { it.isDirectory }
    }

    /**
     * The module the rows are attributed to when several probe to the same directory.
     *
     * A module-per-source-set import gives `…ui` and `…ui.main` the same module directory, and the previews live
     * in `…ui.main`. Attributing the snapshots there is what makes them match: [SnapshotCoverageResolver] pairs
     * within one module name, so a snapshot filed under the holder module would never find its preview.
     */
    fun pickOwningModule(moduleNames: List<String>): String? =
        moduleNames.firstOrNull { it.endsWith(MAIN_SUFFIX) } ?: moduleNames.minByOrNull { it.length }

    /** Every `@PreviewTest` function under a `screenshotTest` directory, as rows the gallery can join. */
    fun scan(project: Project): List<PreviewEntry> {
        val psiManager = PsiManager.getInstance(project)
        return directories(project).flatMap { source ->
            kotlinFiles(source.directory).flatMap { file ->
                // A file the platform will not give us as a KtFile is skipped, never fatal to the batch.
                val ktFile = psiManager.findFile(file) as? KtFile ?: return@flatMap emptyList()
                PreviewPsiScanner.scan(ktFile)
                    .filter { it.isSnapshotTest }
                    .map { PreviewEntry(it, source.moduleName, file) }
            }
        }
    }

    private fun kotlinFiles(directory: VirtualFile): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        VfsUtilCore.processFilesRecursively(directory) { file ->
            if (!file.isDirectory && file.extension == KOTLIN_EXTENSION) result += file
            true
        }
        return result
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "*SnapshotSourceScannerTest*" --tests "*SnapshotOwningModuleTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/service/SnapshotSourceScanner.kt src/test/kotlin/com/devomer/previewgallery/service/SnapshotSourceScannerTest.kt src/test/kotlin/com/devomer/previewgallery/service/SnapshotOwningModuleTest.kt
git commit -m "[PG14-2] - Read the screenshotTest sources from disk"
```

---

### Task 2: Take snapshot rows from the scanner, not the index

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/service/PreviewIndexService.kt`
- Delete: `src/main/kotlin/com/devomer/previewgallery/service/ScreenshotModuleDetector.kt`
- Delete: `src/test/kotlin/com/devomer/previewgallery/service/ScreenshotModuleDetectorTest.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/service/PreviewIndexServiceTest.kt`

**Interfaces:**
- Consumes: `SnapshotSourceScanner.scan(project)`, `SnapshotSourceScanner.directories(project)` (Task 1).
- Produces: unchanged public API — `findAll(): List<PreviewEntry>`, `findOrphanSnapshots(): List<PreviewEntry>`.

- [ ] **Step 1: Write the failing tests**

Append to `PreviewIndexServiceTest.kt`:

```kotlin
fun `test a snapshot outside the index still reaches the tree`() {
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
    val service = PreviewIndexService.getInstance(project)
    val previews = service.findAll()
    assertEquals(1, previews.size)
    assertEquals(1, previews.single().snapshots.size)
    assertEquals(SnapshotCoverage.Covered(1), previews.single().coverage)
}

fun `test a snapshot is not counted twice when the index also sees it`() {
    myFixture.addFileToProject(
        "src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
        """
        package com.example

        import com.android.tools.screenshot.PreviewTest

        @PreviewTest
        fun Widget_Default_Snapshot() = PreviewComponent { Widget() }
        """.trimIndent(),
    )
    val service = PreviewIndexService.getInstance(project)
    // No preview shows Widget, so the snapshot is an orphan — and there must be exactly one of it, even
    // though the fixture's flat layout means the index sees this file too.
    assertEquals(1, service.findOrphanSnapshots().size)
}
```

The existing test `a snapshot row does not appear as a preview` must keep passing unchanged.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "*PreviewIndexServiceTest*"`
Expected: FAIL — the second test finds two orphans, or the first finds zero snapshots.

- [ ] **Step 3: Implement**

In `PreviewIndexService`, `compute()` keeps reading the index but drops the snapshot rows, because the
scanner is now their only source:

```kotlin
            index.processValues(PreviewIndex.NAME, key, null, { file, values ->
                val module = fileIndex.getModuleForFile(file)
                if (module != null) {
                    // Snapshots come from SnapshotSourceScanner, which sees them whether or not the source set
                    // reached the project model. Keeping the index's copy too would double every snapshot in a
                    // project where it did.
                    values.filterNot { it.isSnapshotTest }
                        .forEach { entries += PreviewEntry(it, module.name, file) }
                }
                true
            }, scope)
```

Extract the sort so both channels come out ordered together, and combine them in `rows()`:

```kotlin
    private fun rows(): Rows {
        if (DumbService.isDumb(project)) return Rows(emptyList(), emptyList())
        return CachedValuesManager.getManager(project).getCachedValue(
            project,
            CACHE_KEY,
            {
                CachedValueProvider.Result.create(
                    resolve(sorted(compute() + SnapshotSourceScanner.scan(project))),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    refreshTracker,
                )
            },
            false,
        )
    }

    private fun sorted(entries: List<PreviewEntry>): List<PreviewEntry> = entries.sortedWith(
        compareBy<PreviewEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.moduleName }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.indexed.packageName }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.indexed.displayName },
    )
```

`compute()` returns its list unsorted now — move its `sortedWith(...)` into `sorted` rather than sorting twice.

`resolve` loses the corroboration entirely (spec D5); applicable modules are the scanner's:

```kotlin
    /**
     * Joins coverage onto [entries]. A module is applicable when [SnapshotSourceScanner] found a `screenshotTest`
     * directory for it — the same walk that produced the snapshot rows, so the two can no longer disagree.
     */
    private fun resolve(entries: List<PreviewEntry>): Rows {
        val modules = SnapshotSourceScanner.directories(project).mapTo(HashSet()) { it.moduleName }
        val resolved = SnapshotCoverageResolver.resolve(entries, modules) { row, coverage, snapshots ->
            row.copy(coverage = coverage, snapshots = snapshots)
        }
        return Rows(resolved.previews, resolved.orphans)
    }
```

Delete `ScreenshotModuleDetector.kt` and `ScreenshotModuleDetectorTest.kt`. Check first that nothing
else references either — search for `ScreenshotModuleDetector` across `src/`.

- [ ] **Step 4: Run the full suite**

Run: `./gradlew clean test --no-build-cache --rerun-tasks`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/main/kotlin/com/devomer/previewgallery/service src/test/kotlin/com/devomer/previewgallery/service
git commit -m "[PG14-3] - Take snapshot rows from the source scanner"
```

---

### Task 3: Correct the Phase 13 documents

**Files:**
- Modify: `docs/superpowers/specs/2026-07-30-snapshot-coverage-badge-design.md`
- Modify: `docs/superpowers/plans/2026-07-30-snapshot-coverage-badge.md`
- Modify: `docs/snapshot-testing-roadmap.md`

No code, no tests. Phase 13's documents assert things the gate disproved, and a later reader will
otherwise inherit them.

- [ ] **Step 1: Correct the spec**

In `2026-07-30-snapshot-coverage-badge-design.md`:
- The Risks table's first row predicted the index would see `screenshotTest` files and said the
  expectation was that it would. Record that the gate **disproved** it, and point at Phase 14's spec as
  the resolution.
- D3 and D6 describe matching and reference lookup, both still correct — leave them.
- The Current state section's claim that snapshot functions "are already indexed and appear in the tree
  as ordinary previews" is now known to hold only when the source set is in the project model. Qualify it.

- [ ] **Step 2: Correct the plan's manual gate**

In `2026-07-30-snapshot-coverage-badge.md`, the "Manual verification gate" section's diagnostic — "If no
badges appear anywhere, the source set is not reaching the index" — is obsolete: that is exactly what
happened, and Phase 14 removed the dependency. Replace it with a pointer to Phase 14's plan and its own
gate, and say plainly that the Phase 13 gate was run and failed.

- [ ] **Step 3: Correct the roadmap**

In `docs/snapshot-testing-roadmap.md`, F1's "Open" line asks "does the IDE model expose
`src/screenshotTest` when the Gradle gate flag is off?". Answer it: no, and name Phase 14 as where that
is handled. The spike note that gates F5/F6 stands and should now cite this as evidence.

- [ ] **Step 4: Commit**

```bash
git add docs
git commit -m "[PG14-4] - Correct the phase 13 documents"
```

---

## Manual verification gate (human)

Run the sandbox against `hepsi-android` and check:

```bash
./gradlew runIde
```

- [ ] `features/favorites/ui` preview rows carry coverage badges.
- [ ] `ErrorRetryRowPreview` reads `· 1 snapshot` and has `ErrorRetryRow_Default_Snapshot` as a child row.
- [ ] Selecting that child shows two images side by side, labelled `phone` and `small`.
- [ ] A "Snapshots without a preview" branch holds `NoResultRenderer_Snapshot`.
- [ ] A module with no `src/screenshotTest` shows no badges at all.

If badges are still absent everywhere, **do not** reach for "the probe missed the layout" first. The
reference project's cached module model was inspected after this plan was written and says the probe
will hit: the holder module `hepsi-android.features.favorites.ui` has content root
`features/favorites/ui` (shape 1) and `…ui.main` has `features/favorites/ui/src/main` (shape 2), both
landing on the same `src/screenshotTest` directory, which `directories()` dedups and attributes to
`…ui.main`. Check, in order:

1. **The module-filter toggle.** `PreviewGalleryPanel.applyFilter` runs previews *and* the orphan branch
   through `PreviewModuleFilter`; with the filter on and an editor open in another module, correct rows
   are simply not on screen.
2. **Which module node the rows landed under.** Attribution is the whole point of D4 — rows under
   `…favorites.ui` rather than `…favorites.ui.main` mean `pickOwningModule` did not see the `.main`
   module for that directory.
3. Only then, which content roots the favorites modules actually report.
