# "Show All Previews" Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a **Show all previews** button to the top-right toolbar of Android Studio's Compose preview that switches the editor to code-only and opens this plugin's gallery with the preview under the caret selected.

**Architecture:** A project-level service listens for editor changes and injects a registered `AnAction` into the `DefaultActionGroup` of the `ActionToolbar` whose place is `"NlRhsConfigToolbar"` (Android Studio builds that toolbar programmatically, so there is no XML group to target). The action itself is thin: it resolves the caret's `PreviewEntry` from the index, calls `SplitEditor.selectTextMode(true)` to collapse the editor to code, and reuses the existing tool-window activation path to reveal that entry.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install at `platformLocalPath`) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase`

**Spec:** [2026-07-28-show-all-previews-button-design.md](../specs/2026-07-28-show-all-previews-button-design.md)

## Global Constraints

- **Never use the Kotlin `!!` operator.** Use `?:`, `?.let`, or an explicit null check.
- **All source, comments, docs and test names in English.**
- Commit message pattern: `[PG8-N] - Task name` (this feature's task ids are `PG8-1` … `PG8-6`).
- New production code goes in the new package `com.devomer.previewgallery.editor`; tests mirror it under `src/test/kotlin/com/devomer/previewgallery/editor/`.
- Pure-logic tests use plain JUnit 4 (`@Test` + `org.junit.Assert`), like `ViewTitleTest`. Tests needing a project use `BasePlatformTestCase` with backtick test names prefixed `test `, like `PreviewGalleryPanelTest`.
- User-visible strings go through `PreviewGalleryBundle` / `messages/PreviewGalleryBundle.properties`. No hard-coded UI text.
- Android Studio internals stay isolated: only `PreviewToolbarInjector` may know the toolbar place string, only `SplitEditorSwitcher` may know split-editor classes. Both degrade silently when the API is absent — never crash, never spam the log.
- **Build/test command:** `./gradlew test`. Do **not** run any `./gradlew` task while a `runIde` sandbox is running — kill the sandbox first.

---

## File Structure

| Path | New? | Responsibility |
|---|---|---|
| `src/main/kotlin/com/devomer/previewgallery/editor/CaretPreviewResolver.kt` | new | Pure: caret offset + file → best `PreviewEntry` |
| `src/main/kotlin/com/devomer/previewgallery/editor/ToolbarLocator.kt` | new | Pure: find `ActionToolbar`s by place in a Swing tree |
| `src/main/kotlin/com/devomer/previewgallery/editor/ActionGroupInjector.kt` | new | Pure: idempotent add of an action to a `DefaultActionGroup` |
| `src/main/kotlin/com/devomer/previewgallery/editor/SplitEditorSwitcher.kt` | new | Switch a file's editors to code-only |
| `src/main/kotlin/com/devomer/previewgallery/editor/ShowAllPreviewsAction.kt` | new | The button's behaviour |
| `src/main/kotlin/com/devomer/previewgallery/editor/PreviewToolbarInjector.kt` | new | Project service + `FileEditorManagerListener` that performs the injection |
| `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` | modify | `revealEntry` + pending selection |
| `src/main/resources/META-INF/plugin.xml` | modify | Register the action id and the project listener |
| `src/main/resources/messages/PreviewGalleryBundle.properties` | modify | Button text + description |
| `src/test/kotlin/com/devomer/previewgallery/editor/CaretPreviewResolverTest.kt` | new | Task 1 tests |
| `src/test/kotlin/com/devomer/previewgallery/editor/ActionGroupInjectorTest.kt` | new | Task 2 tests |
| `src/test/kotlin/com/devomer/previewgallery/editor/ToolbarLocatorTest.kt` | new | Task 3 tests |
| `src/test/kotlin/com/devomer/previewgallery/editor/SplitEditorSwitcherTest.kt` | new | Task 4 tests |
| `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt` | modify | Task 5 tests |

---

### Task 1: CaretPreviewResolver

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/editor/CaretPreviewResolver.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/editor/CaretPreviewResolverTest.kt`

**Interfaces:**
- Consumes: `com.devomer.previewgallery.model.PreviewEntry` (fields `indexed: IndexedPreview`, `file: VirtualFile`, `id: String`), `com.devomer.previewgallery.model.IndexedPreview.offset: Int`.
- Produces: `object CaretPreviewResolver { fun resolve(entries: List<PreviewEntry>, file: VirtualFile, caretOffset: Int): PreviewEntry? }`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/devomer/previewgallery/editor/CaretPreviewResolverTest.kt`:

```kotlin
package com.devomer.previewgallery.editor

import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.IndexedPreview
import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaretPreviewResolverTest {

    // LightVirtualFile is a concrete VirtualFile needing no fixture — the same trick SourceFileDisambiguatorTest
    // uses, since this project wires no mocking framework.
    private val fooFile: VirtualFile = LightVirtualFile("Foo.kt")
    private val barFile: VirtualFile = LightVirtualFile("Bar.kt")

    private fun entry(name: String, offset: Int, file: VirtualFile = fooFile) = PreviewEntry(
        IndexedPreview(
            displayName = name,
            functionName = name,
            packageName = "com.example",
            jvmClassName = "com.example.FooKt",
            composableFqn = "com.example.$name",
            offset = offset,
            annotationKind = AnnotationKind.ANDROIDX,
            isPrivate = false,
            hasPreviewParameter = false,
            previewGroup = null,
            unsupportedReason = null,
        ),
        moduleName = "app",
        file = file,
    )

    @Test fun `a caret inside the second preview resolves to the second preview`() {
        val first = entry("First", 100)
        val second = entry("Second", 300)
        assertEquals(second, CaretPreviewResolver.resolve(listOf(first, second), fooFile, 350))
    }

    @Test fun `a caret exactly on a preview offset resolves to that preview`() {
        val first = entry("First", 100)
        val second = entry("Second", 300)
        assertEquals(second, CaretPreviewResolver.resolve(listOf(first, second), fooFile, 300))
    }

    @Test fun `a caret above every preview falls back to the first preview in the file`() {
        val first = entry("First", 100)
        val second = entry("Second", 300)
        assertEquals(first, CaretPreviewResolver.resolve(listOf(second, first), fooFile, 10))
    }

    @Test fun `entries from other files are ignored`() {
        val other = entry("Other", 10, barFile)
        val mine = entry("Mine", 500)
        assertEquals(mine, CaretPreviewResolver.resolve(listOf(other, mine), fooFile, 900))
    }

    @Test fun `a file with no previews resolves to null`() {
        assertNull(CaretPreviewResolver.resolve(listOf(entry("Other", 10, barFile)), fooFile, 900))
    }

    @Test fun `an empty index resolves to null`() {
        assertNull(CaretPreviewResolver.resolve(emptyList(), fooFile, 0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.devomer.previewgallery.editor.CaretPreviewResolverTest"`
Expected: compilation failure — `Unresolved reference: CaretPreviewResolver`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/com/devomer/previewgallery/editor/CaretPreviewResolver.kt`:

```kotlin
package com.devomer.previewgallery.editor

import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.vfs.VirtualFile

/**
 * Picks the preview a caret is "in" using only indexed data.
 *
 * [IndexedPreview.offset] marks a preview's declaration, not its body range, so this approximates containment:
 * the last preview declared at or before the caret wins. A caret above the first preview (imports, package
 * statement) falls back to the file's first preview rather than resolving to nothing.
 */
object CaretPreviewResolver {

    fun resolve(entries: List<PreviewEntry>, file: VirtualFile, caretOffset: Int): PreviewEntry? {
        val inFile = entries.filter { it.file == file }
        if (inFile.isEmpty()) return null
        return inFile.filter { it.indexed.offset <= caretOffset }.maxByOrNull { it.indexed.offset }
            ?: inFile.minByOrNull { it.indexed.offset }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.devomer.previewgallery.editor.CaretPreviewResolverTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/editor/CaretPreviewResolver.kt src/test/kotlin/com/devomer/previewgallery/editor/CaretPreviewResolverTest.kt
git commit -m "[PG8-1] - Resolve the preview under the caret"
```

---

### Task 2: ActionGroupInjector

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/editor/ActionGroupInjector.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/editor/ActionGroupInjectorTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `object ActionGroupInjector { fun addOnce(group: DefaultActionGroup, action: AnAction): Boolean }` — returns `true` when the action was added, `false` when it was already present.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/devomer/previewgallery/editor/ActionGroupInjectorTest.kt`:

```kotlin
package com.devomer.previewgallery.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGroupInjectorTest {

    private class NoopAction : AnAction() {
        override fun actionPerformed(event: AnActionEvent) = Unit
    }

    @Test fun `the action is added to an empty group`() {
        val group = DefaultActionGroup()
        val action = NoopAction()
        assertTrue(ActionGroupInjector.addOnce(group, action))
        assertEquals(listOf<AnAction>(action), group.childActionsOrStubs.toList())
    }

    @Test fun `a second injection of the same action is a no-op`() {
        val group = DefaultActionGroup()
        val action = NoopAction()
        ActionGroupInjector.addOnce(group, action)
        assertFalse(ActionGroupInjector.addOnce(group, action))
        assertEquals(1, group.childActionsOrStubs.size)
    }

    @Test fun `existing children are preserved`() {
        val existing = NoopAction()
        val group = DefaultActionGroup(existing)
        val action = NoopAction()
        ActionGroupInjector.addOnce(group, action)
        assertEquals(listOf<AnAction>(existing, action), group.childActionsOrStubs.toList())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.devomer.previewgallery.editor.ActionGroupInjectorTest"`
Expected: compilation failure — `Unresolved reference: ActionGroupInjector`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/com/devomer/previewgallery/editor/ActionGroupInjector.kt`:

```kotlin
package com.devomer.previewgallery.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * Adds an action to a group exactly once.
 *
 * The injector runs again on every editor switch and on every retry attempt, and Android Studio keeps one
 * toolbar group instance per editor, so the identity check is what keeps a second button from appearing.
 */
object ActionGroupInjector {

    fun addOnce(group: DefaultActionGroup, action: AnAction): Boolean {
        if (group.childActionsOrStubs.any { it === action }) return false
        group.add(action)
        return true
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.devomer.previewgallery.editor.ActionGroupInjectorTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/editor/ActionGroupInjector.kt src/test/kotlin/com/devomer/previewgallery/editor/ActionGroupInjectorTest.kt
git commit -m "[PG8-2] - Idempotent action-group injection"
```

---

### Task 3: ToolbarLocator

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/editor/ToolbarLocator.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/editor/ToolbarLocatorTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `object ToolbarLocator { fun findByPlace(root: Component?, place: String): List<ActionToolbar> }`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/devomer/previewgallery/editor/ToolbarLocatorTest.kt`:

```kotlin
package com.devomer.previewgallery.editor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.BorderLayout
import javax.swing.JPanel

class ToolbarLocatorTest : BasePlatformTestCase() {

    private fun toolbarIn(place: String) =
        ActionManager.getInstance().createActionToolbar(place, DefaultActionGroup(), true)

    fun `test a nested toolbar is found by its place`() {
        val toolbar = toolbarIn("NlRhsConfigToolbar")
        val root = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.EAST) }, BorderLayout.NORTH)
        }
        val found = ToolbarLocator.findByPlace(root, "NlRhsConfigToolbar")
        assertEquals(1, found.size)
        assertSame(toolbar, found.first())
    }

    fun `test a toolbar with another place is not returned`() {
        val root = JPanel(BorderLayout()).apply { add(toolbarIn("NlConfigToolbar").component, BorderLayout.NORTH) }
        assertTrue(ToolbarLocator.findByPlace(root, "NlRhsConfigToolbar").isEmpty())
    }

    fun `test a null root returns nothing`() {
        assertTrue(ToolbarLocator.findByPlace(null, "NlRhsConfigToolbar").isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.devomer.previewgallery.editor.ToolbarLocatorTest"`
Expected: compilation failure — `Unresolved reference: ToolbarLocator`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/com/devomer/previewgallery/editor/ToolbarLocator.kt`:

```kotlin
package com.devomer.previewgallery.editor

import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.util.ui.UIUtil
import java.awt.Component

/**
 * Finds the action toolbars of a Swing subtree by their place string.
 *
 * `ActionToolbarImpl` is itself a component, so a plain UI traversal reaches every toolbar the editor built.
 */
object ToolbarLocator {

    fun findByPlace(root: Component?, place: String): List<ActionToolbar> {
        if (root == null) return emptyList()
        return UIUtil.uiTraverser(root)
            .filter(ActionToolbar::class.java)
            .filter { it.place == place }
            .toList()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.devomer.previewgallery.editor.ToolbarLocatorTest"`
Expected: PASS, 3 tests.

If `UIUtil.uiTraverser(root).filter(ActionToolbar::class.java)` does not compile because the traverser is typed to `Component`, replace the body with an explicit walk:

```kotlin
    fun findByPlace(root: Component?, place: String): List<ActionToolbar> {
        val found = mutableListOf<ActionToolbar>()
        collect(root, place, found)
        return found
    }

    private fun collect(component: Component?, place: String, into: MutableList<ActionToolbar>) {
        if (component == null) return
        if (component is ActionToolbar && component.place == place) into += component
        if (component is java.awt.Container) component.components.forEach { collect(it, place, into) }
    }
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/editor/ToolbarLocator.kt src/test/kotlin/com/devomer/previewgallery/editor/ToolbarLocatorTest.kt
git commit -m "[PG8-3] - Locate editor toolbars by place"
```

---

### Task 4: SplitEditorSwitcher

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/editor/SplitEditorSwitcher.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/editor/SplitEditorSwitcherTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `object SplitEditorSwitcher { fun switchToCodeOnly(project: Project, file: VirtualFile) }`

**Background (verified against the installed Android Studio 253 jars):** `com.android.tools.idea.common.editor.SplitEditor.selectTextMode(boolean)` is `public final`, unannotated; its platform ancestor `com.intellij.openapi.fileEditor.TextEditorWithPreview` exposes `setLayout(Layout)` and `Companion.getParentSplitEditor(FileEditor)`. The `true` argument means "the user explicitly selected this mode", which stops the preview's preferred visibility from re-opening the pane.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/devomer/previewgallery/editor/SplitEditorSwitcherTest.kt`:

```kotlin
package com.devomer.previewgallery.editor

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SplitEditorSwitcherTest : BasePlatformTestCase() {

    fun `test a plain text editor is left alone instead of failing`() {
        val file = myFixture.addFileToProject("Foo.kt", "package com.example\n").virtualFile
        myFixture.openFileInEditor(file)
        // No Compose preview exists in the test fixture, so there is no split editor to switch. The contract is
        // that this degrades to a no-op rather than throwing.
        SplitEditorSwitcher.switchToCodeOnly(project, file)
    }

    fun `test a file that is not open is a no-op`() {
        val file = myFixture.addFileToProject("Bar.kt", "package com.example\n").virtualFile
        SplitEditorSwitcher.switchToCodeOnly(project, file)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.devomer.previewgallery.editor.SplitEditorSwitcherTest"`
Expected: compilation failure — `Unresolved reference: SplitEditorSwitcher`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/com/devomer/previewgallery/editor/SplitEditorSwitcher.kt`:

```kotlin
package com.devomer.previewgallery.editor

import com.android.tools.idea.common.editor.SplitEditor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Collapses every editor showing [file] to code-only.
 *
 * Android Studio's Compose preview lives in a `SplitEditor`, whose `selectTextMode(true)` records that the user
 * asked for code-only — without the flag the preview's preferred visibility can re-open the pane. Files opened in
 * a plain platform split editor fall back to `setLayout(SHOW_EDITOR)`; anything else is left untouched.
 */
object SplitEditorSwitcher {

    fun switchToCodeOnly(project: Project, file: VirtualFile) {
        for (editor in FileEditorManager.getInstance(project).getAllEditors(file)) {
            val split = parentSplitEditor(editor) ?: continue
            if (!selectTextMode(split)) split.layout = TextEditorWithPreview.Layout.SHOW_EDITOR
        }
    }

    private fun parentSplitEditor(editor: FileEditor): TextEditorWithPreview? =
        TextEditorWithPreview.getParentSplitEditor(editor)

    /** Returns false when the Android split editor class is unavailable, so the caller can use the platform path. */
    private fun selectTextMode(split: TextEditorWithPreview): Boolean = try {
        val androidSplit = split as? SplitEditor<*> ?: return false
        androidSplit.selectTextMode(true)
        true
    } catch (_: LinkageError) {
        false
    }
}
```

If `TextEditorWithPreview.getParentSplitEditor(editor)` does not resolve, call it through the companion explicitly: `TextEditorWithPreview.Companion.getParentSplitEditor(editor)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.devomer.previewgallery.editor.SplitEditorSwitcherTest"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/editor/SplitEditorSwitcher.kt src/test/kotlin/com/devomer/previewgallery/editor/SplitEditorSwitcherTest.kt
git commit -m "[PG8-4] - Switch the editor to code-only"
```

---

### Task 5: PreviewGalleryPanel.revealEntry

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` (add a field near `lastSelectedEntry` at line 66, a public method near `selectEntry` at line 202, and edit `applyFilter` at lines 226-243)
- Test: `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt` (append)

**Interfaces:**
- Consumes: existing `selectEntry(entryId: String)`, `applyFilter()`, `searchField`.
- Produces: `fun PreviewGalleryPanel.revealEntry(entryId: String)` — clears the search query, applies the filter, selects the entry; if the entry is not in the tree yet (entries still loading), it is remembered and applied after the next filter pass.

- [ ] **Step 1: Write the failing test**

Append to `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt`, inside the class:

```kotlin
    fun `test revealEntry clears a stale query and selects the entry`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        val panel = panel()
        panel.reloadSynchronously()
        val entry = PreviewIndexService.getInstance(project).findAll().single()
        panel.applyQueryForTest("zzz")
        assertEquals(PreviewGalleryPanel.State.NO_MATCH, panel.state)

        panel.revealEntry(entry.id)

        assertEquals(PreviewGalleryPanel.State.LOADED, panel.state)
        assertEquals(entry.id, panel.selectedEntryIdForTest())
    }

    fun `test an entry revealed before loading is selected once entries arrive`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        // The index service is independent of the panel, so the id is known before the panel has loaded anything.
        val entryId = PreviewIndexService.getInstance(project).findAll().single().id
        val panel = panel()

        panel.revealEntry(entryId)
        assertNull(panel.selectedEntryIdForTest())

        panel.reloadSynchronously()
        assertEquals(entryId, panel.selectedEntryIdForTest())
    }
```

`PreviewIndexService` is already imported by this test file. `assertNull` comes from `BasePlatformTestCase`'s inherited JUnit 3 assertions — no import needed.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.devomer.previewgallery.ui.PreviewGalleryPanelTest"`
Expected: compilation failure — `Unresolved reference: revealEntry`.

- [ ] **Step 3: Write minimal implementation**

In `PreviewGalleryPanel.kt`, add the field right after `private var lastSelectedEntry: PreviewEntry? = null`:

```kotlin
    /** An entry another surface asked to reveal before the tree could show it (the tool window may have been
     *  created by that very request, so [entries] can still be loading). Applied by [applyFilter] and cleared
     *  once the selection lands. */
    private var pendingSelectionId: String? = null
```

Add the public method right after `selectEntry`:

```kotlin
    /**
     * Brings [entryId] into view and selects it, for entry points outside the tool window (PG8: the editor's
     * "Show all previews" button). Unlike [selectEntry] this clears a stale search query first, and survives the
     * entries not being loaded yet.
     */
    fun revealEntry(entryId: String) {
        pendingSelectionId = entryId
        if (searchField.text.isNotEmpty()) searchField.text = ""
        applyFilter()
    }
```

In `applyFilter`, replace the selection-restoring line:

```kotlin
            // No-op if the previously selected entry was filtered out; selection then stays empty.
            if (previousSelectionId != null) selectEntry(previousSelectionId)
```

with:

```kotlin
            val pending = pendingSelectionId
            if (pending != null) {
                // A reveal request outranks the restore: it is an explicit user action, while the restore only
                // exists to survive the rebuild. Keep it pending until the node actually exists.
                selectEntry(pending)
                if (selectedEntry()?.id == pending) pendingSelectionId = null
            } else if (previousSelectionId != null) {
                // No-op if the previously selected entry was filtered out; selection then stays empty.
                selectEntry(previousSelectionId)
            }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.devomer.previewgallery.ui.PreviewGalleryPanelTest"`
Expected: PASS, all tests including the two new ones.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt
git commit -m "[PG8-5] - Reveal an entry from outside the tool window"
```

---

### Task 6: The action, its registration and the toolbar injector

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/editor/ShowAllPreviewsAction.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/editor/PreviewToolbarInjector.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties`

**Interfaces:**
- Consumes: `CaretPreviewResolver.resolve` (Task 1), `ActionGroupInjector.addOnce` (Task 2), `ToolbarLocator.findByPlace` (Task 3), `SplitEditorSwitcher.switchToCodeOnly` (Task 4), `PreviewGalleryPanel.revealEntry` (Task 5), and the existing `PreviewIndexService.getInstance(project).findAll()` + `PreviewGalleryToolWindowFactory.ID`.
- Produces: action id `PreviewGallery.ShowAllPreviews`; `PreviewToolbarInjector.getInstance(project)`.

This task has no automated test of its own: it is pure wiring between units already covered by Tasks 1-5, and the only thing left to verify — that the button really lands in Android Studio's preview toolbar — cannot be reproduced in `BasePlatformTestCase` (the fixture has no Compose preview surface). Step 6 is a manual gate in `runIde`.

- [ ] **Step 1: Add the bundle strings**

Append to `src/main/resources/messages/PreviewGalleryBundle.properties`:

```properties
action.showAllPreviews.text=Show all previews
action.showAllPreviews.description=Close the preview pane and browse every @Preview in the project
```

- [ ] **Step 2: Write the action**

Create `src/main/kotlin/com/devomer/previewgallery/editor/ShowAllPreviewsAction.kt`:

```kotlin
package com.devomer.previewgallery.editor

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.service.PreviewIndexService
import com.devomer.previewgallery.ui.PreviewGalleryPanel
import com.devomer.previewgallery.ui.PreviewGalleryToolWindowFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * Hands the user off from Android Studio's per-file preview to the project-wide gallery: the editor collapses to
 * code-only and the tool window opens on the preview the caret sits in.
 *
 * The three effects are independent on purpose (design D7): a file whose previews are not indexed yet still gets
 * the code-only switch and the gallery, just without a selection.
 */
class ShowAllPreviewsAction : AnAction(
    PreviewGalleryBundle.message("action.showAllPreviews.text"),
    PreviewGalleryBundle.message("action.showAllPreviews.description"),
    AllIcons.Actions.ListFiles,
),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        // Read the caret from the selected text editor rather than the event: the toolbar this button is injected
        // into belongs to the design surface, whose data context carries no editor.
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        val caretOffset = editor?.caretModel?.offset ?: 0

        if (file != null) SplitEditorSwitcher.switchToCodeOnly(project, file)

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(PreviewGalleryToolWindowFactory.ID)
            ?: return
        toolWindow.activate({ if (file != null) revealCaretPreview(project, toolWindow, file, caretOffset) }, false)
    }

    /** Resolves the caret's preview off the EDT (the index read must not block it) and reveals it on the EDT. */
    private fun revealCaretPreview(project: Project, toolWindow: ToolWindow, file: VirtualFile, caretOffset: Int) {
        ReadAction.nonBlocking<String?> {
            CaretPreviewResolver.resolve(PreviewIndexService.getInstance(project).findAll(), file, caretOffset)?.id
        }
            .expireWith(toolWindow.disposable)
            .finishOnUiThread(ModalityState.defaultModalityState()) { entryId ->
                if (entryId == null) return@finishOnUiThread
                toolWindow.contentManager.contents
                    .firstNotNullOfOrNull { it.component as? PreviewGalleryPanel }
                    ?.revealEntry(entryId)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    companion object {
        const val ID = "PreviewGallery.ShowAllPreviews"
    }
}
```

- [ ] **Step 3: Write the toolbar injector**

Create `src/main/kotlin/com/devomer/previewgallery/editor/PreviewToolbarInjector.kt`:

```kotlin
package com.devomer.previewgallery.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm

/**
 * Puts [ShowAllPreviewsAction] into the Compose preview's top-right toolbar.
 *
 * That toolbar has no XML group and no extension point — Android Studio builds it programmatically as a
 * `DefaultActionGroup` inside an `ActionToolbar` whose place is [RHS_TOOLBAR_PLACE] — so the button is injected at
 * runtime. It is also built lazily, only once the preview pane exists, hence the bounded retry: after
 * [MAX_ATTEMPTS] misses the injector gives up silently and the action stays reachable through Find Action.
 *
 * [RHS_TOOLBAR_PLACE] is the single point of contact with that internal layout; if a future Studio renames it, the
 * feature loses its button, not its correctness.
 */
@Service(Service.Level.PROJECT)
class PreviewToolbarInjector(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** Schedules injection attempts for [file]'s editors, restarting the retry window. */
    fun scheduleInjection(file: VirtualFile) {
        alarm.cancelAllRequests()
        scheduleAttempt(file, attempt = 0)
    }

    private fun scheduleAttempt(file: VirtualFile, attempt: Int) {
        if (attempt >= MAX_ATTEMPTS || project.isDisposed) return
        alarm.addRequest({
            if (!inject(file)) scheduleAttempt(file, attempt + 1)
        }, RETRY_DELAY_MS)
    }

    /** Returns true once the action sits in at least one of [file]'s preview toolbars. */
    private fun inject(file: VirtualFile): Boolean {
        if (project.isDisposed) return true
        val action = ActionManager.getInstance().getAction(ShowAllPreviewsAction.ID) ?: return true
        var present = false
        for (editor in FileEditorManager.getInstance(project).getAllEditors(file)) {
            for (toolbar in ToolbarLocator.findByPlace(editor.component, RHS_TOOLBAR_PLACE)) {
                val group = toolbar.actionGroup as? DefaultActionGroup ?: continue
                if (ActionGroupInjector.addOnce(group, action)) toolbar.updateActionsImmediately()
                present = true
            }
        }
        return present
    }

    override fun dispose() = Unit

    /** Re-runs the injection whenever the editor selection changes or a file is opened. */
    class Listener : FileEditorManagerListener, DumbAware {

        override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
            getInstance(source.project).scheduleInjection(file)
        }

        override fun selectionChanged(event: FileEditorManagerEvent) {
            val file = event.newFile ?: return
            getInstance(event.manager.project).scheduleInjection(file)
        }
    }

    companion object {
        /** The place `ActionsToolbar` gives the preview's north-east (top-right) group in Android Studio 253. */
        const val RHS_TOOLBAR_PLACE = "NlRhsConfigToolbar"
        private const val MAX_ATTEMPTS = 8
        private const val RETRY_DELAY_MS = 400

        fun getInstance(project: Project): PreviewToolbarInjector = project.service()
    }
}
```

- [ ] **Step 4: Register the action and the listener**

In `src/main/resources/META-INF/plugin.xml`, add after the closing `</extensions>` of the `com.intellij` block:

```xml
    <projectListeners>
        <listener class="com.devomer.previewgallery.editor.PreviewToolbarInjector$Listener"
                  topic="com.intellij.openapi.fileEditor.FileEditorManagerListener"/>
    </projectListeners>

    <actions>
        <!--
            Registered by id so the button survives the toolbar injection failing: Android Studio's Compose preview
            toolbar is programmatic and cannot be reached with add-to-group, so PreviewToolbarInjector adds this
            instance at runtime. Without a toolbar the action is still reachable from Find Action / a shortcut.
        -->
        <action id="PreviewGallery.ShowAllPreviews"
                class="com.devomer.previewgallery.editor.ShowAllPreviewsAction"/>
    </actions>
```

- [ ] **Step 5: Build and run the whole suite**

Run: `./gradlew build test`
Expected: BUILD SUCCESSFUL, every test passes. Fix compile errors before continuing — in particular confirm that `PreviewToolbarInjector$Listener` is the name the platform resolves (a Kotlin nested class is `Outer$Inner` in plugin.xml).

- [ ] **Step 6: Manual gate in the sandbox IDE**

Run: `./gradlew runIde`

In the sandbox project:
1. Open a Kotlin file with at least two `@Preview` functions and let Android Studio's preview pane render.
2. Confirm a **Show all previews** button appears in the preview's **top-right** toolbar, beside the issue-notification icon.
3. Put the caret inside the *second* preview function and click the button.
4. Expect: the editor becomes code-only (no preview pane), the **Compose Gallery** tool window opens, and the *second* preview is selected and rendering.
5. Re-open Split mode from the editor's own toolbar, then click the button again — confirm exactly **one** button is present (no duplicate from the re-injection).
6. Close the sandbox before running any further Gradle task.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/editor/ShowAllPreviewsAction.kt src/main/kotlin/com/devomer/previewgallery/editor/PreviewToolbarInjector.kt src/main/resources/META-INF/plugin.xml src/main/resources/messages/PreviewGalleryBundle.properties
git commit -m "[PG8-6] - Inject the Show all previews button into the preview toolbar"
```

---

## Verification checklist

- [ ] `./gradlew build test` is green with no sandbox running.
- [ ] No `!!` anywhere in the new code.
- [ ] `"NlRhsConfigToolbar"` appears in exactly one file (`PreviewToolbarInjector`).
- [ ] `SplitEditor` / `TextEditorWithPreview` appear in exactly one file (`SplitEditorSwitcher`).
- [ ] The manual gate's steps 2, 4 and 5 all passed.
