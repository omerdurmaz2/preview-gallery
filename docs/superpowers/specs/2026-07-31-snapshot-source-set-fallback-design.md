# Snapshot Source Set Fallback — Read `screenshotTest` from Disk, Not the Index

| | |
|---|---|
| **Scope** | Phase 14 — make snapshot rows independent of whether the `screenshotTest` source set reached the IDE's project model, by discovering and parsing those files from the VFS instead of querying them out of `FileBasedIndex`. |
| **Builds on** | [Phase 13](2026-07-30-snapshot-coverage-badge-design.md) — the coverage badge, the snapshot rows and the reference-image strip, all of which are correct but currently receive no snapshot rows at all. |
| **Tech** | Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase` |
| **Commit prefix** | `[PG14-N]` |

## Goal

Phase 13's manual gate against `hepsi-android` found the feature completely inert: no badges, no
snapshot child rows, not even the orphan branch. The defect that explains it is **attribution** — the
project is imported module-per-source-set, so snapshot rows were filed under a module name that matches
nothing (see the Evidence section).

This phase replaces the input channel. The scanner, the matching, the tree and the reference strip all
stay as they are — only where the snapshot rows come from changes.

## Evidence from the gate

Run against `hepsi-android`, `features/favorites/ui` (50 snapshot functions, 100 reference images):

| Observation | What it rules in or out |
|---|---|
| `Cmd+Shift+F` finds `@PreviewTest` occurrences | The files are on disk and readable. |
| `Shift+Shift` finds `ErrorRetryRow_Default_Snapshot` as a Kotlin function | The file is indexed by Kotlin's own stub index. Search Everywhere also lists non-project files, so this does **not** prove the file is inside `projectScope`. |
| The gallery shows **no** "Snapshots without a preview" branch anywhere | The feature produced no visible rows. It does **not** follow that the index produced none — see below. |
| Module rows are labelled `…features.favorites.ui.main` | The project is imported **module-per-source-set**. `src/main` is its own module. |

**What the gate did not establish.** The first draft of this document read the missing orphan branch as
decisive proof that `PreviewIndexService.compute()` produced zero `isSnapshotTest` rows — that the
files were outside `projectScope` or unattributable. Inspecting Android Studio's cached module model
for the reference project afterwards contradicts that:

| Module | Content roots |
|---|---|
| `hepsi-android.features.favorites.ui` (holder) | `features/favorites/ui`, excludes limited to `.gradle` and `build` |
| `hepsi-android.features.favorites.ui.main` | `features/favorites/ui/src/main`, `src/debug`, generated dirs |

The holder's content root is the module directory, so `src/screenshotTest/**.kt` **is** `isInContent`,
**is** in `GlobalSearchScope.projectScope`, and `ProjectFileIndex.getModuleForFile` returns the holder
module for it. The index very likely *did* produce those 50 rows, attributed to `…favorites.ui` — where
they matched no preview, and where Phase 13's applicability check therefore left `…favorites.ui.main`
unbadged. The absent orphan branch is better explained by `PreviewGalleryPanel.applyFilter`, which runs
orphans through `PreviewModuleFilter` (the active module during the gate would have been `…ui.main`),
or by the branch hanging under the `…ui` node rather than the one being looked at.

**The single defect this phase fixes is therefore attribution**, and D4 is what fixes it: a snapshot
filed under the holder module `…favorites.ui` can never match a preview in `…favorites.ui.main`,
because `SnapshotCoverageResolver` matches within one module name. The VFS channel is not chosen
because the index is blind — on this project it is not — but because probing from content roots is what
makes the attribution correct, and because it does not depend on the source set being modelled at all.

## Non-Goals

- **Changing the consuming project.** Adding `android.experimental.enableScreenshotTest=true` to its
  `gradle.properties` would also fix this, and is worth doing on its own merits, but the plugin must
  work against a repository it does not control and a sync it cannot enforce.
- **Indexing `screenshotTest` into `PreviewIndex`.** A `FileBasedIndex` is the wrong tool for files the
  platform may decline to index; this phase reads them directly instead.
- **Changing the scanner.** `PreviewPsiScanner` and `TargetExtractor` are unchanged — only their input
  channel changes.
- **Changing matching, tree shape, badge copy or what the reference strip shows.** Phase 13's D1–D11
  stand; D7 below changes only how the strip's directory is *resolved*, not which images it finds.
- **Supporting Kotlin Multiplatform source-set module names.** D4's `.main` rule does not cover
  `androidMain` / `commonMain`; no module in the reference project has that shape.
- **Supporting source sets other than `screenshotTest`.**

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Snapshot rows come from a new `SnapshotSourceScanner` that walks `src/screenshotTest` in the **VFS**, reads each `.kt` file through `PsiManager.findFile`, and runs the existing `PreviewPsiScanner.scan` over it, keeping the results with `isSnapshotTest = true`. | Discovering the directory from a module's content roots is what makes **attribution** right (D4) — the actual defect — and it costs nothing extra to have the walk also cover a project where the source set was never modelled. The parsing rules stay in one place: the same scanner produces both sides, so a preview and its snapshot can never be read by two different sets of rules. Only the channel is new. |
| D2 | An **index** row with `isSnapshotTest = true` is dropped when its file lies inside a directory the scanner already walked, and **kept** otherwise. | Inside a scanned directory both channels see the same function, and keeping both would double every snapshot — the scanner's copy wins because it is the better-attributed one (D4). Outside one, the index is the only channel that saw the file at all: a custom `screenshotTest.srcDir`, or a content root that is neither `<moduleDir>` nor `<x>/src/*`, is invisible to the probe, and dropping the whole class of rows would make such a module *less* visible than it was in Phase 13. |
| D3 | The directory is found by **probing from each module's content roots**, without any external-system API: for a content root `R`, check `R/src/screenshotTest`, and — when `R`'s parent is named `src` — `R.parent/screenshotTest`. | Covers both import layouts with no new API surface. A holder module's content root is the module directory (first probe); a source-set module's content root is `<moduleDir>/src/main` (second probe). `ExternalSystemApiUtil`, the obvious alternative, is **not present** in this SDK — verified by scanning every jar under `Contents/lib` and `Contents/plugins/*/lib`. |
| D4 | When several modules probe to the **same** `screenshotTest` directory, the rows are attributed to the module whose name ends with `.main`, falling back to the shortest name. | This is the module that owns the previews, so attribution alone fixes the module-per-source-set mismatch — no name normalisation is needed anywhere else, and `SnapshotCoverageResolver` stays untouched. |
| D5 | `ScreenshotModuleDetector`'s corroboration (Phase 13's I2 fix) is removed. A module is applicable when the VFS channel found a `screenshotTest` directory for it, **or** when a snapshot row survived D2 for it. | The two inputs corroboration was reconciling are now one for every directory the probe recognises, so keeping it would be a check against itself. The second clause is Phase 13's own fallback, not corroboration: rows the index actually produced are evidence in their own right, and a layout this probe does not recognise must not hide them. |
| D6 | Scanning is done inside the existing cached computation, under the same read action, and a file that fails to parse is skipped rather than failing the batch. A file is gated on its text containing `Preview` before its PSI is built — exactly as `PreviewIndex` gates its own input — and the directory walk does not follow symlinks. | One cache, one invalidation story. The corpus is bounded: only modules that adopted screenshot testing are walked, and the pilot module holds ten files. The text gate matters because the cache key is `PsiModificationTracker.MODIFICATION_COUNT`: without it every `.kt` file in the source set is parsed into an AST after any keystroke in any Kotlin file, and `PreviewSearchEverywhereContributor` pays for that inside a *blocking* read action. |
| D7 | `ReferenceImageLocator.locate` takes the snapshot's **module directory**, derived from the file's own path (`…/src/screenshotTest/…` → the parent of `src`), instead of a `Module` resolved through `ProjectFileIndex.getModuleForFile`. | Otherwise the reference strip keeps the exact dependency the rest of this phase removes, and keeps it on the quiet path: if `getModuleForFile` were the failing half, snapshot rows would appear and every one of them would read `NO_REFERENCE`, with nothing on screen saying why. The path is derivable from the row's own file, so no new field is threaded through `PreviewEntry`. |

## Architecture

```
service/
  SnapshotSourceScanner       NEW · VFS discovery + PSI parse -> snapshot PreviewEntry rows
  ScreenshotModuleDetector    DELETED · the scanner answers "is this module applicable?" by construction
  PreviewIndexService         previews from the index, snapshots from the scanner, then the same join
  ReferenceImageLocator       takes the module *directory*, not a Module — see D7
