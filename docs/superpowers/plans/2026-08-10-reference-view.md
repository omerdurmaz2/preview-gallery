# Reference View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a preview row show the committed golden PNGs of every snapshot covering it, through a sticky mode toggle in the render pane.

**Architecture:** The reference lookup moves out of `PreviewGalleryPanel` into `ReferenceStripLoader` (a behaviour-preserving move first, then widened to take several snapshot rows and compose their labels). `PreviewRenderPanel` gains one `ToggleAction` and one callback; `PreviewGalleryPanel` gains one boolean and one branch in `routeSelection`. No new view machinery: `RenderState.REFERENCE`, `ReferenceStripView` and the strip's existing zoom/fit branch are reused unchanged.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install at `platformLocalPath`) · Swing / `JBTabbedPane` · `javax.imageio` · JUnit 4 · `BasePlatformTestCase`

**Spec:** [2026-08-10-reference-view-design.md](../specs/2026-08-10-reference-view-design.md)

## Global Constraints

- **Never use the Kotlin `!!` operator.** Use `?:`, `?.let`, `requireNotNull` or an explicit null check.
- **All source, comments, docs and test names in English.**
- **Do not add explanatory line comments inside function bodies**, except for a decision a reader would otherwise undo. KDoc on new public declarations is expected — this codebase's KDoc documents *why*, not *what*.
- **Tests come last.** Tasks 1–4 are implementation only. Every test in this feature is written in the **Test & Review** phase at the end, then the code review runs. Do not write a test earlier, and do not skip the phase.
- **Do not modify any existing test.** Task 1 is a move, and the existing reference tests passing **unmodified** is its only evidence (spec D8). If an existing test fails, the move changed behaviour — fix the move, never the test.
- **`ReferenceStripView`, `ReferenceImageLocator`, `ReferenceRoots`, `ModuleDirectoryResolver`, `RenderPipeline` and every rendering component are not modified.**
- **`RenderState` gains no new constant.** The mode reuses `REFERENCE` and `NO_REFERENCE` (spec D2).
- **No new gallery-toolbar button, no tree badge** (spec Non-Goals). The only new control is the render pane's own toggle.
- **PNG decoding stays off the EDT and outside every read action.** The existing three-way split in `resolveReferences` is load-bearing and its KDoc explains why — move it verbatim, do not "simplify" it.
- Commit message pattern: `[PG19-N] - Task name` (`PG19-0` is the spike finding, `PG19-1` is the design spec, tasks are `PG19-2` … `PG19-5`, and the final phase is `PG19-6`).
- Commit trailer on every commit: `Co-Authored-By: Claude MODEL <noreply@anthropic.com>`, where `MODEL` is replaced by the model named in **your own** system prompt, with no brackets — e.g. `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`. Never copy another task's value.
- **Verified against this codebase** (do not substitute unverified values):
  - `IndexedPreview.isSnapshotTest: Boolean = false` — how a snapshot row is told from a preview row.
  - `PreviewEntry.snapshots: List<PreviewEntry>` — the snapshot rows covering a preview; empty for an uncovered one.
  - `ReferenceImageLocator.locate(entry: PreviewEntry, roots: List<ReferenceRoots.Root>): List<ReferenceImage>`
  - `ReferenceImageLocator.labels(images: List<ReferenceImage>): List<String>` — qualifies with `sourceSet` only when the list spans more than one source set.
  - `ReferenceRoots.of(moduleDirectory)`, `ReferenceRoots.refresh(moduleDirectory)`, `ReferenceRoots.updateTask(buildVariant)`.
  - `PreviewRenderPanel.showReference(entry, images, skipped, tasks)`.
  - `PreviewRenderPanel.showingSnapshot` is derived from `activeState`, **not** from the entry — see Task 3 Step 1.
- **Build/test command:** `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`. During tasks 1–4 use `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`; Task 1 additionally runs the full suite, because "the existing tests still pass" *is* its deliverable.
- **Never run `./gradlew` while a `runIde` sandbox is live.** Check with exactly `pgrep -f "idea.plugin.in.sandbox.mode=true"` and `pgrep -f "gradlew.*runIde"` before every Gradle invocation; if either prints a pid, stop and report. Do **not** run `./gradlew runIde` — the human runs that gate.
- Baseline before Task 1: **483 tests / 67 classes**, 0 failures.

---

## File Structure

**Create**

| File | Responsibility |
|---|---|
| `src/main/kotlin/com/devomer/previewgallery/ui/ReferenceStripLoader.kt` | Locate + decode the reference PNGs of one or more snapshot rows, and compose their labels |
| `src/test/kotlin/com/devomer/previewgallery/ui/ReferenceStripLoaderTest.kt` | Label composition |

