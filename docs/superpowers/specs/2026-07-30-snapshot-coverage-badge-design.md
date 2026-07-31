# Snapshot Coverage — Badge the Tree, Show the Reference Images

| | |
|---|---|
| **Scope** | Phase 13 — index the `@PreviewTest` functions of the Compose Preview Screenshot Testing plugin, badge each preview row with whether the composable it shows has a snapshot, and display the committed reference PNGs when a snapshot row is selected. |
| **Builds on** | [Phase 12](2026-07-29-preview-fit-to-view-design.md) — the render panel's zoom/fit behaviour, which the reference-image strip reuses. Feature **F1** of the [snapshot testing roadmap](../../snapshot-testing-roadmap.md). |
| **Tech** | Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase` |
| **Commit prefix** | `[PG13-N]` |

## Goal

The consuming project's rule is *"a composable change means a snapshot change"* — but nothing in the IDE
says which composables have snapshots. Finding out means opening `src/screenshotTest`, reading fifty
function bodies, and matching them by eye to the composables they render. The gallery already indexes
every `@Preview` in the project and already groups them by module and package; the missing half is the
`screenshotTest` side of the same picture.

This phase adds that half. Each preview row states, in words, whether the composable it shows is
covered. Each snapshot appears as a child row of the preview it corresponds to, and selecting it shows
the reference PNGs that are actually committed to the repository — the images the build compares
against, not a fresh render of them.

## Non-Goals

- **Live rendering of `screenshotTest` composables** — a snapshot row never reaches layoutlib. Whether
  the render pipeline can resolve a composable in that source set is unverified, and this phase is
  deliberately independent of the answer.
- **Diffing live renders against references** — roadmap F5.
- **Running `updateDebugScreenshotTest` / `validateDebugScreenshotTest`** — roadmap F6.
- **Generating snapshot files** — roadmap F3.
- **Coverage filter and exportable report** — roadmap F2; this phase produces the per-row facts those
  build on, and nothing more.
- **Reading the HTML report or the build directory** — only committed sources under `src/` are read.
- **Supporting screenshot engines other than `com.android.compose.screenshot`** — Paparazzi and
  Roborazzi have different markers and different reference layouts.

## Current state (what this builds on)

`PreviewIndex` is a `FileBasedIndex` over every `.kt` file whose text contains `Preview`, keyed by
composable FQN; `PreviewPsiScanner` extracts the file-local facts into `IndexedPreview`, and
`PreviewAnnotationMatcher` identifies `@Preview` from the file's own import list without resolving
anything. `PreviewIndexService` joins index values with the module and `VirtualFile` at query time over
`GlobalSearchScope.projectScope`, and caches the result. `PreviewTreeModelBuilder` and
`PackageTreeBuilder` turn the rows into a module → package → preview tree that
`PreviewTreeCellRenderer` draws. `PreviewRenderPanel` hosts the render states in a `ZoomableRenderView`
inside a `JBScrollPane`, with fit-to-view and a zoom ladder (Phase 12).

Two facts about the current behaviour matter here. First, because the index only gates on the text
`Preview`, snapshot functions written with a plain `@Preview` are **already** indexed and appear in the
tree as ordinary previews (when `src/screenshotTest` reaches the project model in the first place —
[Phase 14](2026-07-31-snapshot-source-set-fallback-design.md) found that this is not always true, and
reads those files from the VFS instead), while ones written with the project's `@SnapshotPreviews`
multipreview are not — the matcher is file-local and cannot resolve a custom annotation declared in
another file. That inconsistency is fixed here as a side effect of D1. Second,
`IndexedPreview.jvmClassName` already holds the JVM facade class name, which turns out to be exactly the
directory name the reference PNGs live in.

## Evidence

Gathered from `features/favorites/ui` in the consuming project (50 snapshot functions, 100 reference
images) while designing this phase.

**Function names do not correspond.** Matching a preview to its snapshot by name fails on real data:

| `src/main` preview | `src/screenshotTest` snapshot | Composable both show |
|---|---|---|
| `ErrorRetryRowPreview` | `ErrorRetryRow_Default_Snapshot` | `ErrorRetryRow` |
| `FavoritesFullScreenSkeletonPreview` | `FavoritesSkeleton_FullScreen_Snapshot` | `FavoritesFullScreenSkeleton` |
| `AddProductsToListContentEmptyPreview` | `AddProductsToListContent_Empty_Snapshot` | `AddProductsToListContent` |

The bodies, however, do correspond — both call the same composable:

```kotlin
// src/main
@Preview
@Composable
private fun ErrorRetryRowPreview() = PreviewComponent {
    ErrorRetryRow(onRetry = {})
}

