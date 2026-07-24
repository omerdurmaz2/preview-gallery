# Preview Property Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Open Android Studio's own `@Preview` property picker from the gallery's render panel, and refresh the render when a value changes.

**Architecture:** A `PreviewPickerBridge` resolves the selected preview's `@Preview` annotation element, asks `PreviewPickerPropertiesModel.fromPreviewElement(...)` for a model, and shows it through `PsiPickerManager`. A `GalleryPickerTracker` implements the picker's analytics interface as a no-op and forwards "something changed" to `RenderPipeline.rerenderCurrent()`. AS writes the edit into the source annotation, so the index and tree refresh through the existing Phase 1 path.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · `org.jetbrains.android` bundled plugin · JUnit 4 · `BasePlatformTestCase`

**Spec:** [2026-07-24-preview-property-picker-design.md](../specs/2026-07-24-preview-property-picker-design.md)

## Global Constraints

- Package and Gradle group: `com.devomer.previewgallery`.
- **Never use the Kotlin `!!` operator.**
- **AS-internal API coupling is allowed only in `render/`: `LiveRenderer`, `RenderModelResolver`, and the two new classes `PreviewPickerBridge` and `GalleryPickerTracker`.** Nothing else may import `com.android.tools.*`.
- **Every AS-internal call site is guarded** against `Exception` and `LinkageError`; a capability probe gates the feature (spec §5).
- The Properties button is only added when the picker API is available — a missing API must be invisible, not a dead control.
- PSI access under a read action; PSI writes go through the platform's own write machinery (the picker does its own writing — do not hand-roll annotation edits).
- Commit message format: `[PG3-N] - Task name`.
- All documentation, code comments, and commit messages in English.
- Phase 1 and Phase 2 behaviour must not regress; the suite is currently 86 tests green.

## Verification style

Tasks 1 and 3 are fully specified. Task 2 is **discovery-with-a-verification-gate**: which PSI element the model
factory accepts (spec P1) and what signals a change (P3) cannot be settled without running the IDE, so that task
ends with a `runIde` check by the user, exactly as the Phase 2 render gate did.

---

### Task 1: Probe the picker API and resolve the `@Preview` element

**Goal:** Know whether the picker API is present, and be able to find the `KtAnnotationEntry` a `PreviewEntry`
came from. No UI yet.

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderApiProbe.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/render/PreviewAnnotationLocator.kt`

**Interfaces:**
- Produces:
  - `RenderApiProbe.isPickerAvailable(): Boolean` — reflective check for `PsiPickerManager`,
    `PreviewPickerPropertiesModel` (+ its `Companion.fromPreviewElement`) and `ComposePickerTracker`.
  - `PreviewAnnotationLocator.findPreviewAnnotation(project, entry): KtAnnotationEntry?` — the `@Preview`
    annotation element for a `PreviewEntry`, resolved from `entry.file` + `entry.indexed.offset`.

- [ ] **Step 1: Extend the probe**

`RenderApiProbe` already verifies the render classes reflectively. Add a second list and a second accessor,
keeping the existing `isAvailable()` untouched:

```kotlin
    private val pickerRequired = listOf(
        "com.android.tools.idea.compose.pickers.PsiPickerManager" to listOf("show"),
        "com.android.tools.idea.compose.pickers.preview.model.PreviewPickerPropertiesModel" to emptyList(),
        "com.android.tools.idea.compose.pickers.preview.model.PreviewPickerPropertiesModel\$Companion" to listOf("fromPreviewElement"),
        "com.android.tools.idea.compose.pickers.base.tracking.ComposePickerTracker" to emptyList(),
    )

    /** Whether Android Studio's own @Preview property picker can be driven on this build (spec §5). */
    fun isPickerAvailable(): Boolean = allPresent(pickerRequired)
```

Factor the existing body of `isAvailable()` into a private `allPresent(required: List<Pair<String, List<String>>>): Boolean`
so both accessors share it, and have `isAvailable()` call `allPresent(required)`.

- [ ] **Step 2: Write the annotation locator**

`PreviewAnnotationLocator.kt` — plain PSI, no `com.android.tools.*`:

```kotlin
package com.devomer.previewgallery.render

import com.devomer.previewgallery.index.PreviewAnnotationMatcher
import com.devomer.previewgallery.index.ImportInfo
import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Finds the `@Preview` annotation element a [PreviewEntry] was indexed from, so the picker can be pointed at it.
 *
 * The index stores an offset into the file rather than a PSI element (a PSI element could not survive in an
 * index), so the element is re-resolved here. Call under a read action.
 */
object PreviewAnnotationLocator {