**Modify**

| File | Change |
|---|---|
| `ui/PreviewGalleryPanel.kt` | Delegate the lookup to the loader; add the sticky flag and the third `routeSelection` branch |
| `ui/PreviewRenderPanel.kt` | One `ToggleAction`, one `onToggleReference` callback, one visibility gate |
| `src/main/resources/messages/PreviewGalleryBundle.properties` | One key for the toggle |
| `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt` | New tests only, in the Test & Review phase — **existing tests untouched** |

---

### Task 1 (`PG19-2`): Move the reference lookup into its own class

A pure move. Same behaviour, same threading, same guards, one new file. `PreviewGalleryPanel` keeps its own
`showReferenceImages` / `loadReferences` / `publishReferences` (the debounce, the executor hop and the selection
guard stay with the panel that owns the selection); only the locate-and-decode chain leaves.

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/ReferenceStripLoader.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`

**Interfaces:**
- Consumes: `ModuleDirectoryResolver.resolve(project, file)`; `ReferenceRoots.of/refresh/updateTask`; `ReferenceImageLocator.locate/labels`; `ReferenceStripView.LabelledImage(variant, image)`.
- Produces: `ReferenceStripLoader(project: Project, parentDisposable: Disposable, disposalCheck: CheckedDisposable)`; `ReferenceStripLoader.Located(images: List<ReferenceImage>, tasks: List<String>)`; `ReferenceStripLoader.Decoded(images: List<ReferenceStripView.LabelledImage>, skipped: List<String>)`; `ReferenceStripLoader.locate(snapshot: PreviewEntry): Located`; `ReferenceStripLoader.decode(located: Located): Decoded`.

- [ ] **Step 1: Read what you are moving**

Read `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`, specifically:

- `resolveReferences` (~line 763) and its long KDoc — the three-way split around `ReferenceRoots.refresh` is
  load-bearing and the KDoc says why. **Move the KDoc with the code.**
- `LocatedReferences` (~line 778), `locateReferences` (~line 800), `decodeReferences` (~line 813),
  `readImage` (~line 830), `DecodedReferences` (~line 958).
- `loadReferences` (~line 716) and `routeSelection` (~line 658) — the two callers. They stay in the panel.

- [ ] **Step 2: Create the loader**

Create `src/main/kotlin/com/devomer/previewgallery/ui/ReferenceStripLoader.kt`. The bodies below are the panel's
current ones unchanged; only the receiver moves.

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.ReferenceImage
import com.devomer.previewgallery.service.ModuleDirectoryResolver
import com.devomer.previewgallery.service.ReferenceImageLocator
import com.devomer.previewgallery.service.ReferenceRoots
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.vfs.VirtualFile
import java.awt.image.BufferedImage
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Finds and decodes the committed reference PNGs of a snapshot row.
 *
 * Extracted from [PreviewGalleryPanel] when the preview row's reference mode gave it a second caller (PG19 spec
 * D8). The debounce, the background hop and the "is this row still selected" guard stay with the panel: they are
 * about the selection, which the panel owns. This object is about the files.
 *
 * [locate] and [decode] are separate because their threading rules differ and the callers rely on the seam:
 * [locate] takes read actions and can throw [com.intellij.openapi.progress.ProcessCanceledException], [decode]
 * must hold no read lock at all.
 */
class ReferenceStripLoader(
    private val project: Project,
    private val parentDisposable: Disposable,
    private val disposalCheck: CheckedDisposable,
) {

    /** What one lookup produced: the images to show, and the Gradle tasks to name when there are none. */
    data class Located(val images: List<ReferenceImage>, val tasks: List<String>)

    /** What [decode] found: the variants it could decode, and the ones it could not — reported in the strip's
     *  tooltip rather than dropped silently. */
    data class Decoded(
        val images: List<ReferenceStripView.LabelledImage>,
        val skipped: List<String>,
    )

    /**
     * Resolves [snapshot]'s module directory and locates its reference images under read actions, refreshing the
     * reference directories **between** them without one.
     *
     * The three-way split is forced, not stylistic. `ModuleDirectoryResolver` reads the project model and needs
     * the lock; `ReferenceRoots.refresh` is a synchronous VFS refresh, which the platform rejects under one; the
     * listing needs it again.
     *
     * Callable from the EDT as well as a background thread — which is what lets [PreviewGalleryPanel]'s inline
     * test branch call this directly instead of mirroring its steps — but legal on each for a different reason:
     * which thread calls [ReferenceRoots.refresh] is exactly what decides whether the call is legal, not merely
     * whether a read lock is held. On the EDT the read lock **is** held, and the refresh runs anyway only because
     * the platform exempts the EDT from the check that would otherwise reject it; off the EDT it runs only
     * because this call sits between the two read actions below rather than inside either. Getting either wrong
     * fails silently — a logged error, no refresh, no exception — so the panel would simply keep showing stale
     * images forever instead of crashing; that silent failure mode, not a stylistic preference, is why the lookup
     * stays split into three steps.
     *
     * Returns empty images and tasks when [snapshot] resolves to no module, or when [disposalCheck] fires between
     * the two read actions: the panel that would show the result is gone, so the refresh and the second read
     * action are both work nothing will use. Lets `ProcessCanceledException` propagate rather than catching it
     * here; only the caller's background hop needs to react to it, and it already does.
     *
     * Deleting the [ReferenceRoots.refresh] call below breaks no automated test — steps 2-3 of PG15's manual gate
     * are what actually cover it.
     */
    fun locate(snapshot: PreviewEntry): Located {
        val moduleDirectory = ReadAction.nonBlocking<VirtualFile?> {
            ModuleDirectoryResolver.resolve(project, snapshot.file)
        }
            .expireWith(parentDisposable)
            .executeSynchronously()
            ?: return Located(emptyList(), emptyList())
        if (disposalCheck.isDisposed) return Located(emptyList(), emptyList())
        ReferenceRoots.refresh(moduleDirectory)
        return ReadAction.nonBlocking<Located> { locateUnderRoots(snapshot, moduleDirectory) }
            .expireWith(parentDisposable)
            .executeSynchronously()
    }

    /**
     * Finds [snapshot]'s committed reference images under [moduleDirectory] (PG15 spec D3). **This** is the half
     * that needs a read action: the VFS directory listing, and nothing else.
     *
     * Every discovered root contributes, and the tasks that would regenerate them are collected here rather than
     * in the panel, because this is where the roots are known — the message has to name the module's own
     * variants, not the `Debug` a library module happens to have.
     */
    private fun locateUnderRoots(snapshot: PreviewEntry, moduleDirectory: VirtualFile): Located {
        val roots = ReferenceRoots.of(moduleDirectory)
        return Located(
            images = ReferenceImageLocator.locate(snapshot, roots),
            tasks = roots.mapNotNull { ReferenceRoots.updateTask(it.buildVariant) }.distinct().sorted(),
        )
    }

    /** Decodes what [locate] found — deliberately holding no read lock; a `VirtualFile`'s bytes are readable
     *  without one, and this is the slow half.
     *
     *  Labels come from [ReferenceImageLocator.labels], whose own KDoc states when one carries its source set. */
    fun decode(located: Located): Decoded {
        val images = mutableListOf<ReferenceStripView.LabelledImage>()
        val skipped = mutableListOf<String>()
        for ((reference, label) in located.images.zip(ReferenceImageLocator.labels(located.images))) {
            val image = readImage(reference.file)
            if (image == null) {
                skipped += label
            } else {
                images += ReferenceStripView.LabelledImage(label, image)
            }
        }
        return Decoded(images, skipped)
    }

    /** null when the PNG cannot be read: `ImageIO.read` returns null for a stream no decoder recognises and
     *  throws for an IO failure. Either way that one variant is skipped and reported, never fatal — the other
     *  variants still show (spec's error-handling table). */
    private fun readImage(file: VirtualFile): BufferedImage? =
        try {
            file.inputStream.use { ImageIO.read(it) }
        } catch (e: IOException) {
            thisLogger().warn("Could not read reference image ${file.path}", e)
            null
        }
}
```

