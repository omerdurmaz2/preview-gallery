# Snapshot Testing — Feature Roadmap

> **Status:** **F1 and H1 shipped** — Phase 13 (badge, snapshot rows, reference strip), Phase 14 (read the
> source set from the VFS, fix attribution) and Phase 15 (refresh before the lookup, discover every build
> variant's reference root, recover the index-fallback rows). The manual gate against `hepsi-android`
> passes: snapshots are listed, they hang under the previews they belong to, and a reference directory
> changed from a terminal is picked up without pressing Refresh. Everything else below is still backlog.
>
> Each remaining entry becomes its own session: brainstorm → design spec in `docs/superpowers/specs/`
> → plan in `docs/superpowers/plans/` → implementation. Commit prefixes (`PG16-N`, …) are assigned when
> a feature is planned, continuing from the last used phase (`PG15`).

## Priority order

Ranked for what to build next, not by theme. Effort is a rough order of magnitude for one session.

| # | Item | Why it is here | Effort |
|---|---|---|---|
| 1 | **F2 · Coverage filter and report** | The badge provokes exactly one question — "so what is uncovered?" — and today the only way to answer it is to scroll. Smallest change with a daily payoff, and it needs no new data. | S |
| 2 | **F8 · MCP server over the index** | The consuming project already has a `snapshot-testing` skill that tells an agent *how* to write a snapshot. What the agent cannot get is *which* composables lack one. This closes that loop — and it may make F3 unnecessary, see below. | M |
| 3 | **F3 · "Create snapshot test" action** | The action the filter's answer demands. Worth building **only if** F8 plus the existing skill turns out not to cover it — an agent with project context writes a better fake `UiState` than a PSI template can. | L |
| 4 | **Spike, then F5 · Reference vs. live diff** | Highest ceiling of anything here, still gated on an unanswered AS-internal question. Run the spike early, decide after. | L |
| 5 | **F7 · Degenerate golden detector** | Small, and it protects the value of every snapshot F3/F8 produces. | S |
| 6 | **F6 · Gradle task runner** | Depends on F5's diff view to be worth the wiring. | L |
| 7 | **F4 · Promote a comparison view to a variant** | Depends on F3's writer. Nice, not load-bearing. | M |

**The F3-versus-F8 tension is worth deciding deliberately.** F3 generates snapshot files from inside the
plugin with a PSI writer; F8 hands an agent the coverage data and lets it write them with the project's
own skill in hand. The agent route reads the real preview body, knows which fake-state factories exist,
and can tell an `internal` composable from a `private` one by resolving it — all things a template
cannot do. F3's advantage is that it works with no agent and no network. Build F8 first, use it on a
real module, and only then decide whether F3 still earns its cost.

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

### Theme 0 — Hardening what shipped

#### H1 · Close the gaps the gate did not exercise — **shipped (PG15)**

**Goal:** stop the shipped feature from silently showing the wrong thing on paths nobody walked yet.

Three defects, all closed by Phase 15
(`docs/superpowers/specs/2026-07-31-snapshot-reference-hardening-design.md`):

- **No VFS refresh before reading the reference directory** — a PNG that `updateDebugScreenshotTest` just
  wrote from a terminal was not in the VFS yet, so the panel told the user to run the command they had
  just run. `ReferenceRoots.refresh` now runs before every lookup, in two passes (shallow over `src` for a
  new variant directory, recursive over each source set for a new file in an already-listed one), and
  **outside** any read action — the platform silently skips a synchronous refresh held under a read lock.
  This forced the lookup apart into three steps: resolve the module directory, refresh, then list.
- **Only one build variant was understood** — the root was the constant `src/screenshotTestDebug`. Roots
  are now discovered on disk, every one of them contributes to the strip, a label carries its source set
  only when more than one contributed, and the no-reference message names the matched variants' own
  `update<Variant>ScreenshotTest` (or names none rather than naming a task the module lacks).
- **Index-fallback rows had no reference images** — `ModuleDirectoryResolver` keeps Phase 14's path
  derivation as the primary answer and adds `getModuleForFile` behind it, for exactly the rows whose path
  yields nothing.

**Verified by the manual gate:** the refresh and the message. **Not reproducible in `hepsi-android` and
therefore covered by unit tests only:** the flavoured-module path (the pilot module is a library with no
flavours) and the index-fallback path (no module there has a layout the probe fails on).

- **Hooks:** `service/ReferenceRoots.kt`, `service/ModuleDirectoryResolver.kt`,
  `service/ReferenceImageLocator.kt`, `ui/PreviewGalleryPanel.kt`, `ui/PreviewRenderPanel.kt`
- **Effort:** XS (actual: six tasks, 352 → 384 tests) · **Risk:** low
- **Depends on:** nothing

### Theme 1 — Visibility

#### F1 · Snapshot coverage badge in the tree — **shipped (PG13, PG14)**

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

#### F2 · Coverage filter and report

**Goal:** turn F1's per-row facts into something you can act on without scrolling.

A filter with three states rather than a plain toggle — **all** / **uncovered only** / **covered only**.
Both directions earn their place: *uncovered* is the work queue, *covered* is what you scan when you
change a shared component and want to know which snapshots you are about to invalidate. Modules with no
`src/screenshotTest` stay unbadged and are hidden by either filter, since the question does not apply
to them.

Plus an exportable report — `X/Y covered` per module, markdown — so the number can go in a ticket or a
channel before the consuming project's CI job exists.

- **Hooks:** `PreviewSearchFilter`, `ModuleFilterToggleAction`, `PreviewGalleryPanel`, `SnapshotCoverage`
- **Effort:** S · **Risk:** low
- **Depends on:** F1 (shipped)
- **Open:** does the filter compose with the search box and the module filter, or replace them? What
  happens to the orphan branch under "covered only" — an orphan is a snapshot, not a preview.

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

### Theme 4 — Agents

#### F8 · Serve the preview and snapshot index over MCP

**Goal:** let an agent ask the plugin what the project contains, instead of re-deriving it by grepping.

The gallery already holds, per composable: which module and package it lives in, whether it has a
snapshot, which snapshot functions cover it, which reference PNGs are committed for each, and which
snapshots match no preview. That is precisely the data an agent needs and cannot cheaply reconstruct —
matching a preview to its snapshot took a call-site heuristic and two phases to get right.

Expose it read-only over MCP. Candidate tools:

| Tool | Returns |
|---|---|
| `list_previews(module?, package?)` | Composable FQN, module, file, `isPrivate`, `hasPreviewParameter`, coverage |
| `list_snapshots(module?)` | Snapshot function FQN, the composable it shows, its variants |
| `coverage(module?)` | Covered / uncovered counts plus the uncovered composables themselves |
| `reference_images(snapshotFqn)` | Variant name and absolute path per committed PNG |

**Why this may replace F3.** The consuming project already carries a `snapshot-testing` skill that tells
an agent exactly how to write a snapshot — the wrapper, the naming, the five rules that came from real
render failures. What the agent cannot get today is *which* composables lack one. Give it that, and the
agent writes the file with the whole project in context: it can read the existing `@Preview` body,
resolve whether the target is `internal` or `private`, find the fake-state factory that already exists,
and skip the modal sheets the skill says not to snapshot. A PSI template inside the plugin can do none
of that.

Precedent worth copying: the `DepHealth` plugin in the same toolchain already serves MCP from an IDE
plugin over `http://localhost:7890/mcp`, so the transport question has a known-good answer here.

- **Hooks:** `service/PreviewIndexService.kt` (the whole payload already exists), a new server surface
- **Effort:** M · **Risk:** medium — a new network surface in an IDE plugin, and the first part of this
  plugin that is not purely local UI
- **Depends on:** F1 (shipped)
- **Open:** transport — HTTP like DepHealth, or stdio? Fixed port, configurable, or discovered? Bind to
  localhost only and stay strictly read-only — the plugin must never become a way to write files from
  outside the IDE. Does it serve rendered PNG bytes, or only paths? What happens while the index is
  still building — error, or empty with a "still indexing" flag?

---

## Scope guard for F8

Serving data over a socket is a different posture from a tool window. Two rules, decided here so a
later design does not have to relitigate them: **read-only**, and **localhost only**. The gallery
describes what the repository already contains; it does not accept instructions to change it. Anything
that writes — generating a snapshot file, running a Gradle task — stays behind the IDE's own UI where a
human is present, and an agent that wants those does the writing itself with its own tools.

## Scope guard for the whole roadmap

The plugin spec's non-goal **N6** says Preview Gallery is *not* a snapshot/regression testing tool.
These features do not change that: the plugin does not render goldens in CI, does not own the
reference images, and does not replace the Gradle tasks. It makes an existing testing workflow
visible and cheap from inside the IDE. If a feature starts to look like a second screenshot-testing
engine, it belongs in the consuming project's build, not here.