// src/screenshotTest
@PreviewTest
@SnapshotPreviews
@Composable
internal fun ErrorRetryRow_Default_Snapshot() = PreviewComponent {
    PrimusTheme {
        ErrorRetryRow(onRetry = {})
    }
}
```

**Orphans exist in both directions.** Snapshots with no `main` preview (`NoResultRenderer_Snapshot`,
`FavoritesContent_Loading_Snapshot`) and previews with no snapshot (`MoveProductsBottomSheet_Preview` —
a modal sheet, which the project's own guidance says renders blank and should not be snapshotted). So
coverage is a property of the **composable**, not of a preview function, and neither side can be
treated as the complete list.

**Reference paths are deterministic.** Every one of the 100 images follows:

```
<module>/src/screenshotTestDebug/reference/<package as directories>/<JVM facade class>/<function>_<variant>_<hash>_<index>.png
```

```
…/favorites/component/ComponentsSnapshotsKt/ErrorRetryRow_Default_Snapshot_phone_eee23ffd_0.png
```

The hash is a property of the `@Preview` configuration, not of the function: `phone` is `eee23ffd` and
`small` is `72f29e0e` across all 100 files. So the hash never needs to be computed — a prefix glob on
`<function>_` finds the images and the variant name is read back out of the file name.

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | A function is a **snapshot** iff `@PreviewTest` (`com.android.tools.screenshot.PreviewTest`) is written directly on it, recognised by extending `PreviewAnnotationMatcher` with the same import-list technique used for `@Preview`. `IndexedPreview` gains `isSnapshotTest: Boolean`. A snapshot function is **never** emitted as an ordinary preview row. | `@PreviewTest` is present on every snapshot function and is a plain import, so it is decidable file-locally — unlike `@SnapshotPreviews`, a custom multipreview declared in another file, which an indexer must not resolve. Also fixes today's inconsistency where plain-`@Preview` snapshot files pollute the tree. |
| D2 | `IndexedPreview` gains `targets: List<String>` — the composables the function *shows*. Extraction descends **only through trailing lambdas** from the function body, never into argument lists, and keeps only **PascalCase** callee names. The deepest lambda body reached contributes its calls; if that body holds several calls, all of them are targets. | Wrapper calls (`PreviewComponent`, `PrimusTheme`, `Column`) are consumed by the descent itself, so no configurable wrapper list is needed and nothing is hardcoded to one project. Skipping argument lists keeps `state = fakeState()` and `state = FakeState(…)` out of the target set. PascalCase is the Compose naming convention and excludes helper calls. |
| D3 | Coverage is computed **at query time** in `PreviewIndexService`: a preview is covered iff some record in the **same module** has `isSnapshotTest = true` and a `targets` set that intersects the preview's. Names are never compared. | The evidence table shows name matching failing on all three sampled pairs. Module scope is enough of a narrowing without requiring package equality, which the SDUI renderer snapshots break. Query time, not index time, because it is a cross-file relation and the index stores only file-local facts. |
| D4 | Tree layout: a snapshot is a **child row of the preview it corresponds to**. Snapshots that match no preview are collected under a per-module branch **"Snapshots without a preview"**. | The user chose visible rows over an icon-only badge: another developer opening the plugin should be able to see the snapshots, and the parent/child pairing makes the match visually checkable. The orphan branch exists because roughly a fifth of the sampled snapshots have no `main` preview to hang under. |
| D5 | The badge is **text plus icon**, never icon alone: `· 1 snapshot` / `· 2 snapshots` on a covered row, `· no snapshot` on an uncovered one. It is drawn **only in modules that have a `src/screenshotTest` source directory**; elsewhere the row renders exactly as it does today. | Icons alone do not communicate to a first-time user. Badging every module would paint the whole project as failing and the signal would be discarded — the plugin is general-purpose and most modules will never adopt screenshot testing. |
| D6 | Reference PNGs are resolved **at selection time** from the VFS, not indexed: `<module content root>/src/screenshotTestDebug/reference/<packageName as path>/<jvmClassName simple name>/<functionName>_*.png`, with the variant read from the file name segment after the function name. | The path is fully derivable from facts the index already holds (`packageName`, `jvmClassName`, `functionName`), so storing it would duplicate state that a regenerate invalidates. Prefix glob avoids computing the configuration hash. |
| D7 | All variants of the selected snapshot are shown **side by side in one panel**, each labelled with its variant name underneath. Zoom and pan apply to the whole strip at one shared scale; fit-to-view fits the strip, not a single image. | The user chose side-by-side over tabs so `phone` and `small` are comparable at a glance — which is what the narrow variant exists to catch. One shared scale keeps the comparison honest; per-image scales would make different widths look identical. |
| D8 | A snapshot row shows images only. It is **never** handed to `RenderPipeline` or layoutlib. | Keeps this phase independent of the unverified question of whether `screenshotTest` composables are renderable (roadmap F5's spike), and the reference PNG is the more truthful artefact anyway: it is what the build actually compares against. |
| D9 | Target extraction, coverage matching, reference-path derivation and variant parsing all live in Swing-free, AS-free code (`index/`, `model/`, `ui/` model classes) and are unit-tested without an IDE fixture. Only file lookup touches the VFS. | Matches the boundary every prior phase has kept. |
| D10 | A snapshot whose reference images are missing shows an explicit state naming the fix: *"No reference images — run `updateDebugScreenshotTest`."* A module whose `screenshotTest` files are not indexed degrades to today's behaviour (no badges, no snapshot rows), never to an error. | Degrade, don't break — the plugin's established posture. A missing reference is a normal state for a snapshot that has been written but not yet generated. |
| D11 | **Search matches previews only.** A query filters the preview rows as it does today; a surviving preview keeps its snapshot children, and the orphan branch is filtered by the snapshot names it holds. A snapshot name never pulls an otherwise-filtered-out preview back into the tree. | Snapshot names are derived from the composable the preview already carries, so matching both would return the same row twice under two different spellings. Keeps the search box's meaning unchanged. |

## Architecture

```
index/
  PreviewAnnotationMatcher   + matchPreviewTest(reference, imports): Boolean
  PreviewPsiScanner          + isSnapshotTest, + targets extraction (TargetExtractor)
  TargetExtractor            NEW · pure · trailing-lambda descent over KtNamedFunction
  PreviewValueExternalizer   + two fields · PreviewIndex.VERSION bump

