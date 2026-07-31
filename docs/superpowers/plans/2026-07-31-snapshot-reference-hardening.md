# Snapshot Reference Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the three defects on the reference-image strip that the Phase 13/14 gate never walked — a stale VFS listing, a hardcoded build variant, and index-fallback rows that can resolve no module directory.

**Architecture:** A new `ReferenceRoots` discovers every `<moduleDir>/src/screenshotTest*/reference` directory on disk, refreshes it before it is read, and names the Gradle task that regenerates it. A new `ModuleDirectoryResolver` keeps Phase 14's path derivation as the primary answer and adds `ProjectFileIndex.getModuleForFile` behind it. `ReferenceImageLocator` loses its hardcoded root and merges the results of every discovered root, stamping each image with the source set it came from. `PreviewGalleryPanel` splits its single read action into resolve → refresh (no lock) → locate.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install at `platformLocalPath`) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase`

**Spec:** [2026-07-31-snapshot-reference-hardening-design.md](../specs/2026-07-31-snapshot-reference-hardening-design.md)

## Global Constraints

- **Never use the Kotlin `!!` operator.** Use `?:`, `?.let`, `requireNotNull` or an explicit null check.
- **All source, comments, docs and test names in English.**
- **Do not add explanatory comments to code.** KDoc on new public declarations is expected and is what the existing files carry; line comments inside function bodies are only for a decision a reader would otherwise undo, which is how the current codebase uses them.
- Commit message pattern: `[PG15-N] - Task name` (this feature's task ids are `PG15-2` … `PG15-7`; `PG15-0` is the design spec and `PG15-1` is this plan).
- Commit trailer on every commit: `Co-Authored-By: Claude <Opus 5> <noreply@anthropic.com>`.
- `SnapshotSourceScanner`, `PreviewIndexService`, `SnapshotCoverageResolver`, `PreviewPsiScanner` and `ReferenceStripView` are **not** modified.
- `ReferenceRoots.refresh` **must not** be called while holding a read lock — the platform rejects a synchronous refresh under one ("Do not perform a synchronous refresh under read lock").
- No `com.android.tools.*` import outside `render/`.
- Tests needing a project or PSI use `BasePlatformTestCase` with backticked names starting `test `. Pure-logic tests use plain JUnit 4 (`@Test` + `org.junit.Assert`).
- **Build/test command:** `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`. The plain `./gradlew test` serves stale cached test bytecode after a signature change and `clean` alone does not fix it. Do **not** run `./gradlew runIde` — the human runs that gate. Never run any Gradle task while a `runIde` sandbox is live.

---

## File Structure

**Create**

| File | Responsibility |
|---|---|
| `src/main/kotlin/com/devomer/previewgallery/service/ReferenceRoots.kt` | Discover `src/screenshotTest*/reference` directories, refresh them from disk, name the Gradle task per variant |
| `src/main/kotlin/com/devomer/previewgallery/service/ModuleDirectoryResolver.kt` | Resolve a snapshot file to its module directory: path derivation first, project model second |
| `src/test/kotlin/com/devomer/previewgallery/service/ReferenceRootsTest.kt` | Discovery against a real on-disk directory tree |
| `src/test/kotlin/com/devomer/previewgallery/service/ReferenceRootsTaskNameTest.kt` | Task-name derivation, pure |
| `src/test/kotlin/com/devomer/previewgallery/service/ReferenceRootsRefreshTest.kt` | The proof of defect 1: files written behind the VFS's back |
| `src/test/kotlin/com/devomer/previewgallery/service/ModuleDirectoryResolverTest.kt` | Both resolution paths and the null case |

**Modify**

| File | Change |
|---|---|
| `model/ReferenceImage.kt` | Gains `sourceSet` |
| `service/ReferenceImageLocator.kt` | `REFERENCE_ROOT` deleted; `relativeDirectory` → `packageDirectory`; `locate` takes the root list, merges and sorts; new `labels` |
| `ui/PreviewRenderPanel.kt` | Holds the task names for the no-reference message; `showReference` gains a `tasks` parameter; new `messageForTest` seam |
| `ui/PreviewGalleryPanel.kt` | Three-step flow (resolve → refresh → locate); label composition; passes the task names through |
| `src/main/resources/messages/PreviewGalleryBundle.properties` | `render.noReference` rewritten, `render.noReference.task` added |
| `src/test/kotlin/com/devomer/previewgallery/service/ReferenceImageLocatorTest.kt` | Two `relativeDirectory` cases become `packageDirectory` cases |
| `src/test/kotlin/com/devomer/previewgallery/service/ReferenceImageLocatorLocateTest.kt` | Goes through roots; merge, sort and token cases added |
| `src/test/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanelTest.kt` | Message cases added |
| `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt` | Flavoured-module cases added |

---

### Task 1 (`PG15-2`): Discover the reference roots

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/service/ReferenceRoots.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/service/ReferenceRootsTest.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/service/ReferenceRootsTaskNameTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `ReferenceRoots.Root(sourceSetName: String, variant: String?, directory: VirtualFile)` with a derived `val token: String`; `ReferenceRoots.of(moduleDirectory: VirtualFile): List<Root>`; `ReferenceRoots.updateTask(variant: String?): String?`. `Root.directory` is the **`reference` directory itself**, not the source set that holds it.

- [ ] **Step 1: Write the failing discovery tests**

Create `src/test/kotlin/com/devomer/previewgallery/service/ReferenceRootsTest.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Root discovery against a **real** directory tree rather than the fixture's in-memory one.
 *
 * The temp directory is deliberately outside the project: `ReferenceRoots` takes a plain `VirtualFile` and
 * asks the project model nothing, so a fixture module would add nothing but setup. The real filesystem is
 * what [ReferenceRootsRefreshTest] needs, and using it here too keeps both files on one fixture shape.
 */
class ReferenceRootsTest : BasePlatformTestCase() {

    private lateinit var moduleDirectory: File

    override fun setUp() {
        super.setUp()
        moduleDirectory = FileUtil.createTempDirectory("preview-gallery-roots", null)
    }

    override fun tearDown() {
        try {
            FileUtil.delete(moduleDirectory)
        } finally {
            super.tearDown()
        }
    }

    private fun directoryOnDisk(relativePath: String) {
        FileUtil.createDirectory(File(moduleDirectory, relativePath))
    }

    private fun moduleVirtualFile(): VirtualFile = requireNotNull(
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(moduleDirectory),
    ) { "The temp module directory must be visible in the VFS" }

    fun `test a single variant directory yields one root`() {
        directoryOnDisk("src/screenshotTestDebug/reference")

        val roots = ReferenceRoots.of(moduleVirtualFile())

        assertEquals(listOf("screenshotTestDebug"), roots.map { it.sourceSetName })
        assertEquals(listOf("Debug"), roots.map { it.variant })
        assertEquals("reference", roots.single().directory.name)
    }

    fun `test two flavour directories yield two roots sorted by source set`() {
        directoryOnDisk("src/screenshotTestHuaweiDebug/reference")
        directoryOnDisk("src/screenshotTestGoogleDebug/reference")

        val roots = ReferenceRoots.of(moduleVirtualFile())

        assertEquals(
            listOf("screenshotTestGoogleDebug", "screenshotTestHuaweiDebug"),
            roots.map { it.sourceSetName },
        )
        assertEquals(listOf("googleDebug", "huaweiDebug"), roots.map { it.token })
    }

    fun `test the source directory is not a root`() {
        directoryOnDisk("src/screenshotTest/kotlin/com/example")

        assertEquals(emptyList<String>(), ReferenceRoots.of(moduleVirtualFile()).map { it.sourceSetName })
    }

    fun `test a root with no variant suffix has no variant and falls back to its own name`() {
        directoryOnDisk("src/screenshotTest/reference")

        val root = ReferenceRoots.of(moduleVirtualFile()).single()

        assertNull(root.variant)
        assertEquals("screenshotTest", root.token)
    }

    fun `test a module with no src directory yields nothing`() {
        assertEquals(emptyList<String>(), ReferenceRoots.of(moduleVirtualFile()).map { it.sourceSetName })
    }

    fun `test a source set that is not a screenshot test one is ignored`() {
        directoryOnDisk("src/main/reference")

        assertEquals(emptyList<String>(), ReferenceRoots.of(moduleVirtualFile()).map { it.sourceSetName })
    }
}
```

Create `src/test/kotlin/com/devomer/previewgallery/service/ReferenceRootsTaskNameTest.kt`:

```kotlin
package com.devomer.previewgallery.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferenceRootsTaskNameTest {

    @Test
    fun `a flavoured variant names its own update task`() {
        assertEquals("updateGoogleDebugScreenshotTest", ReferenceRoots.updateTask("GoogleDebug"))
    }

    @Test
    fun `the plain debug variant names the task the skill documents`() {
        assertEquals("updateDebugScreenshotTest", ReferenceRoots.updateTask("Debug"))
    }

    @Test
    fun `an unknown variant names no task at all`() {
        assertNull(ReferenceRoots.updateTask(null))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel --tests "com.devomer.previewgallery.service.ReferenceRoots*"`
Expected: compilation failure — `Unresolved reference: ReferenceRoots`.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/devomer/previewgallery/service/ReferenceRoots.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.intellij.openapi.vfs.VirtualFile

/**
 * The directories the Compose Preview Screenshot Testing plugin commits its reference PNGs to, for one module.
 *
 * They are **discovered**, never hardcoded: the plugin writes to `src/screenshotTest<Variant>/reference`, and a
 * flavoured module therefore has one such directory per variant (`screenshotTestGoogleDebug`,
 * `screenshotTestHuaweiDebug`) rather than the single `screenshotTestDebug` a library module has. Reading the
 * variant off the directory name is what lets the no-reference message name a Gradle task the module actually
 * has, and needs no build model — the same posture [SnapshotSourceScanner] takes for the source set.
 *
 * Requiring a `reference` child is what keeps `src/screenshotTest` — the *source* directory, which matches the
 * same prefix — from being mistaken for a root.
 */
object ReferenceRoots {

    private const val SRC = "src"
    private const val SCREENSHOT_TEST = "screenshotTest"
    private const val REFERENCE = "reference"

    /**
     * One committed-reference directory. [directory] is the `reference` directory itself, so a caller resolves a
     * package path against it directly; [variant] is null when the source-set name carries no suffix to read one
     * from, which is the only case that yields no Gradle task name.
     */
    data class Root(val sourceSetName: String, val variant: String?, val directory: VirtualFile) {

        /** The label token for this root, used only when more than one root contributes to a strip. */
        val token: String
            get() = variant?.replaceFirstChar { it.lowercaseChar() } ?: sourceSetName
    }

    /**
     * Every reference directory under [moduleDirectory], sorted by source-set name so a strip's left-to-right
     * order is stable across selections.
     *
     * Reads the VFS as it stands; call [refresh] first if the answer must include what another process just
     * wrote.
     */
    fun of(moduleDirectory: VirtualFile): List<Root> {
        val src = moduleDirectory.findChild(SRC)?.takeIf { it.isDirectory } ?: return emptyList()
        return src.children.orEmpty()
            .filter { it.isDirectory && it.name.startsWith(SCREENSHOT_TEST) }
            .mapNotNull { sourceSet ->
                val reference = sourceSet.findChild(REFERENCE)?.takeIf { it.isDirectory } ?: return@mapNotNull null
                Root(
                    sourceSetName = sourceSet.name,
                    variant = sourceSet.name.removePrefix(SCREENSHOT_TEST).ifEmpty { null },
                    directory = reference,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.sourceSetName })
    }

    /** The Gradle task that regenerates [variant]'s references, or null when the variant could not be read. */
    fun updateTask(variant: String?): String? = variant?.let { "update${it}ScreenshotTest" }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel --tests "com.devomer.previewgallery.service.ReferenceRoots*"`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/service/ReferenceRoots.kt src/test/kotlin/com/devomer/previewgallery/service/ReferenceRootsTest.kt src/test/kotlin/com/devomer/previewgallery/service/ReferenceRootsTaskNameTest.kt
git commit -m "$(cat <<'EOF'
[PG15-2] - Discover the reference roots

Read src/screenshotTest*/reference off disk instead of assuming the single
screenshotTestDebug a library module has, and name the update task from the
variant the directory carries.

Co-Authored-By: Claude <Opus 5> <noreply@anthropic.com>
EOF
)"
```

---

### Task 2 (`PG15-3`): Refresh the roots from disk before reading them

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/service/ReferenceRoots.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/service/ReferenceRootsRefreshTest.kt`

**Interfaces:**
- Consumes: `ReferenceRoots.of` and `ReferenceRoots.Root` from Task 1.
- Produces: `ReferenceRoots.refresh(moduleDirectory: VirtualFile)`, returning `Unit`. **Must not** be called under a read lock.

- [ ] **Step 1: Write the failing refresh tests**

Create `src/test/kotlin/com/devomer/previewgallery/service/ReferenceRootsRefreshTest.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * The proof of the defect this phase exists for: `updateDebugScreenshotTest` run from a terminal writes PNGs the
 * VFS has not heard about, and the panel then tells the user to run the command they just ran.
 *
 * Every file here is written with `java.io.File`, deliberately bypassing the VFS, and the tree is fully loaded
 * first — a directory whose children were never cached would answer from disk on demand and hide the bug.
 */
class ReferenceRootsRefreshTest : BasePlatformTestCase() {

    private val facadeDirectory = "reference/com/example/WidgetSnapshotsKt"

    private lateinit var moduleDirectory: File

    override fun setUp() {
        super.setUp()
        moduleDirectory = FileUtil.createTempDirectory("preview-gallery-refresh", null)
    }

    override fun tearDown() {
        try {
            FileUtil.delete(moduleDirectory)
        } finally {
            super.tearDown()
        }
    }

    private fun fileOnDisk(relativePath: String) {
        val file = File(moduleDirectory, relativePath)
        FileUtil.createParentDirs(file)
        file.writeText("")
    }

    private fun moduleVirtualFile(): VirtualFile = requireNotNull(
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(moduleDirectory),
    ) { "The temp module directory must be visible in the VFS" }

    private fun loadEveryChild(root: VirtualFile) {
        VfsUtilCore.visitChildrenRecursively(
            root,
            object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean = true
            },
        )
    }

    private fun pngCount(module: VirtualFile): Int = ReferenceRoots.of(module).sumOf { root ->
        root.directory.findFileByRelativePath("com/example/WidgetSnapshotsKt")?.children.orEmpty().size
    }

    fun `test a PNG written outside the IDE is invisible until the refresh`() {
        fileOnDisk("src/screenshotTestDebug/$facadeDirectory/Widget_Default_Snapshot_phone_eee23ffd_0.png")
        val module = moduleVirtualFile()
        loadEveryChild(module)

        fileOnDisk("src/screenshotTestDebug/$facadeDirectory/Widget_Default_Snapshot_small_72f29e0e_0.png")
        assertEquals(1, pngCount(module))

        ReferenceRoots.refresh(module)

        assertEquals(2, pngCount(module))
    }

    fun `test a variant directory created outside the IDE is invisible until the refresh`() {
        fileOnDisk("src/screenshotTestDebug/$facadeDirectory/Widget_Default_Snapshot_phone_eee23ffd_0.png")
        val module = moduleVirtualFile()
        loadEveryChild(module)

        fileOnDisk("src/screenshotTestGoogleDebug/$facadeDirectory/Widget_Default_Snapshot_phone_eee23ffd_0.png")
        assertEquals(1, ReferenceRoots.of(module).size)

        ReferenceRoots.refresh(module)

        assertEquals(2, ReferenceRoots.of(module).size)
    }

    fun `test a module with no src directory survives the refresh`() {
        val module = moduleVirtualFile()

        ReferenceRoots.refresh(module)

        assertEquals(emptyList<String>(), ReferenceRoots.of(module).map { it.sourceSetName })
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel --tests "com.devomer.previewgallery.service.ReferenceRootsRefreshTest"`
Expected: compilation failure — `Unresolved reference: refresh`.

- [ ] **Step 3: Write the implementation**

Add to `ReferenceRoots`, immediately above `of`, and add `import com.intellij.openapi.vfs.VfsUtil` to the file's imports:

```kotlin
    /**
     * Brings [moduleDirectory]'s reference directories up to date with what is actually on disk.
     *
     * **Must not run under a read lock** — the platform rejects a synchronous refresh there ("Do not perform a
     * synchronous refresh under read lock") — so this is its own step between the caller's two read actions,
     * never inside one.
     *
     * Two passes, and one cannot do the job of the other. The shallow pass reloads `src`'s own children, which is
     * what makes a `screenshotTestGoogleDebug` directory created since the last sync appear at all; the recursive
     * pass reloads each source set's subtree, which is what makes a PNG added to an already-listed `reference`
     * directory appear. A single recursive pass over `src` would walk every source file in the module instead of
     * the snapshot corpus alone.
     */
    fun refresh(moduleDirectory: VirtualFile) {
        val src = moduleDirectory.findChild(SRC)?.takeIf { it.isDirectory } ?: return
        VfsUtil.markDirtyAndRefresh(false, false, true, src)
        val sourceSets = src.children.orEmpty()
            .filter { it.isDirectory && it.name.startsWith(SCREENSHOT_TEST) }
        if (sourceSets.isEmpty()) return
        VfsUtil.markDirtyAndRefresh(false, true, true, *sourceSets.toTypedArray())
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel --tests "com.devomer.previewgallery.service.ReferenceRoots*"`
Expected: PASS, 12 tests.

If `test a PNG written outside the IDE is invisible until the refresh` fails on its **first** assertion (it sees 2 before the refresh), the platform's file watcher picked the write up on its own. Do not weaken the assertion: confirm by re-running the single test, and if it is genuinely flaky, note it in the commit body and keep the post-refresh assertion — that one is the contract.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/service/ReferenceRoots.kt src/test/kotlin/com/devomer/previewgallery/service/ReferenceRootsRefreshTest.kt
git commit -m "$(cat <<'EOF'
[PG15-3] - Refresh the reference roots from disk

A PNG written by a Gradle task in a terminal is not in the VFS yet, so the
panel told the user to run the command they had just run. Two passes: shallow
over src for a new variant directory, recursive over each source set for a new
file in an already-listed one.

Co-Authored-By: Claude <Opus 5> <noreply@anthropic.com>
EOF
)"
```

---

### Task 3 (`PG15-4`): Locate across every root

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/model/ReferenceImage.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/service/ReferenceImageLocator.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt:743-745`
- Test: `src/test/kotlin/com/devomer/previewgallery/service/ReferenceImageLocatorTest.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/service/ReferenceImageLocatorLocateTest.kt`

**Interfaces:**
- Consumes: `ReferenceRoots.Root` and `ReferenceRoots.of` from Task 1.
- Produces: `ReferenceImage(sourceSet: String, variant: String, file: VirtualFile)`; `ReferenceImageLocator.packageDirectory(packageName: String, jvmClassName: String): String` returning `<package path>/<facade>` with no root prefix; `ReferenceImageLocator.locate(entry: PreviewEntry, roots: List<ReferenceRoots.Root>): List<ReferenceImage>`; `ReferenceImageLocator.labels(images: List<ReferenceImage>): List<String>`, same order as its input. `variantOf` is unchanged.

- [ ] **Step 1: Update the pure locator tests**

In `src/test/kotlin/com/devomer/previewgallery/service/ReferenceImageLocatorTest.kt`, replace the first two tests with:

```kotlin
    @Test
    fun `directory mirrors the package and the facade class`() {
        assertEquals(
            "com/hepsiburada/ui/feature/favorites/component/ComponentsSnapshotsKt",
            ReferenceImageLocator.packageDirectory(
                packageName = "com.hepsiburada.ui.feature.favorites.component",
                jvmClassName = "com.hepsiburada.ui.feature.favorites.component.ComponentsSnapshotsKt",
            ),
        )
    }

    @Test
    fun `a root package yields no package directories`() {
        assertEquals(
            "SnapshotsKt",
            ReferenceImageLocator.packageDirectory(packageName = "", jvmClassName = "SnapshotsKt"),
        )
    }
```

The four `variantOf` tests below them are untouched.

- [ ] **Step 2: Rewrite the locate tests to go through roots**

Replace `src/test/kotlin/com/devomer/previewgallery/service/ReferenceImageLocatorLocateTest.kt` in full:

```kotlin
package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.search.testRow
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [ReferenceImageLocator.locate] against a real directory tree — the half of the locator the pure
 * [ReferenceImageLocatorTest] cannot reach: the directory walk, the rejection of files that are not this
 * function's, the merge across roots and the sort.
 *
 * The roots are resolved the way the panel resolves them, from the snapshot's own module directory, so this also
 * covers the wiring between [ReferenceRoots] and the locator.
 *
 * The file contents are irrelevant here; nothing decodes them (the panel does, off this path), so an empty file
 * with the right name is a faithful fixture for what this function actually reads: names.
 */
class ReferenceImageLocatorLocateTest : BasePlatformTestCase() {

    private val facade = "com/example/WidgetSnapshotsKt"

    private fun snapshot(functionName: String = "Widget_Default_Snapshot"): PreviewEntry {
        val file = myFixture.addFileToProject(
            "src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
            "package com.example\n",
        ).virtualFile
        val indexed = testRow(
            displayName = functionName,
            functionName = functionName,
            packageName = "com.example",
            isSnapshotTest = true,
        ).indexed.copy(jvmClassName = "com.example.WidgetSnapshotsKt")
        return PreviewEntry(indexed, "app.main", file)
    }

    private fun reference(sourceSet: String, name: String) {
        myFixture.tempDirFixture.createFile("src/$sourceSet/reference/$facade/$name", "")
    }

    /** Exactly what `PreviewGalleryPanel.locateReferences` passes: no `Module`, no `ProjectFileIndex`. */
    private fun roots(entry: PreviewEntry): List<ReferenceRoots.Root> {
        val moduleDirectory = requireNotNull(SnapshotSourceScanner.moduleDirectory(entry.file)) {
            "A snapshot under src/screenshotTest must resolve to the module directory holding its references"
        }
        return ReferenceRoots.of(moduleDirectory)
    }

    fun `test every variant of the function is found and sorted by variant`() {
        reference("screenshotTestDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        reference("screenshotTestDebug", "Widget_Default_Snapshot_small_72f29e0e_0.png")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, roots(entry))

        assertEquals(listOf("phone", "small"), located.map { it.variant })
        assertEquals(
            listOf("Widget_Default_Snapshot_phone_eee23ffd_0.png", "Widget_Default_Snapshot_small_72f29e0e_0.png"),
            located.map { it.file.name },
        )
    }

    fun `test variants sort case-insensitively`() {
        reference("screenshotTestDebug", "Widget_Default_Snapshot_Zebra_eee23ffd_0.png")
        reference("screenshotTestDebug", "Widget_Default_Snapshot_apple_72f29e0e_0.png")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, roots(entry))

        assertEquals(listOf("apple", "Zebra"), located.map { it.variant })
    }

    fun `test files belonging to another function or shape are rejected`() {
        reference("screenshotTestDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        reference("screenshotTestDebug", "OtherWidget_Default_Snapshot_phone_eee23ffd_0.png")
        reference("screenshotTestDebug", "Widget_Default_Snapshot_phone.png")
        reference("screenshotTestDebug", "README.txt")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, roots(entry))

        assertEquals(listOf("phone"), located.map { it.variant })
    }

    fun `test a missing reference directory yields nothing rather than failing`() {
        val entry = snapshot()

        assertEquals(emptyList<String>(), ReferenceImageLocator.locate(entry, roots(entry)).map { it.variant })
    }

    fun `test a reference directory holding no matching file yields nothing`() {
        reference("screenshotTestDebug", "SomethingElse_phone_eee23ffd_0.png")
        val entry = snapshot()

        assertEquals(emptyList<String>(), ReferenceImageLocator.locate(entry, roots(entry)).map { it.variant })
    }

    fun `test two flavours are merged and grouped by source set`() {
        reference("screenshotTestHuaweiDebug", "Widget_Default_Snapshot_small_72f29e0e_0.png")
        reference("screenshotTestHuaweiDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        reference("screenshotTestGoogleDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, roots(entry))

        assertEquals(listOf("googleDebug", "huaweiDebug", "huaweiDebug"), located.map { it.sourceSet })
        assertEquals(listOf("phone", "phone", "small"), located.map { it.variant })
    }

    fun `test a golden committed for one flavour only is still found`() {
        reference("screenshotTestHuaweiDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, roots(entry))

        assertEquals(listOf("huaweiDebug"), located.map { it.sourceSet })
    }

    fun `test a single root labels its images with the variant alone`() {
        reference("screenshotTestDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        reference("screenshotTestDebug", "Widget_Default_Snapshot_small_72f29e0e_0.png")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, roots(entry))

        assertEquals(listOf("phone", "small"), ReferenceImageLocator.labels(located))
    }

    fun `test two roots label their images with the source set`() {
        reference("screenshotTestGoogleDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        reference("screenshotTestHuaweiDebug", "Widget_Default_Snapshot_phone_eee23ffd_0.png")
        val entry = snapshot()

        val located = ReferenceImageLocator.locate(entry, roots(entry))

        // Two identical-looking images with no way to tell which flavour each belongs to would be worse than
        // one, which is why the prefix appears exactly here and not on the single-root strip above.
        assertEquals(
            listOf("googleDebug · phone", "huaweiDebug · phone"),
            ReferenceImageLocator.labels(located),
        )
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel --tests "com.devomer.previewgallery.service.ReferenceImageLocator*"`
Expected: compilation failure — `Unresolved reference: packageDirectory` and a `locate` overload mismatch.

- [ ] **Step 4: Widen the model**

Replace `src/main/kotlin/com/devomer/previewgallery/model/ReferenceImage.kt` in full:

```kotlin
package com.devomer.previewgallery.model

import com.intellij.openapi.vfs.VirtualFile

/**
 * One committed reference PNG of a snapshot: the image the build actually compares against.
 *
 * [sourceSet] is the label token of the root it came from. It only reaches the screen when a strip spans more
 * than one root — a flavoured module commits a full set per variant, and two identical-looking images with no
 * way to tell which flavour each belongs to would be worse than one.
 */
data class ReferenceImage(val sourceSet: String, val variant: String, val file: VirtualFile)
```

- [ ] **Step 5: Rewrite the locator**

In `src/main/kotlin/com/devomer/previewgallery/service/ReferenceImageLocator.kt`: delete the `REFERENCE_ROOT` constant, rename `relativeDirectory` to `packageDirectory` and drop the prefix from its result, and replace `locate` with the two functions below. `variantOf` and its KDoc are untouched.

```kotlin
    /** The directory a function's reference images live in, relative to a [ReferenceRoots.Root]. */
    fun packageDirectory(packageName: String, jvmClassName: String): String {
        val facade = jvmClassName.substringAfterLast('.')
        val packagePath = packageName.replace('.', '/')
        return if (packagePath.isEmpty()) facade else "$packagePath/$facade"
    }

    /**
     * The committed reference images for [entry] across every root in [roots], sorted by source set and then by
     * variant so the strip's left-to-right order is stable across selections and one flavour's images stay
     * contiguous.
     *
     * Every root contributes. Picking one would hide a golden committed for a single flavour, which is the
     * failure this signature replaced: a module whose references live under `screenshotTestHuaweiDebug` reported
     * none at all while the root was the constant `screenshotTestDebug`.
     */
    fun locate(entry: PreviewEntry, roots: List<ReferenceRoots.Root>): List<ReferenceImage> =
        roots.flatMap { locate(entry, it) }
            .sortedWith(
                compareBy<ReferenceImage, String>(String.CASE_INSENSITIVE_ORDER) { it.sourceSet }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.variant },
            )

    private fun locate(entry: PreviewEntry, root: ReferenceRoots.Root): List<ReferenceImage> {
        val relative = packageDirectory(entry.indexed.packageName, entry.indexed.jvmClassName)
        val directory = root.directory.findFileByRelativePath(relative) ?: return emptyList()
        return directory.children.orEmpty().mapNotNull { file ->
            val variant = variantOf(file.name, entry.indexed.functionName) ?: return@mapNotNull null
            ReferenceImage(root.token, variant, file)
        }
    }
```

Also update the object's class KDoc: replace the sentence beginning *"The layout is fully derivable…"* with:

```
 * The layout is fully derivable from facts the index already holds, so nothing here is stored: under a root the
 * directory mirrors the package and then the JVM facade class, and the file name is
 * `<function>_<variant>_<configuration hash>_<index>.png`. The hash is a property of the `@Preview`
 * configuration, not of the function — `phone` hashes to the same value across every snapshot in the corpus —
 * so it is never computed, only skipped over. Which roots exist is [ReferenceRoots]' question, not this one's.
```

Delete `import com.intellij.openapi.vfs.VirtualFile`: after this change nothing in the file names the type — `variantOf` never did, and the private `locate` reaches it through `root.directory` instead of taking it as a parameter.

Finally, add the label rule as its own function, below `locate`:

```kotlin
    /**
     * The strip labels for [images], in the same order.
     *
     * The source set is prefixed only when the strip spans more than one: with a single root the variant alone
     * is unambiguous, and prefixing it would add noise to every module that has no flavours. The rule lives here
     * rather than in the panel because only a merged result knows whether it spans one root or two.
     */
    fun labels(images: List<ReferenceImage>): List<String> {
        val qualify = images.map { it.sourceSet }.distinct().size > 1
        return images.map { if (qualify) "${it.sourceSet} · ${it.variant}" else it.variant }
    }
```

- [ ] **Step 6: Keep `PreviewGalleryPanel` compiling**

In `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`, replace the body of `locateReferences` (line 744-745) with:

```kotlin
        val moduleDirectory = SnapshotSourceScanner.moduleDirectory(snapshot.file) ?: return emptyList()
        return ReferenceImageLocator.locate(snapshot, ReferenceRoots.of(moduleDirectory))
```

Add `import com.devomer.previewgallery.service.ReferenceRoots`. Task 6 replaces this function entirely; this step only keeps the module compiling and its behaviour is deliberately unchanged apart from the root discovery.

In `decodeReferences`, the `LabelledImage` construction still reads `reference.variant` and needs no change yet.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`
Expected: PASS, the whole suite.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/model/ReferenceImage.kt src/main/kotlin/com/devomer/previewgallery/service/ReferenceImageLocator.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt src/test/kotlin/com/devomer/previewgallery/service/ReferenceImageLocatorTest.kt src/test/kotlin/com/devomer/previewgallery/service/ReferenceImageLocatorLocateTest.kt
git commit -m "$(cat <<'EOF'
[PG15-4] - Locate reference images across every root

The reference root was the constant src/screenshotTestDebug, so a flavoured
module reported no images for every snapshot it has. Every discovered root now
contributes, and each image carries the source set it came from.

Co-Authored-By: Claude <Opus 5> <noreply@anthropic.com>
EOF
)"
```

---

### Task 4 (`PG15-5`): Resolve the module directory for index-fallback rows

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/service/ModuleDirectoryResolver.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/service/ModuleDirectoryResolverTest.kt`

**Interfaces:**
- Consumes: `SnapshotSourceScanner.moduleDirectory(file: VirtualFile): VirtualFile?`.
- Produces: `ModuleDirectoryResolver.resolve(project: Project, file: VirtualFile): VirtualFile?`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/devomer/previewgallery/service/ModuleDirectoryResolverTest.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * The two ways a snapshot file reaches its module directory, and the one case that has neither.
 *
 * Phase 14 deliberately removed `ProjectFileIndex.getModuleForFile` from this path; it comes back here as a
 * **fallback only**, so the first test pins that the path derivation still owns every file it can answer for.
 */
class ModuleDirectoryResolverTest : BasePlatformTestCase() {

    fun `test a file under the snapshot source set resolves by path`() {
        val file = myFixture.addFileToProject(
            "src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
            "package com.example\n",
        ).virtualFile

        assertEquals(
            SnapshotSourceScanner.moduleDirectory(file),
            ModuleDirectoryResolver.resolve(project, file),
        )
    }

    fun `test a file the path rule cannot read falls back to the module content root`() {
        myFixture.addFileToProject("src/main/kotlin/com/example/Widget.kt", "package com.example\n")
        val file = myFixture.addFileToProject(
            "snapshots/com/example/WidgetSnapshots.kt",
            "package com.example\n",
        ).virtualFile

        assertNull(SnapshotSourceScanner.moduleDirectory(file))
        val resolved = ModuleDirectoryResolver.resolve(project, file)
        assertNotNull(resolved)
        assertNotNull(resolved?.findChild("src"))
    }

    fun `test a file in no module at all resolves to nothing`() {
        val outside = FileUtil.createTempDirectory("preview-gallery-outside", null)
        try {
            val onDisk = File(outside, "WidgetSnapshots.kt")
            onDisk.writeText("package com.example\n")
            val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(onDisk))

            assertNull(ModuleDirectoryResolver.resolve(project, file))
        } finally {
            FileUtil.delete(outside)
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel --tests "com.devomer.previewgallery.service.ModuleDirectoryResolverTest"`
Expected: compilation failure — `Unresolved reference: ModuleDirectoryResolver`.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/devomer/previewgallery/service/ModuleDirectoryResolver.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * The module directory a snapshot's reference images hang under.
 *
 * Two answers, in this order and never the other way round. [SnapshotSourceScanner.moduleDirectory] derives it
 * from the file's own path and is what Phase 14 put on this path precisely to stop the strip depending on the
 * project model: when the model is the failing half, the rows still appear and every one of them reads
 * `NO_REFERENCE`, with nothing on screen saying why.
 *
 * The model is consulted only where the path rule has no answer — a snapshot outside
 * `<moduleDir>/src/screenshotTest`, which is the layout Phase 14's index fallback exists for and which showed no
 * images at all. Strictly additive: where the path rule answers, nothing here runs; where the model has no
 * answer either, the result is the empty strip it already was.
 *
 * Callers must be under a read action — the fallback reads the project model.
 */
object ModuleDirectoryResolver {

    private const val SRC = "src"

    fun resolve(project: Project, file: VirtualFile): VirtualFile? =
        SnapshotSourceScanner.moduleDirectory(file) ?: fromModel(project, file)

    /**
     * The first content root holding a `src` directory, rather than the first content root outright: a
     * module-per-source-set import gives the holder module several roots, and only the module directory itself
     * has the `src` the reference layout is expressed against.
     */
    private fun fromModel(project: Project, file: VirtualFile): VirtualFile? {
        val module = ModuleUtilCore.findModuleForFile(file, project) ?: return null
        return ModuleRootManager.getInstance(module).contentRoots
            .firstOrNull { it.findChild(SRC)?.isDirectory == true }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel --tests "com.devomer.previewgallery.service.ModuleDirectoryResolverTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/service/ModuleDirectoryResolver.kt src/test/kotlin/com/devomer/previewgallery/service/ModuleDirectoryResolverTest.kt
git commit -m "$(cat <<'EOF'
[PG15-5] - Resolve the module directory for index fallback rows

A snapshot outside <moduleDir>/src/screenshotTest could derive no directory
from its path, so its strip was always empty. The project model answers for
exactly those rows and for no others.

Co-Authored-By: Claude <Opus 5> <noreply@anthropic.com>
EOF
)"
```

---

### Task 5 (`PG15-6`): Name the right Gradle task in the message

**Files:**
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties:33`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanelTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `PreviewRenderPanel.showReference(entry: PreviewEntry, images: List<ReferenceStripView.LabelledImage>, skipped: List<String>, tasks: List<String> = emptyList())`; `PreviewRenderPanel.messageForTest(): String?`.

- [ ] **Step 1: Write the failing message tests**

Add to `src/test/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanelTest.kt`:

```kotlin
    fun `test a snapshot with no reference images names the tasks that generate them`() {
        val panel = panel()

        panel.showReference(
            entry("Widget_Default_Snapshot", isSnapshotTest = true),
            emptyList(),
            emptyList(),
            listOf("updateGoogleDebugScreenshotTest", "updateHuaweiDebugScreenshotTest"),
        )

        assertEquals(
            "No reference images — run updateGoogleDebugScreenshotTest, updateHuaweiDebugScreenshotTest.",
            panel.messageForTest(),
        )
    }

    fun `test a snapshot whose variant cannot be read names no task`() {
        val panel = panel()

        panel.showReference(entry("Widget_Default_Snapshot", isSnapshotTest = true), emptyList(), emptyList())

        // Naming updateDebugScreenshotTest here is what the hardcoded root used to do, and it sent a flavoured
        // module's user to a task that module does not have.
        assertEquals(
            "No reference images — run the update…ScreenshotTest task for this module.",
            panel.messageForTest(),
        )
    }

    fun `test a later render clears the task names a snapshot left behind`() {
        val panel = panel()
        panel.showReference(
            entry("Widget_Default_Snapshot", isSnapshotTest = true),
            emptyList(),
            emptyList(),
            listOf("updateDebugScreenshotTest"),
        )

        panel.show(RenderResultView(RenderState.NO_REFERENCE, null, "app"), entry("Other_Snapshot", true))

        assertEquals(
            "No reference images — run the update…ScreenshotTest task for this module.",
            panel.messageForTest(),
        )
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel --tests "com.devomer.previewgallery.ui.PreviewRenderPanelTest"`
Expected: compilation failure — `Unresolved reference: messageForTest` and too many arguments for `showReference`.

- [ ] **Step 3: Split the message**

In `src/main/resources/messages/PreviewGalleryBundle.properties`, replace line 33 with:

```properties
render.noReference=No reference images — run the update…ScreenshotTest task for this module.
render.noReference.task=No reference images — run {0}.
```

Neither string may contain an apostrophe: `render.noReference.task` takes an argument, so `MessageFormat` would swallow one.

- [ ] **Step 4: Carry the task names on the panel**

In `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt`:

Add the field next to `activeState`:

```kotlin
    /** The Gradle tasks that would generate the missing references, for [noReference]. Owned by the snapshot
     *  path alone: the two-argument [show] clears it, so a live render can never inherit a snapshot's advice. */
    private var referenceTasks: List<String> = emptyList()
```

Replace the two-argument `show`:

```kotlin
    /** The render pipeline's entry point: shows one published [RenderResultView]. */
    fun show(view: RenderResultView, entry: PreviewEntry?) {
        referenceTasks = emptyList()
        show(view, entry, strip = null)
    }
```

Replace `showReference`'s signature and first line, leaving the rest of its body as it is:

```kotlin
    fun showReference(
        entry: PreviewEntry,
        images: List<ReferenceStripView.LabelledImage>,
        skipped: List<String>,
        tasks: List<String> = emptyList(),
    ) {
        referenceTasks = tasks
        if (images.isEmpty()) {
```

Replace `noReference`:

```kotlin
    /**
     * A snapshot with nothing committed to show (spec D10): the message names the Gradle task that generates the
     * references, since "no images" without the fix is not actionable — and names the task of the variants that
     * were actually found, since a flavoured module does not have `updateDebugScreenshotTest`. Offers Open file,
     * and deliberately **not** Render the way [failed] does — a snapshot is never rendered at all (spec D8), so a
     * Render button here would be a control that cannot do what it says.
     */
    private fun noReference(entry: PreviewEntry?): JBPanel<*> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        val message = if (referenceTasks.isEmpty()) {
            PreviewGalleryBundle.message("render.noReference")
        } else {
            PreviewGalleryBundle.message("render.noReference.task", referenceTasks.joinToString(", "))
        }
        add(JBLabel(message), BorderLayout.NORTH)
        if (entry != null) add(ActionLink(PreviewGalleryBundle.message("detail.openFile")) { onOpenFile(entry) }, BorderLayout.SOUTH)
    }
```

Add the test seam next to `actionTitlesForTest`:

```kotlin
    @TestOnly
    internal fun messageForTest(): String? =
        UIUtil.findComponentOfType(centerPanel, JBLabel::class.java)?.text
```

Add `import com.intellij.util.ui.UIUtil` if it is not already imported.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel --tests "com.devomer.previewgallery.ui.PreviewRenderPanelTest"`
Expected: PASS, the file's existing tests plus the three new ones.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/messages/PreviewGalleryBundle.properties src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt src/test/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanelTest.kt
git commit -m "$(cat <<'EOF'
[PG15-6] - Name the variant's own update task

The no-reference message named updateDebugScreenshotTest unconditionally, which
a flavoured module does not have. It now names the tasks of the variants that
were found, and names none when it knows none.

Co-Authored-By: Claude <Opus 5> <noreply@anthropic.com>
EOF
)"
```

---

### Task 6 (`PG15-7`): Wire the three-step flow into the panel

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` (`routeSelection`, `loadReferences`, `publishReferences`, `locateReferences`, `decodeReferences`, the test seams)
- Test: `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt`

**Interfaces:**
- Consumes: `ModuleDirectoryResolver.resolve`, `ReferenceRoots.refresh`, `ReferenceRoots.of`, `ReferenceRoots.updateTask`, `ReferenceImageLocator.locate(entry, roots)`, `ReferenceImage.sourceSet`, `PreviewRenderPanel.showReference(..., tasks)`, `PreviewRenderPanel.messageForTest`.
- Produces: `PreviewGalleryPanel.renderMessageForTest: String?`.

- [ ] **Step 1: Write the failing panel tests**

Add to `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt`, next to the existing snapshot tests:

```kotlin
    fun `test a flavoured module shows the references committed under its own variant`() {
        projectWithSnapshot()
        referencePng(
            "src/screenshotTestGoogleDebug/reference/com/example/WidgetSnapshotsKt",
            "Widget_Default_Snapshot_phone_eee23ffd_0.png",
        )
        val panel = panel()
        panel.reloadSynchronously()

        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        // The root used to be the constant src/screenshotTestDebug/reference, so every row of a flavoured
        // module read NO_REFERENCE while its goldens sat on disk.
        assertEquals(RenderState.REFERENCE, panel.renderStateForTest)
    }

    fun `test a flavoured module with nothing committed for this function names its own task`() {
        projectWithSnapshot()
        referencePng(
            "src/screenshotTestGoogleDebug/reference/com/example/OtherSnapshotsKt",
            "Other_Default_Snapshot_phone_eee23ffd_0.png",
        )
        val panel = panel()
        panel.reloadSynchronously()

        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        assertEquals(RenderState.NO_REFERENCE, panel.renderStateForTest)
        assertEquals(
            "No reference images — run updateGoogleDebugScreenshotTest.",
            panel.renderMessageForTest,
        )
    }

    fun `test a module with no reference directory at all names no task`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()

        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        assertEquals(
            "No reference images — run the update…ScreenshotTest task for this module.",
            panel.renderMessageForTest,
        )
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel --tests "com.devomer.previewgallery.ui.PreviewGalleryPanelTest"`
Expected: compilation failure — `Unresolved reference: renderMessageForTest`. Once that seam exists the two flavour tests still fail on `NO_REFERENCE` / the generic message.

- [ ] **Step 3: Split the lookup into three steps**

In `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`, replace `loadReferences` in full:

```kotlin
    /**
     * Resolves [snapshot]'s module directory and locates its reference images under read actions, refreshes the
     * reference directories **between** them without one, and decodes the PNGs after the last one — all on the
     * same background executor, then hands the result to [publishReferences] on the EDT.
     *
     * The three-way split is forced, not stylistic. `ModuleDirectoryResolver` reads the project model and needs
     * the lock; `ReferenceRoots.refresh` is a synchronous VFS refresh, which the platform rejects under one; the
     * listing needs it again. Decoding stays outside for the reason it always has (`RenderPipeline`'s own class
     * doc calls holding the read lock across long work "a prime freeze suspect"): `ImageIO.read` on two
     * device-resolution PNGs is tens of milliseconds that would otherwise block every write action in the IDE.
     */
    private fun loadReferences(snapshot: PreviewEntry) {
        val modality = ModalityState.defaultModalityState()
        AppExecutorUtil.getAppExecutorService().execute {
            val located = try {
                val moduleDirectory = ReadAction.nonBlocking<VirtualFile?> {
                    ModuleDirectoryResolver.resolve(project, snapshot.file)
                }
                    .expireWith(parentDisposable)
                    .executeSynchronously()
                if (moduleDirectory == null) {
                    LocatedReferences(emptyList(), emptyList())
                } else {
                    ReferenceRoots.refresh(moduleDirectory)
                    ReadAction.nonBlocking<LocatedReferences> { locateReferences(snapshot, moduleDirectory) }
                        .expireWith(parentDisposable)
                        .executeSynchronously()
                }
            } catch (e: ProcessCanceledException) {
                // The panel is gone, or a write action preempted the lookup. Nothing to publish and nothing to
                // retry: the selection that would want this result is gone with it.
                return@execute
            }
            val decoded = decodeReferences(located.images)
            ApplicationManager.getApplication().invokeLater(
                {
                    if (disposalCheck.isDisposed) return@invokeLater
                    publishReferences(snapshot, decoded, located.tasks)
                },
                modality,
            )
        }
    }

    /** What one lookup produced: the images to show, and the Gradle tasks to name when there are none. */
    private data class LocatedReferences(val images: List<ReferenceImage>, val tasks: List<String>)
```

Replace `locateReferences` in full:

```kotlin
    /**
     * Finds [snapshot]'s committed reference images under [moduleDirectory] (spec D6). **This** is the half that
     * needs a read action: the VFS directory listing, and nothing else.
     *
     * Every discovered root contributes, and the tasks that would regenerate them are collected here rather than
     * in the panel, because this is where the roots are known — the message has to name the module's own
     * variants, not the `Debug` a library module happens to have.
     */
    private fun locateReferences(snapshot: PreviewEntry, moduleDirectory: VirtualFile): LocatedReferences {
        val roots = ReferenceRoots.of(moduleDirectory)
        return LocatedReferences(
            images = ReferenceImageLocator.locate(snapshot, roots),
            tasks = roots.mapNotNull { ReferenceRoots.updateTask(it.variant) }.distinct().sorted(),
        )
    }
```

Replace `publishReferences`, keeping its existing KDoc and adding one sentence to it:

```kotlin
    private fun publishReferences(snapshot: PreviewEntry, decoded: DecodedReferences, tasks: List<String>) {
        if (selectedSnapshotEntry()?.id != snapshot.id) return
        renderPanel.showReference(snapshot, decoded.images, decoded.skipped, tasks)
    }
```

Replace `decodeReferences`:

```kotlin
    /** Decodes what [locateReferences] found — deliberately holding no read lock (see [loadReferences]); a
     *  `VirtualFile`'s bytes are readable without one, and this is the slow half.
     *
     *  The label carries its source set only when the strip spans more than one: with a single root the variant
     *  alone is unambiguous, and prefixing it would add noise to every module that has no flavours. */
    private fun decodeReferences(references: List<ReferenceImage>): DecodedReferences {
        val images = mutableListOf<ReferenceStripView.LabelledImage>()
        val skipped = mutableListOf<String>()
        for ((reference, label) in references.zip(ReferenceImageLocator.labels(references))) {
            val image = readImage(reference.file)
            if (image == null) {
                skipped += label
            } else {
                images += ReferenceStripView.LabelledImage(label, image)
            }
        }
        return DecodedReferences(images, skipped)
    }
```

Replace the inline branch in `routeSelection` (the `else` of `if (deferReferenceLookup)`):

```kotlin
            } else {
                val moduleDirectory = ModuleDirectoryResolver.resolve(project, snapshot.file)
                val located = if (moduleDirectory == null) {
                    LocatedReferences(emptyList(), emptyList())
                } else {
                    ReferenceRoots.refresh(moduleDirectory)
                    locateReferences(snapshot, moduleDirectory)
                }
                publishReferences(snapshot, decodeReferences(located.images), located.tasks)
            }
```

Add the test seam next to `renderStateForTest`:

```kotlin
    @TestOnly
    internal val renderMessageForTest: String?
        get() = renderPanel.messageForTest()
```

Add the imports `com.devomer.previewgallery.service.ModuleDirectoryResolver` and `com.intellij.openapi.vfs.VirtualFile` if they are not already present.

- [ ] **Step 4: Run the full suite**

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`
Expected: PASS, the whole suite including `test a snapshot the project model places in no module still shows its references` — that test pins Phase 14's path derivation and must keep passing unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt
git commit -m "$(cat <<'EOF'
[PG15-7] - Refresh, then locate across every root

The lookup becomes three steps: resolve the module directory under a read
action, refresh the reference directories without one, then list them under a
read action again. Labels carry the source set only when a strip spans more
than one.

Co-Authored-By: Claude <Opus 5> <noreply@anthropic.com>
EOF
)"
```

---

## Manual gate

The suite cannot cover the thing the phase is about — another process writing files while the IDE is running. Run this against `hepsi-android` after Task 6, from a `runIde` sandbox:

1. Open the gallery, select a snapshot in `features/favorites/ui` that has references. The strip shows them.
2. In the IDE's **embedded terminal** (not an external one — the point is that the IDE frame never loses focus), delete one reference PNG and run
   `./gradlew :features:favorites:ui:updateDebugScreenshotTest -Pandroid.experimental.enableScreenshotTest=true`.
3. Select another row, then the same one again. The regenerated PNG is in the strip, without pressing Refresh and without leaving the IDE window.
4. On a module with **no** references committed, the message names that module's own `update<Variant>ScreenshotTest`, or names no task at all — never `updateDebugScreenshotTest` for a flavoured module.

## Roadmap

After the gate passes, mark **H1** shipped in `docs/snapshot-testing-roadmap.md`: strike the three bullets under "Theme 0 — Hardening what shipped", note the phase (`PG15`) the way F1 notes `PG13, PG14`, and drop H1 from the priority table so F2 becomes item 1. Commit as `[PG15-8] - Record the hardening in the roadmap`.
