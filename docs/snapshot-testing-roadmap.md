# Snapshot Testing — Feature Roadmap

> **Status:** backlog only. Nothing here is designed or planned yet. Each entry becomes its own
> session: brainstorm → design spec in `docs/superpowers/specs/` → plan in `docs/superpowers/plans/`
> → implementation. Commit prefixes (`PG13-N`, `PG14-N`, …) are assigned when a feature is planned,
> continuing from the last used phase (`PG12`).

## Why

The consuming project (`hepsi-android`) adopted Android's official Compose Preview Screenshot Testing
plugin. Its team rule is *"a composable change means a snapshot change"* — but nothing in the IDE
shows which composables have snapshots, generating a snapshot is a manual copy-paste ritual, and
verifying one costs a full Gradle task run. Preview Gallery already indexes every `@Preview` in the
project and renders it through layoutlib, which is exactly the infrastructure those three gaps need.

## Context — how snapshot testing works in the consuming project

Facts the features below depend on. Source: `hepsi-android/.ai/skills/snapshot-testing/SKILL.md`
(last updated 2026-07-23).

| Item | Value |
|---|---|
| Engine | `com.android.compose.screenshot` (Android official, layoutlib) — not Paparazzi/Roborazzi |
| Wiring | Applied once in the `hb.android.compose.library` convention plugin; no per-module build script change |
| Gate | `-Pandroid.experimental.enableScreenshotTest=true`; without it the plugin is not applied at all |
| Snapshot source set | `<module>/src/screenshotTest/kotlin/<package of the composable>/XxxSnapshots.kt` |
| Reference images | `<module>/src/screenshotTestDebug/reference/...`, committed to git |
| Annotations | `@PreviewTest` + `@Preview` (or the project's `@SnapshotPreviews` multipreview: `phone` + `small` at `widthDp = 320`) |
| Required wrapper | `PreviewComponent { PrimusTheme { … } }` — a Primus component without `PrimusTheme` crashes with `IllegalStateException: No Component provided` |
| Naming | `<ComposableName>_<State>_Snapshot`, one function per distinct visual state |
| Tasks | `:<module>:updateDebugScreenshotTest` (regenerate) · `:<module>:validateDebugScreenshotTest` (verify) |
| Report | `<module>/build/reports/screenshotTest/preview/debug/index.html`, with per-snapshot diff images |
| Pilot module | `features/favorites/ui` — 50 snapshot functions, 100 reference images |
| CI | No job yet (planned by the consuming team); snapshots run locally today |

Authoring constraints that the generator (F3) must respect:

1. Always wrap in `PreviewComponent { PrimusTheme { … } }`.
2. Build fake state inline — no ViewModel, no Hilt, no repositories. Snapshot the stateless inner
   composable when the target takes `viewModel: X = hiltViewModel()`.
3. `screenshotTest` can call `internal` and public composables from `main`, but **not** `private` ones.
4. Modal bottom sheets render blank in static screenshots — a blank golden is worse than no test.
5. Reuse the existing `@Preview` body from `main`; add the `PrimusTheme` wrapper.
6. Font scale and dark theme are deliberately excluded — the app pins `fontScale = 1.0f` and is
   light-only, so those variants would test unreachable states.

## Existing plugin infrastructure these features build on

| Component | File | Reused for |
|---|---|---|
| Preview index | `index/PreviewIndex.kt`, `index/PreviewPsiScanner.kt` | Indexing `screenshotTest` previews (F1) — `PreviewPsiScanner` only; Phase 14 stopped relying on `PreviewIndex` for this source set, see `service/SnapshotSourceScanner.kt` |
| Index model | `model/IndexedPreview.kt` | Already carries `isPrivate`, `hasPreviewParameter`, `annotationKind`, `unsupportedReason` |
| Search / filter | `search/PreviewSearchFilter.kt`, `search/PreviewModuleFilter.kt` | "Uncovered only" filter (F2) |
| Tree | `ui/PreviewTreeModelBuilder.kt`, `ui/PreviewTreeCellRenderer.kt`, `ui/PackageTreeBuilder.kt` | Coverage badges (F1), failure badges (F6) |
| Live render | `render/LiveRenderer.kt`, `render/RenderModelResolver.kt`, `render/RenderPipeline.kt` | Gradle-free live render for diffing (F5) |
| Comparison tabs | `ui/ComparisonViewList.kt`, `ui/ZoomableRenderView.kt`, `model/ViewOverride.kt` | Reference / diff tabs (F5), variant promotion (F4) |
| Image export | `ui/RenderImageExporter.kt` | Golden export / comparison (F5) |
| Build on demand | `render/BuildService.kt`, `render/ModuleFreshness.kt` | Running the Gradle screenshot tasks (F6) |
| Render inspection | `render/RenderedImageInspector.kt` | Degenerate-golden detection (F7) |
| API safety net | `render/RenderApiProbe.kt` | Same degrade-don't-break posture for any new AS-internal capability |

---

## Backlog

Effort is a rough order of magnitude for a single feature session, not a commitment.

### Theme 1 — Visibility

#### F1 · Snapshot coverage badge in the tree

**Goal:** show, per preview in the gallery tree, whether a snapshot exists for it.

Index `@PreviewTest` functions in `src/screenshotTest` alongside the `main` `@Preview` functions, then
badge each tree row (covered / not covered / snapshot-only). Matching a snapshot to its composable is
the core problem: the snapshot function is `<ComposableName>_<State>_Snapshot` in the mirrored package
and calls the composable in its body — name convention and call-site resolution are two candidate
strategies, and the design must pick one and state what it does with mismatches.

- **Hooks:** `PreviewPsiScanner`, `PreviewIndex`, `IndexedPreview`, `PreviewTreeCellRenderer`
- **Effort:** M · **Risk:** low (pure PSI/index work, no AS-internal API)
- **Depends on:** nothing
- **Open:** Does the IDE model expose `src/screenshotTest` when the Gradle gate flag is off? **Not
  answered, and it turned out not to be the question.** Phase 13's manual gate against `hepsi-android`
  showed no badges, child rows or orphan branch, which that phase read as "the index sees nothing";
  inspecting the cached module model afterwards showed the holder module's content root *is*
  `features/favorites/ui`, so those files are in `projectScope` after all. The defect was
  **attribution** — a module-per-source-set import files the rows under a module that owns no previews.
  Phase 14 (`docs/superpowers/specs/2026-07-31-snapshot-source-set-fallback-design.md`) reads the source
  set from the VFS, which fixes attribution and makes the modelling question moot either way. Which
  matching strategy (naming convention vs. resolving the called composable) survives real code?

#### F2 · "Uncovered previews" filter and coverage report

**Goal:** turn F1's per-row facts into a module-level metric the team can act on.

A toggle that filters the tree to previews with no snapshot, plus an exportable report
(`X/Y covered` per module, markdown). Useful before the consuming project's CI job exists.

- **Hooks:** `PreviewSearchFilter`, `ModuleFilterToggleAction`, `PreviewGalleryPanel`
- **Effort:** S · **Risk:** low
- **Depends on:** F1

### Theme 2 — Authoring

#### F3 · "Create snapshot test" action

**Goal:** generate the snapshot file that the skill currently asks a developer to write by hand.

Right-click a preview in the tree → write
`src/screenshotTest/kotlin/<package>/<ComposableName>Snapshots.kt` containing a `@PreviewTest` +
`@SnapshotPreviews` function whose body is ported from the existing `main` `@Preview` and wrapped in
`PreviewComponent { PrimusTheme { … } }`. Refuse (with an explanation) on `private` composables and
on `@PreviewParameter` — the index already flags both. Append to the file when it exists rather than
overwriting.

The wrapper composables (`PreviewComponent`, `PrimusTheme`) are project-specific, so the generated
template must be configurable rather than hardcoded to `hepsi-android`.

- **Hooks:** `IndexedPreview` (`isPrivate`, `hasPreviewParameter`), `render/PreviewAnnotationLocator.kt`,
  a new PSI writer
- **Effort:** L · **Risk:** low-medium (PSI generation, no AS-internal API)
- **Depends on:** nothing (F1 makes it discoverable, not required)
- **Open:** how is the template configured — settings page, project-level config file, or convention
  detection? Does the ported body compile when it references `private` preview-data factories (skill
  rule 3 says inline a local copy)?

#### F4 · Promote a comparison view to a snapshot variant

**Goal:** turn an ad-hoc comparison view into a committed test variant.

The comparison-view tabs already hold a full `@Preview` property override in memory. "Promote" writes
that configuration into the snapshot as a new variant — extending the project's `@SnapshotPreviews`
multipreview or generating a new one. Deliberately excludes the axes the consuming project rules out
(font scale, dark theme).

- **Hooks:** `ComparisonViewList`, `ViewOverride`, F3's PSI writer
- **Effort:** M · **Risk:** low
- **Depends on:** F3

### Theme 3 — Verification

#### F5 · Reference vs. live diff, without Gradle

**Goal:** see whether a composable still matches its committed golden, in seconds instead of a task run.

Load the committed PNG from `src/screenshotTestDebug/reference/` as a **Reference** tab beside the live
render, and add a **Diff** tab (pixel difference overlay). The tab strip, zoom/pan view and image export
already exist; what is missing is reference-file resolution (reference filenames carry a hash), synced
zoom/pan across tabs, and the diff computation itself.

Resolving *which* PNG belongs to *which* preview is the real work: the plugin renders the `main`
`@Preview`, while the golden was produced from the `screenshotTest` function with its
`PreviewComponent`/`PrimusTheme` wrapper — so a live-vs-golden diff is only apples-to-apples if the
plugin renders the `screenshotTest` function itself.

- **Hooks:** `PreviewRenderPanel`, `ComparisonViewList`, `ZoomableRenderView`, `RenderImageExporter`
- **Effort:** L · **Risk:** **high** — see the spike below
- **Depends on:** F1 (for the preview → snapshot mapping)
- **Open:** reference filename → preview mapping (`..._phone_<hash>_0.png`); tolerance for
  antialiasing noise; what the diff shows when the two images differ in size.

> **Spike required before F5/F6.** The `screenshotTest` source set only enters the AGP model when
> `-Pandroid.experimental.enableScreenshotTest=true` is set, and the reference project is synced without
> it. Phase 14 (`docs/superpowers/specs/2026-07-31-snapshot-source-set-fallback-design.md`) sidesteps the
> question by reading `src/screenshotTest` from the VFS rather than from the project model — note that its
> own evidence section retracts the stronger claim that the index could not see those files. That covers
> reading and parsing
> the source; whether the plugin can resolve and render a `@PreviewTest` composable from that source set
> through `RenderModelResolver` is **still unverified** — rendering through layoutlib is a different,
> AS-internal question Phase 14 deliberately left untouched. Loading and displaying the reference PNG does
> not depend on this; rendering the `screenshotTest` function live does. Run a Phase-0-style spike (as in
> the plugin spec §10) before committing to a design.

#### F6 · Run the Gradle tasks and badge failures

**Goal:** drive `update` / `validate` from the gallery and surface results where the previews live.

Run `:<module>:validateDebugScreenshotTest -Pandroid.experimental.enableScreenshotTest=true` for the
selected module, parse `build/reports/screenshotTest/preview/debug/`, badge failing previews red in the
tree, and open F5's diff view on click. `update` gets the same treatment for regenerating references.

- **Hooks:** `BuildService`, `ModuleFreshness`, `PreviewTreeCellRenderer`, F5's diff view
- **Effort:** L · **Risk:** medium (report format is not a stable API — parse the diff PNGs on disk
  rather than scraping the HTML if possible)
- **Depends on:** F1, F5
- **Open:** the gate flag is project-specific — configurable, or detected? Never spawn a second Gradle
  daemon (spec goal G5).

#### F7 · Degenerate golden detector

**Goal:** catch snapshots that test nothing.

Skill rule 4: modal bottom sheets render blank because the sheet starts collapsed, and a blank golden
is worse than no test. `RenderedImageInspector` already inspects rendered images — run it over live
renders and over committed reference PNGs, and warn on blank or single-colour results.

- **Hooks:** `RenderedImageInspector`, `PreviewRenderPanel`, tree badges
- **Effort:** S · **Risk:** low
- **Depends on:** F1 (to find the reference files)

---

## Suggested order

1. **F1** — everything else keys off the preview → snapshot mapping, and it carries no AS-internal risk.
2. **F3** — automates the manual authoring ritual; independent of F1's outcome.
3. **F2**, **F7** — small additions once F1 lands.
4. **Spike**, then **F5** — the highest-value feature, gated on whether `screenshotTest` composables can
   be rendered live.
5. **F6**, **F4** — build on F5 and F3 respectively.

## Scope guard

The plugin spec's non-goal **N6** says Preview Gallery is *not* a snapshot/regression testing tool.
These features do not change that: the plugin does not render goldens in CI, does not own the
reference images, and does not replace the Gradle tasks. It makes an existing testing workflow
visible and cheap from inside the IDE. If a feature starts to look like a second screenshot-testing
engine, it belongs in the consuming project's build, not here.