- [ ] **Step 3: Delegate from the panel**

In `PreviewGalleryPanel.kt`:

1. Add the field, next to the other collaborators (near `moduleTracker`, ~line 131):

```kotlin
    private val referenceLoader = ReferenceStripLoader(project, parentDisposable, disposalCheck)
```

`disposalCheck` is declared above it (~line 91), so no reordering is needed.

2. **Delete** `resolveReferences`, `locateReferences`, `decodeReferences`, `readImage`, `LocatedReferences` and
   `DecodedReferences` from the panel.

3. In `routeSelection`, the inline branch becomes:

```kotlin
            if (deferReferenceLookup) {
                showReferenceImages(snapshot)
            } else {
                val located = referenceLoader.locate(snapshot)
                publishReferences(snapshot, referenceLoader.decode(located), located.tasks)
            }
```

4. In `loadReferences`:

```kotlin
            val located = try {
                referenceLoader.locate(snapshot)
            } catch (e: ProcessCanceledException) {
                // The panel is gone, or a write action preempted the lookup. Nothing to publish and nothing to
                // retry: the selection that would want this result is gone with it.
                return@execute
            }
            val decoded = referenceLoader.decode(located)
```

5. `publishReferences`'s parameter type changes from the deleted `DecodedReferences` to
   `ReferenceStripLoader.Decoded`. Its body is unchanged.