    fun findPreviewAnnotation(project: Project, entry: PreviewEntry): KtAnnotationEntry? {
        val ktFile = PsiManager.getInstance(project).findFile(entry.file) as? KtFile ?: return null
        val function = ktFile.findElementAt(entry.indexed.offset)?.parentOfType<KtNamedFunction>() ?: return null
        val imports = ktFile.importDirectives.mapNotNull { directive ->
            val fqn = directive.importedFqName?.asString() ?: return@mapNotNull null
            ImportInfo(fqn, directive.aliasName, directive.isAllUnder)
        }
        return function.annotationEntries.firstOrNull { annotation ->
            val reference = annotation.typeReference?.text?.substringBefore('<')?.trim() ?: return@firstOrNull false
            PreviewAnnotationMatcher.matchPreview(reference, imports) != null
        }
    }
}
```

This reuses the Phase 1 matcher, so aliased and star imports resolve exactly as the index resolved them.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render/RenderApiProbe.kt src/main/kotlin/com/devomer/previewgallery/render/PreviewAnnotationLocator.kt
git commit -m "[PG3-1] - Probe the picker API and locate the @Preview element"
```

---

### Task 2: Open the picker (GATE)

**Goal:** Clicking a Properties button opens Android Studio's own picker, pre-filled for the selected preview.
Settles spec unknowns P1 (which PSI element the factory wants), P2 (no-op tracker), P4 (popup placement).

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/render/GalleryPickerTracker.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/render/PreviewPickerBridge.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt`
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties`

**Interfaces:**
- Produces:
  - `GalleryPickerTracker(onModification: () -> Unit) : ComposePickerTracker` — no analytics; calls `onModification`
    from `registerModification` (and see Task 3 for the fallbacks).
  - `PreviewPickerBridge(project)` with `isAvailable(): Boolean` and
    `showPicker(entry: PreviewEntry, at: RelativePoint, onModification: () -> Unit): Boolean`.
  - `PreviewRenderPanel` gains `var onProperties: (PreviewEntry) -> Unit` and shows a Properties action when
    `PreviewPickerBridge.isAvailable()`.

- [ ] **Step 1: Write the tracker**

`ComposePickerTracker` has four members: `pickerShown()`, `pickerClosed()`, `registerModification(String, EditorPickerEvent...PreviewPickerValue, Device)`,
`logUsageData()`. Implement all four; do nothing except forward modifications:

```kotlin
/**
 * Satisfies the picker's analytics interface without reporting anything, and forwards "a value changed" so the
 * gallery can re-render. The proto/Device parameters are part of Android Studio's usage-tracking signature and
 * are deliberately ignored.
 */
class GalleryPickerTracker(private val onModification: () -> Unit) : ComposePickerTracker { ... }
```

If the analytics proto type cannot be referenced at compile time (spec P2), report exactly what the compiler
said before working around it — do not silently switch to reflection.

- [ ] **Step 2: Write the bridge**

`PreviewPickerBridge` is the only place allowed to touch the picker API. It must:
- return false from `showPicker` when `isPickerAvailable()` is false,
- resolve the annotation with `PreviewAnnotationLocator` under a read action,
- resolve the `Module` for `entry.file` (the same way `RenderModelResolver` does),
- create a `SmartPsiElementPointer` for the annotation element
  (`SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)`),
- call `PreviewPickerPropertiesModel.fromPreviewElement(project, module, pointer, tracker)`,
- call `PsiPickerManager.show(point, title, model, Balloon.Position.below)`,
- wrap **everything** in a guard catching `Exception` and `LinkageError`, log once, return false.

**Spec P1 — the pointer's element.** Try the `KtAnnotationEntry` first: `PreviewPropertiesProvider`'s own
constructor takes a `KtAnnotationEntry`, which strongly suggests the factory expects the annotation. If the
runIde check in Step 5 shows the model comes up empty or throws, try the annotated `KtNamedFunction` and record
which one worked.

- [ ] **Step 3: Add the Properties action to the render panel**

Add a bundle key `render.properties=Properties`. In `PreviewRenderPanel`, expose
`var onProperties: (PreviewEntry) -> Unit = {}` and show a small action (an `ActionLink` or an icon button using
`AllIcons.General.Settings`) **only when the picker is available and an entry is selected**. Clicking it calls
`onProperties(entry)` with the button's screen position available to the caller (pass a `RelativePoint` built
from the clicked component, so the popup appears next to the button — spec P4).

