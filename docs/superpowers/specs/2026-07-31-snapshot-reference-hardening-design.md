# Snapshot Reference Hardening — Refresh the VFS, Read Every Variant, Recover the Fallback Rows

| | |
|---|---|
| **Scope** | Phase 15 — close the three defects the Phase 13/14 manual gate did not exercise, all of them on the reference-image strip: a stale VFS listing, a hardcoded build variant, and index-fallback rows that can never resolve a directory. |
| **Builds on** | [Phase 13](2026-07-30-snapshot-coverage-badge-design.md) — the badge, the snapshot rows and the reference strip. [Phase 14](2026-07-31-snapshot-source-set-fallback-design.md) — reading the source set from the VFS, and D7's path-derived module directory. |
| **Tech** | Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase` |
| **Commit prefix** | `[PG15-N]` |

## Goal

Phase 13 and Phase 14 shipped. The gate against `hepsi-android` passed on the path it walked: snapshots
are listed, they hang under the previews they belong to, and their reference images show. Every defect
below sits on a path the gate did **not** walk, and each one produces a wrong or missing image without
saying so.

The shape of all three is the same: the strip shows nothing, and the message tells the user to run a
command. In two of the three cases the user has already run it, or has run the one that actually
applies. That is the specific failure this phase removes — not "no images", but "no images, and the
advice is wrong".

## The three defects

| # | Symptom | Cause | Line |
|---|---|---|---|
| 1 | `updateDebugScreenshotTest` writes PNGs from a terminal; the panel still says *"No reference images — run `updateDebugScreenshotTest`"* | The directory listing comes straight from the VFS with no refresh, so files written outside the IDE are not there yet | `ReferenceImageLocator.locate` — `directory.children.orEmpty()` |
| 2 | Every snapshot in a flavoured module reports no images, and names a task that does not exist for it | The reference root is the constant `src/screenshotTestDebug/reference`; a flavoured module commits to `src/screenshotTest<Flavor>Debug/reference` and regenerates with `update<Flavor>DebugScreenshotTest` | `ReferenceImageLocator.REFERENCE_ROOT`, `PreviewGalleryBundle.render.noReference` |
| 3 | A snapshot row that reached the tree through Phase 14's **index fallback** always reads `NO_REFERENCE` | Its file is not under `<moduleDir>/src/screenshotTest`, so `SnapshotSourceScanner.moduleDirectory` returns null and the lookup returns an empty list before it starts | `PreviewGalleryPanel.locateReferences` |

Defect 1 is why the IDE's own *"Synchronize external changes when switching to the IDE window"* setting
does not cover this: it fires on frame activation, and running Gradle in the IDE's embedded terminal
never deactivates the frame.

Defect 2 has not bitten yet because the pilot module (`features/favorites/ui`) is a library with no
product flavours. The consuming project ships Google and Huawei flavours, so the first flavoured module
that adopts screenshot testing hits it on every row.

Defect 3 is documented in Phase 14's own error-handling table as an accepted degradation. It is
reclassified here: the row is visible, its badge is right, and its images are on disk — showing nothing
for them is a defect once the recovery costs one fallback branch.

## Non-Goals

- **Rendering a `screenshotTest` composable.** Still unverified, still gated on the F5 spike. This phase
  only reads committed PNGs.
- **Running Gradle from the gallery.** The message names the task; it does not offer to run it. That is
  F6.
- **Choosing a variant for the user.** Reading the module's *selected* build variant would need the AGP
  model — the dependency Phase 14 removed. Every committed variant is shown instead (D4).
- **Making the strip cheap to open twice.** No cache is introduced. The lookup is already debounced and
  already off the EDT; a per-selection directory refresh is small enough to pay each time (see Risks).

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | The reference directories are **refreshed from disk before** the lookup, synchronously, on the background thread that already runs it, and **outside** any read action. | A synchronous refresh under a read lock is rejected by the platform ("Do not perform a synchronous refresh under read lock"), so the refresh cannot live inside today's single read action — it has to become its own step. Doing it before every lookup rather than only when the lookup comes back empty leaves no staleness hole: a variant added to a directory that already holds two images would never trigger a refresh-on-empty. |
| D2 | The refresh is **two steps**: a shallow refresh of `<moduleDir>/src` with `reloadChildren`, then a recursive refresh of every `src/screenshotTest*` child it now shows. | One step cannot do it. A shallow refresh of `src` reveals a `screenshotTestGoogleDebug` directory created since the last sync but not its grandchildren, so the `reference` subtree of a first-ever task run stays invisible; a recursive refresh of `src` alone would walk every source file in the module. Two steps are bounded by the snapshot source set and the reference images, and reach both new directories and new files. |
| D3 | The reference root is **discovered**, not hardcoded: every child of `<moduleDir>/src` whose name starts with `screenshotTest` and that has a `reference` child is a root. The variant is the name's remainder (`screenshotTestGoogleDebug` → `GoogleDebug`); an empty remainder means the variant is unknown. | Derivation from what is on disk needs no build model and no configuration, and it is the same posture Phase 14 took for the source set. Requiring the `reference` child is what keeps `src/screenshotTest` — the *source* directory, which matches the same prefix — from being mistaken for a root. |
| D4 | **Every** matching root contributes to one strip. A variant label carries its source set (`googleDebug · phone`) only when more than one root contributed; with a single root the label stays the bare variant it is today. The source-set token is the root's build variant with its first letter lowercased (`GoogleDebug` → `googleDebug`), falling back to the directory name when the build variant is unknown. | Picking one root would reintroduce defect 2 in a quieter form: a golden committed only for Huawei would be hidden with no indication. Showing both is honest, and when the two flavours differ the difference is the thing worth seeing. Labelling only on collision keeps the common single-variant case visually unchanged. |
| D5 | The no-reference message names the task derived from the **matched** variants (`updateGoogleDebugScreenshotTest`); when no root matched, or none yields a variant, it falls back to a message that names no specific task. | Naming a task the module does not have is worse than naming none: it sends the user to a command that fails. Two bundle keys rather than one interpolated string, so the generic form reads as a sentence rather than as a template with a hole. |
| D6 | The snapshot's module directory is resolved by a new `ModuleDirectoryResolver`: Phase 14's path derivation **first**, then `ProjectFileIndex.getModuleForFile` and its content roots as a **fallback**. The first content root with a `src` child wins. | Strictly additive. The fallback runs only where the primary derivation already returned null — rows that show nothing today — so Phase 14's D7 keeps owning every path it owns now, and the failure mode when the model has no answer either is unchanged. Confining the model lookup to one small object keeps `SnapshotSourceScanner` model-free, which is what D7 was protecting. |
| D7 | `ReferenceImage` gains a `sourceSet` field holding D4's token; results are sorted by `(sourceSet, variant)`, both case-insensitive. | The label is composed at the panel boundary, where the whole result set is visible and D4's "more than one root" test can be applied. Sorting by source set first keeps one flavour's images contiguous instead of interleaving two full sets by variant name. |
| D8 | The work is split across two new objects rather than added to `ReferenceImageLocator`: `ReferenceRoots` (discovery, refresh, task naming) and `ModuleDirectoryResolver` (D6). The locator keeps only what it does today — parse a file name, list one directory. | The refresh and the listing have **opposite** locking contracts (D1): one must not hold the read lock, the other is called under it. Carrying both on one object means one doc explaining when each half may be called; splitting them lets each carry a single contract, and leaves the locator's existing tests untouched. |

## Architecture

```
service/
  ReferenceRoots            NEW · discovers src/screenshotTest*/reference, refreshes it, names its task
  ModuleDirectoryResolver   NEW · path derivation, then getModuleForFile as fallback (D6)
  ReferenceImageLocator     loses REFERENCE_ROOT; locate() takes the root list and merges it