6. Remove imports that are now unused (`ImageIO`, `BufferedImage`, `IOException`, `ReferenceImageLocator`,
   `ReferenceRoots`, `ModuleDirectoryResolver`, `ReferenceImage`) **only if nothing else in the file still uses
   them** — check each with a search before deleting it. `readImage` is used by more than the reference path in
   some builds; if a second caller exists, leave `readImage` in the panel and let the loader keep its own copy,
   and say so in your report.

- [ ] **Step 4: Compile, then run the whole suite**

Sandbox check first (both `pgrep` patterns), then:

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`
Expected: BUILD SUCCESSFUL, **483 tests / 67 classes**, 0 failures — the same numbers as the baseline, with no
test file modified. That identity is this task's whole deliverable. If a reference test fails, the move changed
behaviour: fix the move.

- [ ] **Step 5: Commit**

Write the message to a file under the session scratchpad and use `git commit -F` — a heredoc broke on an
apostrophe in an earlier phase.

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/ReferenceStripLoader.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt
git commit -F <scratchpad>/pg19-2-msg
```

Message body:

```
[PG19-2] - Move the reference lookup into its own class

The preview row's reference mode gives this chain a second caller, and the panel
was already 1022 lines. Behaviour, threading and guards are unchanged; the
existing reference tests pass unmodified, which is the only evidence a move
gets.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 2 (`PG19-3`): Take several snapshots, and name them when it matters

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/ReferenceStripLoader.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`

**Interfaces:**
- Consumes: Task 1's `ReferenceStripLoader`.
- Produces: `ReferenceStripLoader.locate(snapshots: List<PreviewEntry>): Located`; `Located(groups: List<Located.Group>, tasks: List<String>)`; `Located.Group(snapshotName: String, images: List<ReferenceImage>)`; `decode(located: Located): Decoded` unchanged in signature.

- [ ] **Step 1: Widen `locate` to a list, keeping the groups apart**

The labels must be able to name their snapshot, so the images cannot be flattened before decoding. Replace
`Located` and `locate` in `ReferenceStripLoader.kt`:

```kotlin
    /**
     * What one lookup produced. The images stay grouped by the snapshot row they came from, because [decode]'s
     * label rule needs to know how many snapshots are in play (spec D4) — flattening here would throw away the
     * only thing that distinguishes `Loaded · phone` from `phone`.
     */
    data class Located(val groups: List<Group>, val tasks: List<String>) {

        /** [snapshotName] is the snapshot function's own name, used as a label prefix only when the strip spans
         *  more than one snapshot. */
        data class Group(val snapshotName: String, val images: List<ReferenceImage>)
    }

    /**
     * Locates the reference images of every row in [snapshots] — one row for a snapshot selection, a preview's
     * whole `snapshots` list for the reference mode (spec D3).
     *
     * Each row is resolved independently: two snapshots of the same preview can live in different modules, and
     * the module directory is what the roots hang off. Groups that found nothing are dropped, so a snapshot with
     * no committed PNG cannot put an empty section in the strip; [Located.tasks] still collects that module's
     * regenerating tasks, which is what the no-reference message needs.
     */
    fun locate(snapshots: List<PreviewEntry>): Located {
        val groups = mutableListOf<Located.Group>()
        val tasks = mutableListOf<String>()
        for (snapshot in snapshots) {
            val located = locateOne(snapshot)
            tasks += located.tasks
            if (located.groups.isNotEmpty()) groups += located.groups
        }
        return Located(groups, tasks.distinct().sorted())
    }

    private fun locateOne(snapshot: PreviewEntry): Located {
        val moduleDirectory = ReadAction.nonBlocking<VirtualFile?> {
            ModuleDirectoryResolver.resolve(project, snapshot.file)
        }
            .expireWith(parentDisposable)
            .executeSynchronously()
            ?: return Located(emptyList(), emptyList())
        if (disposalCheck.isDisposed) return Located(emptyList(), emptyList())
        ReferenceRoots.refresh(moduleDirectory)
        return ReadAction.nonBlocking<Located> { locateUnderRoots(snapshot, moduleDirectory) }
            .expireWith(parentDisposable)
            .executeSynchronously()
    }
```