```

Unchanged: `PreviewPsiScanner`, `TargetExtractor`, `SnapshotCoverageResolver`, every `ui/` component,
`ReferenceStripView`.

## Data flow

1. `compute()` reads the index as today, dropping only those `isSnapshotTest` rows whose file the
   scanner already walked (D2).
2. `SnapshotSourceScanner.scan(project)` probes every module's content roots (D3), resolves each
   directory to its owning module (D4), walks it for `.kt` files, parses each and keeps the
   `isSnapshotTest` results as `PreviewEntry` rows. The probe runs **once** per recomputation and its
   result is threaded into both steps 1 and 3 — it visits every module in the project, and the
   reference project has 1371 of them.
3. `resolve()` passes previews and snapshot rows to the unchanged `SnapshotCoverageResolver`, with the
   applicable-module set of D5.
4. Everything downstream — coverage, tree, badges, orphan branch, reference strip — is Phase 13's.

## Error handling

| Situation | Behaviour |
|---|---|
| No `screenshotTest` directory for a module, and no index rows either | Not applicable: no badge, no rows. Exactly today's behaviour for a module that never adopted screenshot testing. |
| Directory exists but holds no `.kt` files | Applicable with zero snapshots: previews read `· no snapshot`, which is the truth. |
| A `.kt` file cannot be resolved to a `KtFile` | That file is skipped; the others still produce rows. |
| A file whose text never contains `Preview` | Skipped before PSI is built, exactly as `PreviewIndex` skips it. `@PreviewTest` contains the substring, so no snapshot is gated out. |
| A file parses but yields no `@PreviewTest` function | Contributes nothing. Not an error — a helper file in the source set is normal. |
| A symlink loop under `screenshotTest` | Not followed: the walk is `NO_FOLLOW_SYMLINKS`. |
| The source set **is** in the project model | The index channel also sees these functions; D2 drops its copies for the directories the scanner walked, so there is exactly one row per snapshot either way. |
| The layout is one the probe does not recognise | The index's own rows are kept and the module stays applicable (D2/D5) — Phase 13's behaviour, not silence. Their **reference strip** is the one thing that degrades: D7 derives its directory from the file path, and such a file is not under `<moduleDir>/src/screenshotTest`, so the strip shows `NO_REFERENCE`. Accepted: the row, its badge and its navigation all still work, and re-adding a `ProjectFileIndex` lookup to recover the images would put the strip back on the dependency D7 removed. |

## Testing

`BasePlatformTestCase`:

- A `src/screenshotTest` file the index cannot see — the directory marked as an **excluded root**, so it
  is genuinely outside `projectScope` — still produces snapshot rows and a `Covered(1)` badge.
- A snapshot in a layout the probe does not recognise still reaches the tree through the index.
- A directory with no `.kt` files yields an applicable module with zero snapshots.
- A snapshot row and its preview in the same module match, end to end, producing `Covered(1)`.
- The index channel's `isSnapshotTest` rows are dropped for a scanned directory: a project where the
  source set *is* modelled produces one row per snapshot, not two.
- A file whose text never mentions `Preview` has no PSI built for it at all.
- Content-root probing, direct: module-directory root, `<moduleDir>/src/main` root, a root that
  resolves to neither. The fixture's own content root is always the module directory, so the shape the
  reference project depends on is unreachable through `directories()` and must be probed directly.
- The reference strip finds its images for a snapshot the project model places in **no** module.

Plain JUnit 4:

- Module attribution when several modules share a directory: `.main` wins; with no `.main`, the
  shortest name wins; a KMP `androidMain` module loses to its holder (a pinned limitation, not a goal).

## Risks

| Risk | Mitigation |
|---|---|
| The probe misses a layout nobody sampled (a module whose content root is neither the module directory nor `<moduleDir>/src/<sourceSet>`, or a custom `screenshotTest.srcDir`). | Such a module falls back to the **index** channel: D2 keeps its rows and D5 keeps it applicable, so it behaves as it did in Phase 13 rather than going silent. Silence only remains for a module the index cannot see either — which is the pre-Phase-13 state, not a regression. Both probe shapes and the fallback are tested. |
| Attribution is wrong for a Kotlin Multiplatform import: `androidMain` does not end in `.main`, so D4's fallback picks the holder module and its previews never match. | Out of scope, and pinned by a test rather than left implicit. No module in the reference project has that shape; the symptom would be `· no snapshot` on rows that do have one, never a crash. |
| Parsing on every cache recomputation costs more than an index lookup. | Bounded three ways: only modules with a `screenshotTest` directory are walked, only their `.kt` files are considered, and only files whose text contains `Preview` are parsed at all (D6). The pilot module holds ten. If a large project makes this visible, the scan result is a natural candidate for its own cache key. |
| `PsiManager.findFile` on a file outside the project model may return a `KtFile` without a module context. | The row's module comes from the probe (D4), not from the file, and the reference strip's directory comes from the file's path (D7) — so no module lookup happens on the file at all. |
| Phase 13's manual-gate diagnostic ("if no badges appear anywhere, the source set is not reaching the index") is now obsolete. | The plan for this phase replaces it. The content-root evidence above says the probe *will* hit on the reference project, so absent badges point at the module filter or at which module node the rows landed under, not at the probe. |