model/
  ReferenceImage            gains sourceSet (D7)
ui/
  PreviewGalleryPanel       orchestrates resolve -> refresh -> locate across two read actions and one gap
  PreviewRenderPanel        holds the task names for the no-reference message (D5)
```

`ReferenceRoots`'s surface, and the lock contract each half carries:

```kotlin
data class Root(val sourceSetName: String, val buildVariant: String?, val directory: VirtualFile)

fun refresh(moduleDirectory: VirtualFile)          // MUST NOT hold the read lock; blocking IO
fun of(moduleDirectory: VirtualFile): List<Root>   // read action
fun updateTask(buildVariant: String?): String?     // pure; null when buildVariant is null
```

`Root.directory` is the `reference` directory itself, not the source set that holds it, so
`ReferenceImageLocator` no longer prefixes a root onto the path it derives: its `relativeDirectory`
becomes `packageDirectory`, returning `<package path>/<facade>` alone.

Unchanged: `SnapshotSourceScanner`, `PreviewIndexService`, `SnapshotCoverageResolver`,
`ReferenceStripView`, every tree component.

## Data flow

`PreviewGalleryPanel.loadReferences`, on the existing background executor behind the existing
`referenceAlarm` debounce:

1. **Read action** — `ModuleDirectoryResolver.resolve(project, snapshot.file)`. Needs the lock because
   the fallback half touches `ProjectFileIndex`. Null ends the flow at step 5 with no task names.
2. **No lock** — `ReferenceRoots.refresh(moduleDirectory)`: shallow refresh of `src`, then a recursive
   refresh of each `src/screenshotTest*` child (D1, D2).
3. **Read action** — `ReferenceRoots.of(moduleDirectory)`, then `ReferenceImageLocator.locate(entry,
   roots)`, which lists each root, stamps D4's token onto every image and returns them sorted by
   `(sourceSet, variant)` (D7). Merging belongs to the locator rather than the panel: it is the object
   that knows what a reference image is, and the sort is meaningless on one root's results alone.
4. **No lock** — `ImageIO.read` per image, exactly as today; undecodable images are collected as
   `skipped`.
5. **EDT** — `publishReferences` drops the result unless the row is still selected, then
   `renderPanel.showReference(entry, images, skipped, tasks)`. `tasks` is the distinct, sorted set of
   `updateTask(root)` for the roots found in step 3.

Today's single read action becomes three steps, two of them locked. The split is the same one
`loadReferences` already documents for decoding: the lock is held for the two cheap model/VFS reads and
released for the two slow ones.

Label composition is `ReferenceImageLocator.labels`, applied in step 4: if the images span more than one
`sourceSet`, each label becomes `"$sourceSet · $variant"`; otherwise it stays `variant` (D4). It sits with
the locator rather than the panel because only a merged result knows whether it spans one root or two.

## Error handling

| Situation | Behaviour |
|---|---|
| `ModuleDirectoryResolver` returns null — no path derivation and no module | `NO_REFERENCE` with the generic message. Today's behaviour for these rows, minus the wrong task name. |
| `<moduleDir>/src` does not exist | Refresh is a no-op, `of` returns empty, generic message. |
| Roots exist but none holds a PNG for this function | `NO_REFERENCE`, message naming the matched variants' update tasks (D5). |
| A root's directory name has no variant suffix (`src/screenshotTest/reference`) | It is still a root and its images still show; `updateTask` returns null, so it contributes no task name. If it is the only root, the message is the generic one. |
| Two roots hold the same variant | Both are shown; D4's label disambiguates them by source set. |
| One PNG cannot be decoded | Unchanged: that variant is skipped, named in the strip's tooltip, and the others still show. |
| The refresh blocks behind a pending write action | It is off the EDT and behind the debounce; the panel is unaffected. The subsequent read action is `expireWith(parentDisposable)`, so a disposed panel ends the flow. |
| `ProcessCanceledException` from either read action | Caught where it is caught today: the flow ends with nothing published. The refresh between them holds no lock and leaves no state to unwind. |
| The row's selection changes mid-flight | Unchanged: `publishReferences` re-reads the tree's selection and drops a stale result. |

**On stale image *content*.** The recursive refresh of D2 re-stats every file in the subtree, so a PNG
whose length or modification time changed has its cached content dropped and is re-read. A rewrite that
preserves both is not detected — Gradle does not produce one, so this is theoretical rather than a
known gap.

## Testing

`BasePlatformTestCase` (real VFS, real project fixture):

- `ReferenceRootsTest` — a single `screenshotTestDebug/reference` yields one root with build variant
  `Debug`; two flavour directories yield two roots in a deterministic order; `src/screenshotTest` with no
  `reference` child is not a root; `src/screenshotTest/reference` is a root with a null build variant; a
  module with no `src` yields none.
- `ReferenceRootsRefreshTest` — **the proof of defect 1.** PNGs written with `java.io.File`, bypassing
  the VFS: `of`/`locate` see nothing before `refresh` and see them after. A second case creates the
  whole `screenshotTestGoogleDebug/reference/...` tree on disk after the fixture's VFS snapshot, which
  fails without D2's second step.
- `ModuleDirectoryResolverTest` — a path-derivable file resolves to the same directory
  `SnapshotSourceScanner.moduleDirectory` returns; a file the path rule cannot handle resolves through
  the module's content root; a file in no module resolves to null.
- `ReferenceImageLocatorLocateTest` (extended) — listing within one root; two roots merged;
  `(sourceSet, variant)` ordering; the label rule in both directions (one root bare, two roots
  prefixed).
- `PreviewRenderPanelTest` (extended) — `showReference` with an empty image list and task names renders
  `render.noReference.task` with those names; with none it renders `render.noReference`.

Plain JUnit 4:

- `ReferenceRoots.updateTask` — `GoogleDebug` → `updateGoogleDebugScreenshotTest`, `Debug` →
  `updateDebugScreenshotTest`, null build variant → null.
- `ReferenceImageLocatorTest`'s two `relativeDirectory` cases move to `packageDirectory` and lose the
  `src/screenshotTestDebug/reference/` prefix from their expected value. Its `variantOf` cases are
  untouched — that signature does not change.

## Risks

| Risk | Mitigation |
|---|---|
| A synchronous refresh on every snapshot selection is slow enough to notice. | Bounded three ways: it runs behind the existing `referenceAlarm` debounce, off the EDT, and it is scoped to one module directory — a shallow listing of `src` plus a recursive stat of the snapshot source set and its reference images. The pilot module's worst case is ~100 PNGs and ~10 source files. If a larger module ever makes it visible, the refresh is a natural candidate for a "roots changed since last time" guard, which needs no design change. |
| The refresh is called from somewhere holding a read lock and the platform throws. | The contract is on `ReferenceRoots.refresh`'s own doc (D8) and there is exactly one caller. The split into three steps is the reason the two objects exist rather than one. |
| The glob matches a directory that is not an AGP variant (`src/screenshotTestScratch/reference`), so the message names a task that does not exist. | The same failure the phase is fixing, but inverted and much rarer: it needs a hand-made directory that mimics the plugin's output layout. Accepted rather than guarded — validating the variant would need the build model. |
| `getModuleForFile` resolves defect-3 rows to a module whose content root is not the module directory, so the `src` probe misses. | The resolver tries **each** content root and takes the first with a `src` child, rather than the first root outright. When none qualifies it returns null and the row behaves as it does today. |
| MessageFormat quoting: `render.noReference.task` takes an argument, so a literal apostrophe in it would be swallowed. | Both message strings are written without apostrophes, and the parameterised one is covered by a rendering test. |
| Defect 3's fallback quietly becomes the main path if Phase 14's probe regresses. | It cannot: the fallback runs only when the path derivation returns null, and Phase 14's own tests pin the derivation for both import layouts. |
