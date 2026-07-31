# Snapshot Source Set Fallback — Read `screenshotTest` from Disk, Not the Index

| | |
|---|---|
| **Scope** | Phase 14 — make snapshot rows independent of whether the `screenshotTest` source set reached the IDE's project model, by discovering and parsing those files from the VFS instead of querying them out of `FileBasedIndex`. |
| **Builds on** | [Phase 13](2026-07-30-snapshot-coverage-badge-design.md) — the coverage badge, the snapshot rows and the reference-image strip, all of which are correct but currently receive no snapshot rows at all. |
| **Tech** | Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase` |
| **Commit prefix** | `[PG14-N]` |

## Goal

Phase 13 shipped behind an unverified assumption, recorded as its Risk 1: that a `.kt` file under
`src/screenshotTest` would be indexed and reachable through `GlobalSearchScope.projectScope` even when
the consuming project is synced without `-Pandroid.experimental.enableScreenshotTest=true`. The manual
gate against `hepsi-android` disproved it. The whole feature is inert there: no badges, no snapshot
child rows, not even the orphan branch.

This phase replaces the input channel. The scanner, the matching, the tree and the reference strip all
stay as they are — only where the snapshot rows come from changes.

## Evidence from the gate

Run against `hepsi-android`, `features/favorites/ui` (50 snapshot functions, 100 reference images):

| Observation | What it rules in or out |
|---|---|
| `Cmd+Shift+F` finds `@PreviewTest` occurrences | The files are on disk and readable. |
| `Shift+Shift` finds `ErrorRetryRow_Default_Snapshot` as a Kotlin function | The file is indexed by Kotlin's own stub index. Search Everywhere also lists non-project files, so this does **not** prove the file is inside `projectScope`. |
| The gallery shows **no** "Snapshots without a preview" branch anywhere | Decisive. An unmatched snapshot becomes an orphan regardless of module applicability, so an empty orphan branch means `PreviewIndexService.compute()` produced **zero** rows with `isSnapshotTest = true`. The rows are dropped by the query, not by the matching. |
| Module rows are labelled `…features.favorites.ui.main` | The project is imported **module-per-source-set**. `src/main` is its own module; a source set absent from the Gradle model belongs to no source-set module at all. |

`compute()` drops a row when `GlobalSearchScope.projectScope` excludes the file or when
`ProjectFileIndex.getModuleForFile` returns null — both are silent. Either is sufficient to explain
the observation, and this phase removes the dependency on both rather than establishing which one fired.

The module-per-source-set finding is a second, independent defect: even with the rows present, a
snapshot attributed to the holder module `…favorites.ui` would never match a preview in
`…favorites.ui.main`, because `SnapshotCoverageResolver` matches within one module name.

## Non-Goals

- **Changing the consuming project.** Adding `android.experimental.enableScreenshotTest=true` to its
  `gradle.properties` would also fix this, and is worth doing on its own merits, but the plugin must
  work against a repository it does not control and a sync it cannot enforce.
- **Indexing `screenshotTest` into `PreviewIndex`.** A `FileBasedIndex` is the wrong tool for files the
  platform may decline to index; this phase reads them directly instead.
- **Changing the scanner.** `PreviewPsiScanner` and `TargetExtractor` are unchanged — only their input
  channel changes.
- **Changing matching, tree shape, badge copy or the reference strip.** Phase 13's D1–D11 stand.
- **Supporting source sets other than `screenshotTest`.**

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Snapshot rows come from a new `SnapshotSourceScanner` that walks `src/screenshotTest` in the **VFS**, reads each `.kt` file through `PsiManager.findFile`, and runs the existing `PreviewPsiScanner.scan` over it, keeping the results with `isSnapshotTest = true`. `PreviewIndexService` no longer takes snapshot rows from the index. | The parsing rules stay in one place — the same scanner produces both sides, so a preview and its snapshot can never be read by two different sets of rules. Only the channel is new. |
| D2 | Rows the **index** still produces with `isSnapshotTest = true` are dropped, not merged. | Where the source set *is* in the model, both channels see the same functions; keeping both would double every snapshot. The VFS channel is the one that works in both configurations, so it is the only one. |
| D3 | The directory is found by **probing from each module's content roots**, without any external-system API: for a content root `R`, check `R/src/screenshotTest`, and — when `R`'s parent is named `src` — `R.parent/screenshotTest`. | Covers both import layouts with no new API surface. A holder module's content root is the module directory (first probe); a source-set module's content root is `<moduleDir>/src/main` (second probe). `ExternalSystemApiUtil`, the obvious alternative, is **not present** in this SDK — verified by scanning every jar under `Contents/lib` and `Contents/plugins/*/lib`. |
| D4 | When several modules probe to the **same** `screenshotTest` directory, the rows are attributed to the module whose name ends with `.main`, falling back to the shortest name. | This is the module that owns the previews, so attribution alone fixes the module-per-source-set mismatch — no name normalisation is needed anywhere else, and `SnapshotCoverageResolver` stays untouched. |
| D5 | `ScreenshotModuleDetector`'s corroboration (Phase 13's I2 fix) collapses to a single question: a module is applicable iff the VFS channel found a `screenshotTest` directory for it. | The two inputs it was reconciling are now one. Corroboration existed to catch the disagreement this phase eliminates; keeping it would be a check against itself. |
| D6 | Scanning is done inside the existing cached computation, under the same read action, and a file that fails to parse is skipped rather than failing the batch. | One cache, one invalidation story. The corpus is bounded: only modules that adopted screenshot testing are walked, and the pilot module holds ten files. |

## Architecture

```
service/
  SnapshotSourceScanner       NEW · VFS discovery + PSI parse -> snapshot PreviewEntry rows
  ScreenshotModuleDetector    simplified: applicable == a screenshotTest directory was found
  PreviewIndexService         previews from the index, snapshots from the scanner, then the same join
```

Unchanged: `PreviewPsiScanner`, `TargetExtractor`, `SnapshotCoverageResolver`, every `ui/` component,
`ReferenceImageLocator`, `ReferenceStripView`.

## Data flow

1. `compute()` reads the index as today and **discards** rows with `isSnapshotTest = true` (D2).
2. `SnapshotSourceScanner.scan(project)` probes every module's content roots (D3), resolves each
   directory to its owning module (D4), walks it for `.kt` files, parses each and keeps the
   `isSnapshotTest` results as `PreviewEntry` rows.
3. `resolve()` passes previews and snapshot rows to the unchanged `SnapshotCoverageResolver`, with the
   applicable-module set from the simplified detector (D5).
4. Everything downstream — coverage, tree, badges, orphan branch, reference strip — is Phase 13's.

## Error handling

| Situation | Behaviour |
|---|---|
| No `screenshotTest` directory for a module | Not applicable: no badge, no rows. Exactly today's behaviour for a module that never adopted screenshot testing. |
| Directory exists but holds no `.kt` files | Applicable with zero snapshots: previews read `· no snapshot`, which is the truth. |
| A `.kt` file cannot be resolved to a `KtFile` | That file is skipped; the others still produce rows. |
| A file parses but yields no `@PreviewTest` function | Contributes nothing. Not an error — a helper file in the source set is normal. |
| The source set **is** in the project model | The index channel would also see these functions; D2 drops them, so there is exactly one row per snapshot either way. |

## Testing

`BasePlatformTestCase`:

- A `src/screenshotTest` file outside every source root still produces snapshot rows.
- Rows are attributed to the `.main` module when both it and a holder module probe to the same directory.
- A directory with no `.kt` files yields an applicable module with zero snapshots.
- A snapshot row and its preview in the same module match, end to end, producing `Covered(1)`.
- The index channel's `isSnapshotTest` rows are dropped: a project where the source set *is* modelled
  produces one row per snapshot, not two.

Plain JUnit 4:

- Content-root probing: module-directory root, `src/main` root, a root that resolves to neither.
- Module attribution when several modules share a directory: `.main` wins; with no `.main`, the
  shortest name wins.

## Risks

| Risk | Mitigation |
|---|---|
| The probe misses a layout nobody sampled (a module whose content root is neither the module directory nor `<moduleDir>/src/main`). | The failure mode is the current one — no rows, no badge, silent. D5's rule means such a module is simply not applicable. Both probe shapes are unit-tested. |
| Parsing on every cache recomputation costs more than an index lookup. | Bounded by adoption: only modules with a `screenshotTest` directory are walked, and only their `.kt` files are parsed. The pilot module holds ten. If a large project makes this visible, the scan result is a natural candidate for its own cache key. |
| `PsiManager.findFile` on a file outside the project model may return a `KtFile` without a module context. | The row's module comes from the probe (D4), not from the file, so no module lookup happens on the file at all. |
| Phase 13's manual-gate diagnostic ("if no badges appear anywhere, the source set is not reaching the index") is now obsolete. | The plan for this phase replaces it: badges must appear, and their absence now means the probe failed. |