Move `locateOne`'s KDoc from Task 1's `locate` — the three-way-split explanation belongs to the method that
still performs it. `locate`'s own new KDoc is above.

And `locateUnderRoots` returns a group:

```kotlin
    private fun locateUnderRoots(snapshot: PreviewEntry, moduleDirectory: VirtualFile): Located {
        val roots = ReferenceRoots.of(moduleDirectory)
        val images = ReferenceImageLocator.locate(snapshot, roots)
        return Located(
            groups = if (images.isEmpty()) {
                emptyList()
            } else {
                listOf(Located.Group(snapshot.indexed.functionName, images))
            },
            tasks = roots.mapNotNull { ReferenceRoots.updateTask(it.buildVariant) }.distinct().sorted(),
        )
    }
```

- [ ] **Step 2: Compose the labels**

Replace `decode`:

```kotlin
    /**
     * Decodes what [locate] found — deliberately holding no read lock; a `VirtualFile`'s bytes are readable
     * without one, and this is the slow half.
     *
     * A label carries its snapshot's name only when more than one snapshot is on the strip (spec D4): with one,
     * naming it on every image is noise, and the single-snapshot case must read exactly as it did before this
     * feature existed. `ReferenceImageLocator.labels` applies the same rule one level down for the source set,
     * and the two compose — a strip spanning two snapshots and two source sets earns both qualifiers, because
     * without both its rows genuinely cannot be told apart.
     */
    fun decode(located: Located): Decoded {
        val images = mutableListOf<ReferenceStripView.LabelledImage>()
        val skipped = mutableListOf<String>()
        val qualify = located.groups.size > 1
        for (group in located.groups) {
            for ((reference, label) in group.images.zip(ReferenceImageLocator.labels(group.images))) {
                val qualified = if (qualify) "${group.snapshotName} · $label" else label
                val image = readImage(reference.file)
                if (image == null) {
                    skipped += qualified
                } else {
                    images += ReferenceStripView.LabelledImage(qualified, image)
                }
            }
        }
        return Decoded(images, skipped)
    }
```

Note `ReferenceImageLocator.labels` is called **per group**, not over the flattened list: its source-set
qualification asks "does *this* strip section span several roots", and answering it across unrelated snapshots
would qualify labels that are not ambiguous.

- [ ] **Step 3: Update the two call sites**

In `PreviewGalleryPanel.kt`, both callers now pass a list. In `routeSelection`'s inline branch:

```kotlin
                val located = referenceLoader.locate(listOf(snapshot))
                publishReferences(snapshot, referenceLoader.decode(located), located.tasks)
```

and in `loadReferences`:

```kotlin
                referenceLoader.locate(listOf(snapshot))
```

- [ ] **Step 4: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/ReferenceStripLoader.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt
git commit -F <scratchpad>/pg19-3-msg
```

Message body:

```
[PG19-3] - Let one strip span several snapshots

The images stay grouped through the lookup because the label rule needs to know
how many snapshots are in play: with one, naming it on every image is noise and
the single-snapshot strip must read exactly as it always has.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 3 (`PG19-4`): The toggle

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt`
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties`

**Interfaces:**
- Consumes: `PreviewEntry.indexed.isSnapshotTest`, `PreviewEntry.snapshots`.
- Produces: `PreviewRenderPanel.onToggleReference: (Boolean) -> Unit`; `PreviewRenderPanel.referenceModeActive: Boolean` (settable by the owner so the control reflects the sticky flag it does not own).

- [ ] **Step 1: Understand the gate you must NOT use**

Read `PreviewRenderPanel.showingSnapshot` (~line 168):

```kotlin
    private val showingSnapshot: Boolean
        get() = activeState == RenderState.REFERENCE || activeState == RenderState.NO_REFERENCE
```

It is derived from `activeState`, **not** from the entry. Once the reference mode shows a preview's goldens the
panel is in `REFERENCE`, so `showingSnapshot` is true *for a preview row*. Gating the new toggle on it would hide
the toggle exactly while the mode is on — leaving the user no way to switch back.

The toggle's gate therefore asks the **entry**: `!entry.indexed.isSnapshotTest && entry.snapshots.isNotEmpty()`.

Leave `showingSnapshot` itself alone. It answers a different question — "is the pane showing something that is
not a render" — and Properties suppressing itself while goldens are on screen is correct: it would act on
something not being shown.

- [ ] **Step 2: Add the bundle key**

In `src/main/resources/messages/PreviewGalleryBundle.properties`, after `render.copyImage` (~line 44):

```properties
render.showReference=Show committed reference images
```

- [ ] **Step 3: Add the callback and the mirrored flag**

