# Snapshot Testing — Feature Roadmap

> **Status:** **F1, H1, F2, F8, F7, F6 and F5's reference half shipped** — Phase 13 (badge, snapshot rows,
> reference strip), Phase 14 (read the source set from the VFS, fix attribution), Phase 15 (refresh before the
> lookup, discover every build variant's reference root, recover the index-fallback rows), Phase 16 (the
> uncovered-only toggle and the markdown report), Phase 17 (the index served read-only over MCP), Phase 18
> (snapshot health, in the export and over MCP), Phase 19 (a preview row shows its committed goldens) and
> Phase 20 (a snapshot row runs the project's own `validate` task and shows what it found). The manual gate
> against `hepsi-android` passes: snapshots are listed, they hang under the previews they belong to, a reference
> directory changed from a terminal is picked up without pressing Refresh, the toggle leaves the work queue on
> screen, an agent asking `list_previews` gets the same 854 uncovered composables the tree shows, the health
> section names the `favorites` specimen it was designed around, a covered preview shows every covering
> snapshot's goldens while the mode stays on across rows, and a deliberately corrupted golden comes back as
> `differs` with its reference, rendered and diff images and the engine's own `0.111% different`. Everything
> else below is still backlog.
>
> Each remaining entry becomes its own session: brainstorm → design spec in `docs/superpowers/specs/`
> → plan in `docs/superpowers/plans/` → implementation. Commit prefixes (`PG21-N`, …) are assigned when
> a feature is planned, continuing from the last used phase (`PG20`).

## Priority order

Ranked for what to build next, not by theme. Effort is a rough order of magnitude for one session.

| # | Item | Why it is here | Effort |
|---|---|---|---|
| 1 | **F5's diff half** | The reference half shipped (PG19) and the classloader spike answered its blocker: the seam is the plugin's and the whole chain composes out of public API. What it buys over F6 is speed — seconds instead of a Gradle run — at the cost of an unproven pixel-comparability question, so its own first phase is a calibration, not a UI. | M |
| 2 | **H2 · Close what PG20's gate left open** | Two verify checks were never run after the last model fix, two silent paths are known and unfixed, and a source-tree walk still runs unbounded on the EDT behind a 5 s cache. Small, and it is the price of having shipped F6 through a gate that found four real bugs. | S |

**Deferred:** F3 (the agent route supersedes it until proven otherwise) and F4 (waits on F3's writer). Both
keep their sections below with the reasoning.

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

#### F2 · Coverage filter and report — **shipped (PG16)**

**Goal:** turn F1's per-row facts into something you can act on without scrolling.

Shipped as a **two-state toggle**, not the three-state control this entry sketched: only the *uncovered*
direction was built. *Covered only* — "which snapshots am I about to invalidate?" — was cut for want of a
second control in a toolbar that already carries a module filter, and because nobody had asked for it. It
stays available as a follow-up: the filter is one `filter` call, and adding the direction is a dropdown, not
an architecture.

Two decisions from the design spec reversed what this entry assumed, both after the manual gate:

- Modules with no `src/screenshotTest` are **not** exempt. The `NotApplicable` state was deleted outright —
  a preview with no matching snapshot is uncovered wherever it lives. Hiding those modules made the toggle
  surface one module out of 1371 in `hepsi-android`, which read as the filter being broken rather than as
  the project having no coverage.
- The report counts every module holding a preview, so it reads near 0% on that project. That number is the
  finding, not noise.

The report writes `X/Y covered` per module as markdown to a file chosen in a save dialog, and always
describes the whole project — never the filtered tree.

- **Built:** `search/PreviewCoverageFilter.kt`, `service/CoverageReport.kt`, `ui/PersistentToggleAction.kt`
  (extracted from `ModuleFilterToggleAction`), `ui/CoverageFilterToggleAction.kt`, `ui/CoverageReportAction.kt`
- **Spec:** `docs/superpowers/specs/2026-08-06-snapshot-coverage-filter-design.md`
- **Answers to the open questions:** the filter composes with the search box and the module filter rather than
  replacing either (spec D4), and the orphan branch stays visible while it is on (spec D3) — an orphan is the
  mirror of what the filter selects for, so hiding it would tell half the truth.
- **Left for a follow-up:** the *covered only* direction.

### Theme 2 — Authoring

#### F3 · "Create snapshot test" action — **deferred (2026-08-07)**

**Deferred in favour of the agent route, not cancelled.** F8 shipped, so the thing this action would
generate can now be written by an agent that holds the index: it reads the real `@Preview` body, resolves
whether the target is `internal` or `private`, finds the fake-state factory that already exists in the
module, and follows the consuming project's own `snapshot-testing` skill. A PSI template inside the plugin
does none of that, and the template would have to be made configurable per project on top.

Revisit this only if writing snapshots that way turns out to be worse in practice. What would bring it
back: the agent route needing the same manual correction every time, in a way a template could have got
right. Until someone has written a batch of snapshots against the live MCP server and hit that, building a
PSI writer is guessing.

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

#### F4 · Promote a comparison view to a snapshot variant — **blocked by F3's deferral**

It needs F3's PSI writer, which is deferred above, so this one waits with it rather than on its own merits.

**Goal:** turn an ad-hoc comparison view into a committed test variant.

The comparison-view tabs already hold a full `@Preview` property override in memory. "Promote" writes
that configuration into the snapshot as a new variant — extending the project's `@SnapshotPreviews`
multipreview or generating a new one. Deliberately excludes the axes the consuming project rules out
(font scale, dark theme).

- **Hooks:** `ComparisonViewList`, `ViewOverride`, F3's PSI writer
- **Effort:** M · **Risk:** low
- **Depends on:** F3

### Theme 3 — Verification

#### F5 · Reference vs. live diff, without Gradle — **reference half shipped (PG19), diff half open**

**Goal:** see whether a composable still matches its committed golden, in seconds instead of a task run.

**Shipped (PG19):** a preview row shows the committed goldens of every snapshot covering it, through a sticky
mode toggle in the render pane. Not a tab beside the live render, as this entry sketched — a **mode**, reusing
`RenderState.REFERENCE` and the strip the snapshot row already used, because a tab in `viewTabs` would have made
the comparison-view machinery (per-tab overrides, close buttons, render generations) learn a "this tab is not a
render" case. The lookup came out of `PreviewGalleryPanel` into `ReferenceStripLoader` on the way.

**Deliberately not built:** the diff, and with it the synced zoom this entry lists. Not for want of effort — the
two images are not comparable. The plugin renders the `main` `@Preview`; the golden came from the
`screenshotTest` function with its `PreviewComponent`/`PrimusTheme` wrapper, so they are *expected* to differ,
and a UI inviting the comparison would present that difference as a regression.

**What is left, and its one gate.** Making them comparable means rendering the `screenshotTest` function itself.
The [spike](superpowers/specs/2026-08-10-screenshottest-render-spike.md) answered how close that is: module
attribution, facet, build target, configuration and layoutlib inflation all succeed **without** the experimental
flag — the composition then cannot load the snapshot class, because AS's `ClassFileFinder` calls the holder
module ambiguous and falls back to main. The class file is on disk, already compiled. So the remaining question
is narrow: **can a plugin inject the `debugScreenshotTest` classes directory into `StudioModuleRenderContext`'s
classloader?** Answer that before designing the diff.

- **Hooks:** `PreviewRenderPanel`, `ComparisonViewList`, `ZoomableRenderView`, `RenderImageExporter`
- **Effort:** M (diff half) · **Risk:** medium — the AS-internal unknown is now one named question, not a field
- **Depends on:** F1 (for the preview → snapshot mapping), and the classloader spike
- **Open:** whether the classes directory can be injected; tolerance for antialiasing noise; what the diff shows
  when the two images differ in size.
- **Known rough edge (accepted at the PG19 gate):** switching the mode *on* briefly shows "Nothing selected" with
  an empty toolbar, because `pipeline.select(null)` publishes `IDLE` synchronously. Toggling off is smooth, so it
  reads as asymmetric while arrow-scanning. Suppressing that publish is not a one-liner: `showReferenceStrip`
  currently relies on it having cleared the comparison tabs, so it would first have to detach `renderScroll` from
  `viewTabs` itself.

> **The original spike is answered — see [2026-08-10-screenshottest-render-spike.md](superpowers/specs/2026-08-10-screenshottest-render-spike.md).**
> It asked whether the plugin can resolve and render a `@PreviewTest` composable from `src/screenshotTest`
> through `RenderModelResolver` in a project synced without `-Pandroid.experimental.enableScreenshotTest=true`.
> The answer is *almost*: everything up to the class load succeeds, and the flag turned out not to be the
> obstacle — module attribution worked without it. What blocks the render is `ClassFileFinder` falling back to
> the main module, so the compiled snapshot class (which is on disk) is not on the render classpath. The
> follow-up question named in F5 above replaces this one; do not re-run this spike.

#### F6 · Run the Gradle tasks and badge failures — **shipped (PG20)**

**Goal:** drive `validate` from the gallery and surface results where the previews live.

**The dependency this entry asserted was wrong.** It said F6 "depends on F5's diff view to be worth the wiring".
It does not: the task already writes the rendered images, the diff images, and a machine-readable JUnit result
carrying the function name, the variant and both image paths per snapshot — everything a diff view would have had
to compute. Removing that false dependency is what let F6 ship first, and it turned out to be the better order,
because F6's answer is exact by construction while F5's still has to prove it is comparable at all.

**Shipped narrower than sketched:** `validate` only, never `update` — the plugin does not write reference images,
and regenerating a golden stays the human's deliberate act at a terminal. Results are read from the JUnit XML
rather than the HTML report, as this entry hoped. Selecting a snapshot row runs the module's task behind the
same debounce the render and reference paths use; a toolbar action forces one and supersedes whatever is in
flight.

**The gate is where this feature was actually built.** Four bugs survived every review and only a real run found
them, which is worth recording for the next feature that touches Gradle output:

1. The XML's image paths are **relative to the build root**, and the code read them with `File(path)`. Every
   decode failed. Invisible to tests, whose paths were absolute by construction.
2. `PsiModificationTracker` is a "something that could affect PSI happened" counter, not an edit counter —
   measured: writing one file under `build/outputs` moved it by 5, a real edit by 3. A verify's own Gradle run
   outran the stamp taken before it launched, so every run was born stale.
3. `PreviewScreenshot.diffImagePath` **exists only on a pixel-difference failure**. A size mismatch produces no
   diff image, omits the property, and names the path only inside the `<failure>` message. The reader was written
   against the property alone, having only ever seen a passing `update` run.
4. The store conflated **the last measurement** with **the last attempt**, so an attempt that measured nothing —
   a cancellation, an UP-TO-DATE second run, an indexing refusal — erased a good verdict. Three separate fixes
   only chose which fact to lose before the model was split.

- **Hooks:** `BuildService`'s external-system shape (mirrored, not shared — see the debt below), `ModuleFreshness`,
  `PreviewTreeCellRenderer`, `ReferenceStripView`
- **Effort:** M as built · **Risk:** realised — see the four above
- **Debt:** `SnapshotVerifyRunner` duplicates ~120 lines of `BuildService`'s single-flight, generation-guard and
  listener-lifetime machinery. It was left duplicated deliberately, but the copies have since diverged and the
  same task-id ownership bug had to be fixed in both. A shared `internal ExternalSystemRunner` would make "at most
  one of our external-system tasks in flight" a plugin-wide invariant rather than a per-class one.
- **Open:** `validate` fails the build when a snapshot differs, so a failed Gradle run is the normal failing case
  — anything reading its exit status must not treat that as an error.

#### H2 · Close what PG20's gate left open

**Goal:** finish the verification F6's gate started, and pay down what it deliberately deferred.

Two gate checks were never run after the final model fix: an **UP-TO-DATE second verify** must keep the badge and
report that the last attempt measured nothing, and **pressing Verify during indexing** must produce a visible
message. Both are now expected to pass; neither is observed.

Two silent paths are known and unfixed. `runVerify` starts `verifyTarget() ?: return`, so a Verify on a module
with snapshot tests but no committed goldens records nothing and says nothing — fixing it naively replaces the
useful "run `updateDebugScreenshotTest`" pane with a bare "Not verified", so it needs a wording decision rather
than a guard. And `startVerify` cancels the pending alarm before the `needsVerify` check, so a selection change
inside the debounce silently drops an explicit Verify.

One performance ceiling: `ModuleFreshness.newestModuleSourceMtime` walks a module's source tree **unbounded** and
runs from Swing's per-row paint callback, behind a 5 s TTL cache. `isModuleFresh` caps its own walk at
`MAX_SCAN_DEPTH`; this one does not. Amortised, not removed.

- **Effort:** S · **Risk:** low
- **Depends on:** nothing

#### F7 · Degenerate golden detector — **shipped (PG18)**

**Goal:** catch snapshots that test nothing.

Two checks rather than the one this entry sketched, and the one it did not sketch is the one that found
everything. The blank-golden half is what the entry describes: `RenderedImageInspector.isBlank` run over the
committed reference PNGs. Design added a second, the **name rule** — a row whose name claims a component its
body never calls — because the specimen that motivated this feature renders a full, convincing PNG and no
pixel check can see it.

Narrower than sketched in three ways, all deliberate (spec Non-Goals): reference PNGs only, not live renders;
no tree badge and no new toolbar button; and the findings ride the existing coverage export as a `## Health`
section rather than a second document, because "how healthy are this project's snapshots" is one question and
two files each holding half the answer is how a number gets quoted without its caveat. Agents get the same
findings through a `snapshot_health` MCP tool.

**The calibration run is the entry's real result.** Against `hepsi-android`: **zero blank goldens** — a real
answer, not a broken check — and **12 name findings, 9 real and 3 noise**. The nine include the specimen
(`DeleteSelectedProductsDialog_Preview`, named after the dialog, showing `PrimusDialog`) *and its snapshot*,
which copies the same mistake — exactly the failure mode the feature was designed around. The three false
positives shared one shape: `@Preview` composables that serve as their own preview, carrying no
`Preview`/`Snapshot` suffix, so the rule accused them of not calling themselves. PG18-10 tightened `stems()` to
drop the full-length stem when no suffix was stripped, which removes that class without touching any of the
nine. The check now reports 9 findings on this project.

Worth knowing for whoever picks up F5: the half this entry is *named* after found nothing, and the half added
during design found all of it. A blank golden is a real failure mode; it is just not this project's.

- **Hooks:** `RenderedImageInspector` (called, never modified), `CoverageReportAction`, `ToolRegistry`
- **Effort:** S · **Risk:** low
- **Depends on:** F1 (to find the reference files)

### Theme 4 — Agents

#### F8 · Serve the preview and snapshot index over MCP — **shipped (PG17)**

**Goal:** let an agent ask the plugin what the project contains, instead of re-deriving it by grepping.

Four tools rather than the five sketched here: `list_projects`, `list_previews`, `list_snapshots` and
`coverage_report`. `reference_images` was folded into `list_snapshots` — a row carrying its own PNG paths
is one call instead of N — and `coverage` became `coverage_report`, which returns the byte-identical
markdown the toolbar's export writes, so a number an agent quotes and a number pasted from the IDE cannot
disagree.

Every open question this entry left is answered in the spec:

- **Transport** — HTTP, but on the JDK's own `com.sun.net.httpserver` rather than `DepHealth`'s Ktor. Two
  endpoints do not earn four artifacts and a second Netty class-loader tree inside the IDE.
- **Port** — fixed at 7891, one application-level server, with an optional `project` argument on every tool
  and `list_projects` to discover the names. This workflow runs two IDEs at once, and a project-level server
  would make the second fight for the port.
- **Bytes or paths** — paths. Every client here reads files already; base64 in a JSON-RPC response spends a
  context window doing it worse.
- **While indexing** — `list_projects` says `indexing: true` and every other tool refuses, as an `isError`
  result rather than a protocol error, so the message reaches the model instead of being rejected by the
  client. An agent handed `[]` concludes the project has no previews and acts on it.
- **Read-only** — held. No tool creates, edits or runs anything, and the scope guard below still governs.

Two things the reviews caught that no plan would have: reference PNGs live nested under
`reference/<package>/<FacadeKt>/`, so the obvious flat scan returns nothing on every real project
(`ReferenceImageLocator` already solved it); and `HttpURLConnection` silently drops the `Origin` header, so
the browser-guard test could never have passed as first written.

Against `hepsi-android` the server reports 880 previews, 50 snapshots, **24 orphans** and 854 uncovered
across 92 modules. That orphan count is a finding in its own right — half the snapshots match no preview —
and belongs to whoever picks up the matching heuristic next, not to this feature.

- **Built:** `mcp/` (pure: `ProjectSnapshot`, `ProjectSelector`, four tools, `ToolRegistry`, `McpDispatcher`,
  `McpHttpServer`), `service/McpServerService.kt`, `service/McpServerStartup.kt`, `ui/McpServerAction.kt`,
  `ui/McpServerDialog.kt`, `ui/McpClientConfig.kt`
- **Spec:** `docs/superpowers/specs/2026-08-07-mcp-index-server-design.md`
- **Does it replace F3?** Not answered yet, and now answerable the honest way: use it. The claim was that an
  agent with the whole project in context writes a better fake `UiState` than a PSI template can. Write a few
  snapshots that way against the live server before committing to F3's writer.

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