model/
  IndexedPreview             + isSnapshotTest: Boolean, + targets: List<String>
  SnapshotCoverage           NEW · Covered(count) | Uncovered | NotApplicable
  ReferenceImage             NEW · variant name + VirtualFile

service/
  PreviewIndexService        + coverage join (module + target intersection)
  ScreenshotModuleDetector   NEW · does this module have src/screenshotTest?
  ReferenceImageLocator      NEW · path derivation + prefix glob + variant parsing

ui/
  PreviewNode                + SnapshotRow, + OrphanSnapshotBranch
  PreviewTreeModelBuilder    + attach snapshots to their previews, collect orphans
  PreviewTreeCellRenderer    + coverage badge text
  ReferenceStripView         NEW · side-by-side images, shared zoom/pan, variant labels
  PreviewRenderPanel         + REFERENCE and NO_REFERENCE states
```

## Data flow

1. **Index** — `PreviewPsiScanner` records, per function: `isSnapshotTest` (D1) and `targets` (D2),
   alongside today's fields. Nothing cross-file is read.
2. **Query** — `PreviewIndexService.findAll()` joins index values with module and file as it does
   today, then partitions them into previews and snapshots, and computes each preview's
   `SnapshotCoverage` by intersecting target sets within the module (D3). Modules with no
   `src/screenshotTest` directory yield `NotApplicable` for every row (D5).
3. **Tree** — `PreviewTreeModelBuilder` hangs each snapshot under the preview whose targets it
   intersects, and any snapshot that matched nothing under the module's orphan branch (D4).
   `PreviewTreeCellRenderer` appends the badge text for anything but `NotApplicable`.
4. **Selection of a snapshot row** — `ReferenceImageLocator` derives the directory, globs
   `<functionName>_*.png`, parses each variant name, and sorts the results by variant (D6).
   `PreviewRenderPanel` shows `ReferenceStripView` with the images side by side (D7), or the
   no-reference state when the glob is empty (D10).
5. **Selection of a preview row** — unchanged; the existing render pipeline runs as today.

## Error handling

| Situation | Behaviour |
|---|---|
| Module has no `src/screenshotTest` | No badge, no snapshot rows. Row renders as today. |
| Snapshot matches no preview | Appears under the module's "Snapshots without a preview" branch. |
| Snapshot matches several previews | Appears as a child of each. Coverage counts are per preview. |
| Reference directory missing or glob empty | `NO_REFERENCE` state naming `updateDebugScreenshotTest`. |
| A reference PNG fails to decode | That variant is skipped and reported in the strip; the others still show. |
| `targets` empty (nothing extractable) | The preview is `Uncovered` and the snapshot becomes an orphan — it is never silently matched to an arbitrary row. |
| Index does not see `screenshotTest` files | Everything degrades to `NotApplicable`; the plugin behaves exactly as it does today. |

## Testing

Pure JUnit 4, no fixture:

- `TargetExtractor` — wrapper chain (`PreviewComponent { PrimusTheme { X() } }` → `X`); argument-list
  calls excluded (`X(state = fakeState())` → `X`, and `X(state = FakeState())` → `X`); several calls in
  the innermost lambda → all of them; camelCase callees excluded; a body with no call → empty.
- Coverage matching — covered, uncovered, orphan, many-to-one, cross-package within a module,
  same-name targets in different modules must not match.
- Reference file-name parsing — `ErrorRetryRow_Default_Snapshot_phone_eee23ffd_0.png` → variant
  `phone`; a name that does not fit the pattern is ignored rather than mis-parsed.
- Reference path derivation from `packageName` + `jvmClassName` + module content root.
- Badge text for each `SnapshotCoverage` value, including the singular/plural boundary.

`BasePlatformTestCase`:

- Index round-trip: a `@PreviewTest` function is stored with `isSnapshotTest = true` and its targets,
  survives serialization, and does **not** appear as an ordinary preview row.
- Tree construction: snapshot attached under its preview; orphan under the module branch; a module
  without `src/screenshotTest` unchanged from today.
- `ReferenceImageLocator` against a fixture directory tree, including the empty-glob case.

The scanner tests use the real bodies from `features/favorites/ui` (reduced to their essentials) as
inputs, so the heuristic is measured against the code it was designed from.

## Risks

| Risk | Mitigation |
|---|---|
| **The `screenshotTest` source set may not be in the IDE's project model** when the consuming project is synced without `-Pandroid.experimental.enableScreenshotTest=true`, so its files may fall outside `projectScope` and never be indexed. | Verify first — it is the first task of the plan. `FileBasedIndex` indexes files under content roots regardless of source-root marking, so the expectation was that they would be indexed. **The gate against `hepsi-android` showed no badges, no snapshot children and no orphan branch, and this phase read that as the risk firing — wrongly.** Inspecting the module model afterwards found the holder module's content root *is* the module directory, so those files are in `projectScope`; the real defect was **attribution** under a module-per-source-set import. See [Phase 14](2026-07-31-snapshot-source-set-fallback-design.md)'s Evidence section, which locates `src/screenshotTest` from the module's content root by path convention and fixes attribution with it. Either way D10 keeps the failure silent. |
| `targets` extraction is a heuristic; a body shape nobody sampled yields a wrong target and therefore a wrong badge. | Tests are written from the 50 real snapshot bodies. A wrong badge is visible and low-stakes — it never changes code, and the snapshot row itself still shows the true reference images. |
| Coverage counts could be read as a quality metric and drive snapshot-writing for its own sake, including for composables the project's own guidance says not to snapshot (modal sheets). | The badge states a fact (`no snapshot`), not a verdict, and this phase deliberately ships no aggregate score. F2 will need to decide how it presents module totals. |
| The reference path layout is an implementation detail of the screenshot plugin and may change between AGP versions. | It is derived in one place (`ReferenceImageLocator`) and its failure mode is D10's no-reference state, not an error. |
| **The strip's fit and its preferred size can disagree about the chrome.** The gap between variants and the variant-label row are laid out at the display's scale and do **not** grow with the zoom, so a fit that divides by `sum(width) + gaps` / `maxHeight + labelHeight` returns a scale whose own preferred size still overflows the viewport by up to one label row and one set of gaps — pressing Fit grows scrollbars, the defect PG12 existed to remove. This shipped in PG13-11 and was fixed in PG13-13. | The chrome is subtracted from the viewport instead: `min((vw - gap*(n-1)) / sum(width), (vh - labelHeight) / maxHeight)`. The regression test asserts `preferredStripSize(fitScale(w, h))` is within `w x h` on both axes rather than merely `< 1.0`, which cannot tell the two formulas apart. **Roadmap F5's diff view must not re-derive its own fit** — any second side-by-side surface inherits this trap. |
| Two new index fields require a `PreviewIndex.VERSION` bump, forcing a full reindex on first run after upgrade. | Unavoidable and one-time; the index is already rebuilt on plugin updates. |