In `PreviewRenderPanel.kt`, beside the other callbacks (~line 47):

```kotlin
    /** Fires when the user toggles the reference mode, with the requested state. The mode itself is owned by
     *  [com.devomer.previewgallery.ui.PreviewGalleryPanel] — it survives selection changes (PG19 spec D5) and
     *  decides what the next selection routes to, neither of which this panel knows about. */
    var onToggleReference: (Boolean) -> Unit = {}

    /** The owner's reference-mode flag, mirrored so the toggle can show its pressed state. Set by the owner, and
     *  never by the action itself: the action reports intent through [onToggleReference] and the owner decides,
     *  which keeps one source of truth for a mode that outlives this panel's current entry. */
    var referenceModeActive: Boolean = false
        set(value) {
            field = value
            updateActionsBar(currentEntry)
        }
```

- [ ] **Step 4: Add the action**

Beside `HandToolAction` (~line 429), matching its shape:

```kotlin
    /** The reference mode as a real toggle so the toolbar shows its pressed state; the state itself lives in the
     *  owner (see [referenceModeActive]). */
    private inner class ShowReferenceAction : ToggleAction(
        PreviewGalleryBundle.message("render.showReference"), null, AllIcons.Actions.Diff,
    ), DumbAware {
        override fun isSelected(e: AnActionEvent): Boolean = referenceModeActive
        override fun setSelected(e: AnActionEvent, state: Boolean) { onToggleReference(state) }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }
```

- [ ] **Step 5: Contribute it from the actions bar**

In `updateActionsBar`, immediately before the Properties block (after the `if (strip != null) … else if …` chain,
before `val activeCopy = activeComparisonView()`):

```kotlin
        // PG19 spec D6: only for a preview that actually has goldens to show, and asked of the ENTRY rather than
        // of [showingSnapshot] — with the mode on, a preview sits in REFERENCE too, and gating on the state would
        // hide the only control that turns it back off.
        val referenceAvailable = entry != null && !entry.indexed.isSnapshotTest && entry.snapshots.isNotEmpty()
        if (referenceAvailable) {
            if (group.childrenCount > 0) group.addSeparator()
            group.add(ShowReferenceAction())
        }
```

- [ ] **Step 6: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL. `AllIcons` and `ToggleAction` are already imported by this file — confirm rather than
re-adding, and add `com.intellij.openapi.project.DumbAware` only if it is not already there.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt src/main/resources/messages/PreviewGalleryBundle.properties
git commit -F <scratchpad>/pg19-4-msg
```

Message body:

```
[PG19-4] - Add the reference mode toggle

Gated on the entry, not on showingSnapshot: with the mode on a preview sits in
REFERENCE as well, so the state gate would hide the only control that turns the
mode back off.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 4 (`PG19-5`): Route a covered preview to its goldens

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`

**Interfaces:**
- Consumes: Task 2's `ReferenceStripLoader.locate(List<PreviewEntry>)`; Task 3's `onToggleReference` and `referenceModeActive`.
- Produces: nothing later tasks build on.

- [ ] **Step 1: Add the flag and a single accessor for what to show**

In `PreviewGalleryPanel.kt`, near `lastSelectedEntry` (~line 102):

```kotlin
    /** Whether a covered preview shows its goldens instead of a live render. Sticky across selections (PG19 spec
     *  D5): the workflow is arrowing down a package looking at committed images, and resetting per selection
     *  would cost a click a row. An uncovered preview shows its live render and leaves the flag alone (D7). */
    private var showReferenceForPreviews = false
```

And, next to `selectedSnapshotEntry`:

```kotlin
    /** The rows whose goldens the current selection should show, or empty when it should render instead. The one
     *  place that answers "reference or render", so [routeSelection] and [publishReferences]' staleness guard
     *  cannot disagree about it. */
    private fun referenceRowsForSelection(): List<PreviewEntry> {
        val snapshot = selectedSnapshotEntry()
        if (snapshot != null) return listOf(snapshot)
        if (!showReferenceForPreviews) return emptyList()
        return selectedEntry()?.snapshots.orEmpty()
    }
```

- [ ] **Step 2: Route through it**

Replace `routeSelection`'s body:

```kotlin
    private fun routeSelection(deferReferenceLookup: Boolean) {
        val rows = referenceRowsForSelection()
        if (rows.isNotEmpty()) {
            val owner = selectedSnapshotEntry() ?: selectedEntry() ?: return
            lastSelectedEntry = null
            pipeline.select(null)
            if (deferReferenceLookup) {
                showReferenceImages(owner, rows)
            } else {
                val located = referenceLoader.locate(rows)
                publishReferences(owner, referenceLoader.decode(located), located.tasks)
            }
            return
        }
        // Anything pending on the reference alarm belongs to a row that is no longer selected: drop it before it
        // starts rather than letting it run and be discarded on arrival by [publishReferences]' guard.
        referenceAlarm.cancelAllRequests()
        val selected = selectedEntry()
        lastSelectedEntry = selected
        pipeline.select(selected)
    }