Wire it in `PreviewGalleryPanel`: `renderPanel.onProperties = { entry -> pickerBridge.showPicker(entry, point, ::onPickerModification) }`,
where `onPickerModification` is a stub for now (Task 3 connects it to the pipeline).

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew compileKotlin && ./gradlew test`
Expected: BUILD SUCCESSFUL, 86 tests still passing.

- [ ] **Step 5: runIde verification (the gate — needs the user)**

Do NOT commit before this passes. Report what the user must do:

1. `./gradlew runIde`, open a real Compose project, open **Compose Gallery**, select a preview.
2. Click **Properties**.
3. Expected: Android Studio's picker opens, showing that preview's current values (name, group, Device,
   apiLevel, showBackground, …) — the same dialog the editor gutter shows.
4. Change a value (e.g. Device or `showBackground`) and confirm the `@Preview` annotation in the source file is
   updated.

If the picker does not open or the model is empty, capture the exception from `idea.log` and report it — that is
the P1 answer, and the controller decides the next move.

- [ ] **Step 6: Commit (only after the gate passes)**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt src/main/resources/messages/PreviewGalleryBundle.properties
git commit -m "[PG3-2] - Open the Android Studio preview picker from the gallery"
```

---

### Task 3: Re-render after a change

**Goal:** When a property changes, the render refreshes without the user reselecting the preview.

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderPipeline.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`

**Interfaces:**
- Produces: `RenderPipeline.rerenderCurrent()` — re-render the entry currently displayed, without changing
  selection and without restarting the debounce.

- [ ] **Step 1: Add `rerenderCurrent()` to the pipeline**

The pipeline already tracks the current entry for its generation counter. Add:

```kotlin
    /**
     * Re-render whatever is currently selected. Used after the property picker edits the @Preview annotation:
     * the selection has not changed, so [select] would be a no-op, but the source has.
     */
    fun rerenderCurrent() {
        val entry = currentEntry ?: return
        val gen = ++generation
        render(entry, gen)
    }
```

Introduce a `private var currentEntry: PreviewEntry?` set in `dispatch(...)`/`requestBuildAndRender(...)` if the
class does not already keep one. A stale generation must still be ignored, exactly as elsewhere.

- [ ] **Step 2: Connect the picker's change signal**

In `PreviewGalleryPanel`, the callback passed to `showPicker` becomes `{ pipeline.rerenderCurrent() }`. It may
arrive off the EDT — marshal to the EDT before touching Swing, as the rest of the panel does.

**Spec P3 — the change signal.** `GalleryPickerTracker.registerModification` is the primary hook. Confirm in the
runIde check whether it actually fires per edit. If it does not, fall back in this order and record which one was
used:
1. `pickerClosed()` — refresh once when the popup closes.
2. A `PsiTreeChangeListener` scoped to the entry's file, debounced, disposed with the panel.

- [ ] **Step 3: Verify**

Run: `./gradlew compileKotlin && ./gradlew test`
Expected: BUILD SUCCESSFUL, 86 tests passing.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render/RenderPipeline.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt
git commit -m "[PG3-3] - Re-render after a picker change"
```

---

### Task 4: Tests and manual verification

**Files:**
- Create: `src/test/kotlin/com/devomer/previewgallery/render/PreviewAnnotationLocatorTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/render/RenderPipelineRerenderTest.kt`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: `PreviewAnnotationLocatorTest` (`BasePlatformTestCase`)**

Fixtures and assertions:
- a plain `@Preview fun FooPreview()` → the located element is that `@Preview` annotation
- `@Preview(name = "X", showBackground = true) fun FooPreview()` → located, and its text contains `name = "X"`
- two `@Preview`s on one function → returns the first
- `import ...Preview as P` + `@P` → still located (proves the Phase 1 matcher is reused)
- a `@Composable` with no `@Preview` → returns null

- [ ] **Step 2: `RenderPipelineRerenderTest` (plain JUnit, fake renderer)**

- `rerenderCurrent()` with nothing selected → no render is attempted
- after a selection, `rerenderCurrent()` renders the same entry again
- a result from a superseded generation is ignored

- [ ] **Step 3: Run the suite**

Run: `./gradlew test`
Expected: PASS — 86 existing plus the new tests, no skips.

- [ ] **Step 4: Manual verification (AC1–AC7, needs the user)**

In `runIde` against a real Compose project:
- AC1 Properties button appears with a preview selected
- AC2 picker opens pre-filled
- AC3 changing Device/apiLevel writes into the source `@Preview`
- AC4 the render refreshes without reselecting
- AC5 editing `name` updates the tree label
- AC6 undo reverts the change
- AC7 (if feasible) with a probe class name broken, the button is absent and everything else works

- [ ] **Step 5: Changelog and commit**

Add an "Added — preview property picker" entry to `CHANGELOG.md`, then:

```bash
git add src/test/kotlin/com/devomer/previewgallery/render CHANGELOG.md
git commit -m "[PG3-4] - Tests and changelog for the property picker"
```