```

Extend its KDoc: the snapshot branch is now "the selection has goldens to show", which is a snapshot row, or a
covered preview while the mode is on. `owner` is the row the strip is *about* — the entry `showReference` labels
its no-reference message with — and stays the snapshot itself on the snapshot path, so that path is byte-for-byte
what it was.

- [ ] **Step 3: Thread the rows through the debounced path**

`showReferenceImages` and `loadReferences` gain the row list; the owner keeps travelling for the guard:

```kotlin
    private fun showReferenceImages(owner: PreviewEntry, rows: List<PreviewEntry>) {
        referenceAlarm.cancelAllRequests()
        referenceAlarm.addRequest({ loadReferences(owner, rows) }, RenderPipeline.DEBOUNCE_MS)
    }

    private fun loadReferences(owner: PreviewEntry, rows: List<PreviewEntry>) {
        val modality = ModalityState.defaultModalityState()
        AppExecutorUtil.getAppExecutorService().execute {
            val located = try {
                referenceLoader.locate(rows)
            } catch (e: ProcessCanceledException) {
                // The panel is gone, or a write action preempted the lookup. Nothing to publish and nothing to
                // retry: the selection that would want this result is gone with it.
                return@execute
            }
            val decoded = referenceLoader.decode(located)
            ApplicationManager.getApplication().invokeLater(
                {
                    if (disposalCheck.isDisposed) return@invokeLater
                    publishReferences(owner, decoded, located.tasks)
                },
                modality,
            )
        }
    }
```

- [ ] **Step 4: Widen the staleness guard**

`publishReferences` currently asks only whether the snapshot row is still selected. It must now also survive the
mode being switched off mid-decode (spec D9):

```kotlin
    /**
     * EDT half of [showReferenceImages]. Dropped unless the selection *still* wants [owner]'s goldens: arrow-keying
     * down a preview's snapshot children starts one decode per row, and a slower earlier one must not land on top
     * of a later selection — nor on top of a live render, if the user has moved back to a preview or switched the
     * reference mode off meanwhile. Re-deriving the answer from [referenceRowsForSelection] is the whole guard; no
     * separate generation counter can disagree with it.
     */
    private fun publishReferences(owner: PreviewEntry, decoded: ReferenceStripLoader.Decoded, tasks: List<String>) {
        val wanted = referenceRowsForSelection()
        if (wanted.isEmpty()) return
        val currentOwner = selectedSnapshotEntry() ?: selectedEntry()
        if (currentOwner?.id != owner.id) return
        renderPanel.showReference(owner, decoded.images, decoded.skipped, tasks)
    }
```

- [ ] **Step 5: Wire the toggle**

Beside the other `renderPanel.on…` assignments (~line 190):

```kotlin
        renderPanel.onToggleReference = { active ->
            showReferenceForPreviews = active
            renderPanel.referenceModeActive = active
            routeSelection(deferReferenceLookup = true)
        }
```

- [ ] **Step 6: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt
git commit -F <scratchpad>/pg19-5-msg
```

Message body:

```
[PG19-5] - Show a covered preview its goldens

One accessor answers "reference or render" for both the routing and the
staleness guard, so a decode in flight when the mode is switched off cannot land
on top of a live render.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

## Test & Review phase (`PG19-6`)

Everything above shipped without a new test. This phase is where the feature earns them, and it is not optional.

Read `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt` before writing anything: it is the
existing home of the reference tests, and its fixture helpers (how it builds a project with a `screenshotTest`
source set and writes reference PNGs — `referencePng` around line 532) are what these tests must reuse rather than
reinvent. If a helper does not exist for something below, follow the closest one in that file and say so in your
report.

- [ ] **Step 1: Label composition**

Create `src/test/kotlin/com/devomer/previewgallery/ui/ReferenceStripLoaderTest.kt`. `Located` and `Decoded` are
plain data, and `decode` only reads files through `readImage`, so the label rule is testable through
`BasePlatformTestCase` with two real one-pixel PNGs — follow `GoldenInspectorTest`
(`src/test/kotlin/com/devomer/previewgallery/service/GoldenInspectorTest.kt`) for writing PNG bytes into the
fixture, including its `myFixture.tempDirFixture` + `WriteAction.runAndWait<IOException>` form.

Cover:

1. One group, two variants → labels are the bare variants (`phone`, `small`) — the pre-feature reading.
2. Two groups, one variant each → labels carry the snapshot name (`Loaded · phone`, `Error · phone`).
3. A group whose PNG will not decode → its qualified label appears in `skipped`, and the other group's image
   still appears in `images`.

- [ ] **Step 2: The toggle's visibility**

Add to `PreviewGalleryPanelTest.kt`, using its existing `actionTitlesForTest()` route (`PreviewRenderPanel`
exposes it for exactly this):

1. Selecting a **covered** preview lists `Show committed reference images` among the action titles.
2. Selecting an **uncovered** preview does not (spec D6).
3. Selecting a **snapshot** row does not — the mode is about preview rows, and the snapshot row already shows its
   goldens.

- [ ] **Step 3: Sticky behaviour**

Add to `PreviewGalleryPanelTest.kt`:

1. With the mode on, selecting a covered preview shows its goldens rather than a render — assert through the
   panel's state (`activeState == RenderState.REFERENCE`), the same way the existing snapshot-row tests do.
2. With the mode on, selecting an **uncovered** preview does not show a strip, and the mode is still on when a
   covered preview is selected again (spec D5, D7). This is the test that fails if the flag is reset on selection.
3. Switching the mode off returns the covered preview to the render path.

- [ ] **Step 4: The staleness guard**

Add to `PreviewGalleryPanelTest.kt`: with a covered preview selected and the mode on, switching the mode off
before a pending publish lands leaves the panel out of `REFERENCE`. Drive it through the same synchronous route
the existing tests use (`selectByLabelPathForTest`, which routes with `deferReferenceLookup = false`) — if that
route cannot express "a publish already in flight", assert the guard directly by calling `publishReferences`'s
public entry point after toggling the mode off, and say in your report which form you used and why.

- [ ] **Step 5: Run the whole suite**

Sandbox check first, then:

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`

Expected: PASS. Baseline was 483 tests / 67 classes. This phase adds 3 + 3 + 3 + 1 = **10 tests and 1 class**, so
expect **493 tests / 68 classes**.

Iterate until green. **No existing test may be modified** — if one fails, the implementation broke it.

- [ ] **Step 6: Commit the tests**

```bash
git add src/test/kotlin/com/devomer/previewgallery/ui/
git commit -F <scratchpad>/pg19-6-msg
```

Message body:

```
[PG19-6] - Test the reference view

The sticky mode gets the most of it: the flag has to survive a selection onto an
uncovered preview, which is the case that would quietly turn the mode into a
per-row click.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

- [ ] **Step 7: Code review**

Run a review over the whole feature (`PG19-2..PG19-6`) with `superpowers:requesting-code-review`, or ask the human
to run `/code-review`. Fix what it reports before the gate. Pay attention to: PNG decoding that ended up inside a
read action or on the EDT, a staleness guard that can still publish over a live render, the toggle's gate reading
`showingSnapshot` instead of the entry, and any `!!`.

---

## Manual gate

Against `hepsi-android`, from a `runIde` sandbox, after the review:

1. Select a preview that has a snapshot (e.g. anything under `features/favorites/ui`). The toolbar shows
   **Show committed reference images**.
2. Press it. The pane shows the committed golden(s) instead of the live render, and the zoom / Fit / 100% / Save
   PNG controls act on the strip.
3. Arrow down to another covered preview. Its goldens appear **without pressing the toggle again** — this is the
   sticky behaviour, and it is the whole point of the mode.
4. Arrow onto an **uncovered** preview. It renders live, and the toggle is gone. Arrow back onto a covered one:
   the goldens are back and the toggle is still pressed.
5. Find a preview covered by **more than one** snapshot. Its strip shows every snapshot's images, and the labels
   read `<SnapshotName> · <variant>`. On a preview covered by exactly one, the labels are bare variants.
6. Press the toggle off. The live render comes back.
7. Select a **snapshot** row directly. It behaves exactly as it did before this feature — that path was not
   supposed to change.

## Roadmap

After the gate passes, update **F5** in `docs/snapshot-testing-roadmap.md`: it is no longer wholly unbuilt. Record
that the reference half shipped (`PG19`), that the diff half is still gated on the classloader spike named in
[2026-08-10-screenshottest-render-spike.md](../specs/2026-08-10-screenshottest-render-spike.md), and leave F5 in
the priority table with its scope narrowed to the diff. Commit as `[PG19-7] - Record the reference view in the
roadmap`.
