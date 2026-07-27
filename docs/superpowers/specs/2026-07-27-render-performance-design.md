# Render Performance — Build Decision, Warm Render, Fast Preview

| | |
|---|---|
| **Scope** | Phase 7 — make the gallery render as fast as Android Studio's own in-editor Compose preview: stop building when a build isn't needed, stop rebuilding the render stack on every render, and recompile in-process instead of through Gradle. |
| **Builds on** | [Phase 6](2026-07-26-comparison-views-device-snapshots-design.md) — comparison views share the same render pipeline and benefit from every layer here. |
| **Tech** | Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 |
| **Commit prefix** | `[PG7-N]` |

## Goal

Today, selecting a preview in the gallery often triggers a full Gradle build and then a cold render, while Android Studio's own preview tab shows the same composable immediately — even while a build is running elsewhere. This phase closes that gap in three layers: **(1)** decide "does this need a build?" from Android Studio's own build state instead of a filesystem mtime guess, and render optimistically instead of blocking on the build; **(2)** keep the render stack warm — reuse the `RenderTask` and its module class loader instead of building and disposing one per render; **(3)** route "the code changed" through Android Studio's in-process Fast Preview compiler instead of a Gradle invocation, falling back to today's build when it is unavailable.

## Non-Goals

- **A rendered-image cache / `CACHED` state** — explicitly excluded by the user for this phase; re-selecting a preview still renders (but from a warm stack, which is the point).
- **Live Edit** (editing the composable and seeing it update as you type) — out of scope; Fast Preview here is a *compile* strategy, not an editing mode.
- **Changing what we render** — no change to config-aware rendering, comparison views, the overlay, or export.
- **Multi-preview / batch rendering or pre-warming every preview in the tree** — one render at a time stays the model.
- **Replacing `BuildService`** — Gradle stays the fallback path and the explicit "Build & retry" action.

## Current state (what this replaces)

Verified by reading the code and disassembling the AS 253 jars:

- **Build decision.** `RenderPipeline.dispatch` calls `ModuleFreshness.isModuleFresh(module)` on every selection; `classify()` turns "not fresh" into `NEEDS_BUILD`, which runs a real Gradle `compileDebugKotlin` through `BuildService` and only renders afterwards. `ModuleFreshness.isFresh` compares the newest source mtime against the newest `.class` mtime found by a `maxDepth(8)` walk of a *guessed* `build/` root, cached 5 s. It reports stale in three cases where the previewed class is actually fine: when the Gradle module data can't be resolved (`newestClassMtime == 0` ⇒ always stale), when any unrelated file in the module is newer (module-wide granularity), and when the real class file sits deeper than 8 levels. `BuildService` single-flights only *its own* task, so it cannot see a build started by Run/Make and will start a second one.
- **Render cost.** `LiveRenderer.renderResolved` builds a fresh `RenderTask` per render and disposes it in `finally` ("MANDATORY: release layoutlib render contexts / class loaders"); `RenderModelResolver` builds a fresh `RenderModelModule`, `Configuration` and `RenderLogger` per call. Nothing is reused, so every render pays full setup and class loading. The double-render + Compose callback drain is *necessary* (AS does the same) and is not the target here.
- **Android Studio, by contrast.** `RenderingBuildStatusManager` computes status from event-driven signals — a `ProjectSystemBuildManager` build listener (which sees *every* build regardless of trigger), a resource-change listener, and a per-file PSI modification-count plus an "is there already a class file for this file" check — never an mtime walk. `LayoutlibSceneRenderer` keeps its `RenderTask` in a mutable field across renders. `StudioModuleClassLoaderManager` hands out shared, reference-counted module class loaders, with `ModuleClassLoaderHatchery` pre-warming spares and `Preloader` loading classes ahead of need. `FastPreviewManager.compileRequest(files, BuildTargetReference, ProgressIndicator, …)` compiles just the changed files through an out-of-process compiler daemon it can `preStartDaemon(...)`, de-duplicating in-flight requests; a full Gradle build is the rare, distinguished `PreviewStatus.NeedsBuild` path.

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | **Android Studio's build state is the authority; our mtime heuristic becomes a hint.** A new `render/BuildStateProbe` reads `ProjectSystemBuildManager` (`isBuilding()`, `getLastBuildResult()`), guarded. A successful last build ⇒ output is usable; a build in flight ⇒ never start another. `ModuleFreshness` stays only as a fallback when the probe is unavailable. | The probe sees every build from any trigger and is O(1) state, not a directory walk. It removes the "I just built via Run, now the gallery builds again" case the user reported. |
| D2 | **Fail-open, not fail-stale.** When neither the probe nor the heuristic can tell (Gradle data unresolved, probe unavailable), we **attempt the render** instead of forcing a build. A render that fails because classes are genuinely missing then falls back to the build path. | Today's `newestClassMtime == 0 ⇒ always stale` turns "I don't know" into "always build," which is the most common needless build. Attempting a render is cheap relative to a Gradle invocation, and its failure is a *better* signal than a timestamp guess. |
| D3 | **Optimistic render, background build.** When output is usable but possibly out of date, render **immediately** from what exists and, if a build is warranted, run it in the background and re-render silently when it finishes — instead of blocking on the build first. `NEEDS_BUILD` remains the state only when there is nothing renderable at all. | The user's chosen posture ("önce cache, arkada tazele") and exactly what AS does: it renders from the last successful build's classes while a build runs elsewhere. |
| D4 | **Warm render stack.** A new `render/RenderTaskCache` keeps one `RenderTask` (with its `RenderModelModule`/`Configuration`) per **(module, config key)**, reused across re-renders, picker refreshes and comparison-view tabs. Disposal moves from `finally`-every-render to explicit invalidation. | The single largest measured cost: today every render pays full task + class-loader setup. AS keeps its `RenderTask` in a field. |
| D5 | **Invalidation rules for the cache (all guarded):** a completed build (build listener), a module change/close, project close, panel/tool-window disposal, and an idle TTL. Any invalidation disposes the task off the EDT. A render that throws also drops its cached entry. | Reuse is only safe with disciplined invalidation; the build listener is the same signal AS uses. The TTL bounds native/layoutlib memory held while the tool window sits unused. |
| D6 | **Fast Preview before Gradle.** A new `render/FastCompileBridge` calls `FastPreviewManager.compileRequest(...)` for the changed file(s) when the manager is available and enabled; on unavailable, disabled, failure, or timeout it falls back to today's `BuildService` Gradle path. The compiled output must reach the render's class loader — the mechanism (AS overlays the compiled classes for the module class loader) is the phase's main unknown (V3). | Turns "wait for Gradle" into "wait for an in-process incremental compile," which is what makes AS's preview feel instant after an edit. The fallback keeps today's behaviour whenever anything about it is unavailable. |
| D7 | **Every layer is independently switchable and independently degradable.** Each of `BuildStateProbe`, `RenderTaskCache` and `FastCompileBridge` is capability-probed (`RenderApiProbe`) and, when unavailable, the pipeline behaves exactly as it does today. Layers land in order 1 → 2 → 3, each with its own gate. | Standard posture for this plugin: never crash, never remove existing behaviour. It also means a failed layer 3 does not jeopardise the wins from layers 1–2. |
| D8 | **Measure, don't assert.** Each layer's gate records "selection → first pixel" for the same preview before and after, plus whether a build was triggered. The numbers go in the phase's changelog entry. | Without a before/after number, "it feels faster" is unfalsifiable — and one of these layers could plausibly make things *worse* (e.g. a stale warm task). |
| D9 | **Isolation unchanged.** All new code lives in `render/`; `ui/` and `model/` gain nothing AS-internal. Decision logic that can be pure (when to build, when to invalidate, which state to show) lives in plugin-owned, unit-tested objects that take plain inputs, not AS types. | Keeps the Phase 1–6 boundary and keeps the risky logic testable without an IDE. |

## Interfaces (indicative)

```kotlin
// render/ — pure decision core, unit-tested (no AS types in the signature)
enum class BuildState { UP_TO_DATE, OUT_OF_DATE, BUILDING, NEEDS_BUILD, UNKNOWN }

/** What the pipeline should do for one selection, decided from the signals we have. */
sealed interface RenderPlan {
    object RenderNow : RenderPlan                       // output usable — render, no build
    object RenderNowRefreshAfterBuild : RenderPlan      // render now; build in background; re-render after (D3)
    object CompileThenRender : RenderPlan               // Fast Preview compile first (D6)
    object BuildThenRender : RenderPlan                 // nothing renderable — today's blocking path
}

object RenderPlanner {
    /** Pure: no AS types, no I/O. [buildState] comes from BuildStateProbe, [heuristicFresh] from ModuleFreshness
     *  (null when it cannot tell), [fastCompileAvailable] from the probe. */
    fun plan(buildState: BuildState, heuristicFresh: Boolean?, fastCompileAvailable: Boolean): RenderPlan
}

// render/ — AS-internal, guarded + probed
object BuildStateProbe { fun stateOf(project: Project, module: Module): BuildState }   // ProjectSystemBuildManager
class RenderTaskCache : Disposable {                                                    // D4/D5
    fun acquire(key: RenderCacheKey, create: () -> RenderTask?): RenderTask?
    fun invalidate(module: Module)                       // build finished / module changed
    fun invalidateAll()
}
data class RenderCacheKey(val moduleName: String, val configKey: String)                // plugin-owned key
class FastCompileBridge(private val project: Project) {                                 // D6
    fun isAvailable(): Boolean
    /** Compiles [files] in-process; false ⇒ caller falls back to BuildService. Never on the EDT. */
    fun compile(files: List<VirtualFile>, module: Module): Boolean
}
// RenderApiProbe gains: isBuildStateAvailable(), isRenderTaskReusable(), isFastPreviewAvailable()
```

## Unknowns (discovery gates — settled in `runIde`, like every prior AS-internal phase)

| # | Unknown | Where it bites | Degrade |
|---|---------|----------------|---------|
| V1 | Is a `RenderTask` safely reusable across renders on our path, and across a **changed `Configuration`** (comparison views change device/theme/scale), or must the cache be keyed per config — or is a fresh `Configuration` on a reused task enough? | D4 — the warm-render win. | Key the cache per (module, config) — worst case one task per distinct config; if reuse proves unsafe at all, disable the cache and keep today's dispose-per-render. |
| V2 | Which `ProjectSystemBuildManager.BuildStatus`/`BuildMode` combinations actually mean "output is usable" on this build, and whether `getLastBuildResult()` survives an IDE restart (i.e. is `UNKNOWN` common in practice?). | D1/D2 — the build decision. | `UNKNOWN` follows D2 (attempt the render); the heuristic remains as a secondary hint. |
| V3 | **How Fast Preview's compiled output reaches our render.** AS compiles to an overlay and its module class loader is told about it; whether our `RenderTask`/class loader picks it up automatically, needs the overlay path applied, or needs a fresh (non-cached) loader is unverified. | D6 — the whole Fast Preview layer. | If the output cannot reach our render, drop layer 3 entirely: Gradle fallback keeps today's behaviour, and layers 1–2 stand alone. |
| V4 | Does reusing a warm task/class loader ever show **stale classes** after a build (i.e. is the build-listener invalidation sufficient), and does the idle TTL actually release native/layoutlib memory? | D5 — invalidation correctness. | Shorten the TTL / invalidate more aggressively; worst case, cache only within a single selection's lifetime. |

## Architecture

```
selection / re-render request
        │
        ▼
  BuildStateProbe.stateOf(project, module)          (render/, AS-internal, guarded)
  ModuleFreshness.isModuleFresh(module)             (existing heuristic — now only a hint)
        │
        ▼
  RenderPlanner.plan(buildState, heuristicFresh, fastCompileAvailable)     ← PURE, unit-tested
        │
        ├── RenderNow ─────────────► render (warm)
        ├── RenderNowRefreshAfterBuild ─► render (warm) now │ background build │ re-render on completion
        ├── CompileThenRender ─► FastCompileBridge.compile(files, module) ─► render (warm)
        │                              └── false ─► BuildService (Gradle) ─► render
        └── BuildThenRender ─► BuildService (Gradle) ─► render        (today's path; nothing renderable)
        │
        ▼
  LiveRenderer.render(entry, viewConfig)
        └── RenderTaskCache.acquire(RenderCacheKey(module, config)) ─► warm RenderTask
              • inflate → render → drain Compose callbacks → render (unchanged; required)
              • NO dispose per render — disposal only on invalidation

  build finished (ProjectSystemBuildManager listener) ─► RenderTaskCache.invalidate(module) ─► re-render current
  module changed / project closed / panel disposed / idle TTL ─► invalidate
```

## Components

| Unit | Responsibility | AS-internal? |
|------|----------------|--------------|
| `RenderPlanner` (render/) | Pure decision: given build state + heuristic + capability, which `RenderPlan` | No |
| `BuildStateProbe` (render/) | Read `ProjectSystemBuildManager` → `BuildState`, guarded | **Yes** |
| `RenderTaskCache` (render/) | Hold/reuse one `RenderTask` per (module, config), invalidate + dispose off the EDT | **Yes** |
| `FastCompileBridge` (render/) | `FastPreviewManager.compileRequest` with fallback to `BuildService` | **Yes** |
| `RenderPipeline` (render/) | Consume the plan: render now, background build + silent re-render, or build first | Yes (existing) |
| `LiveRenderer` (render/) | Acquire from the cache instead of building/disposing per render | Yes (existing) |
| `ModuleFreshness` (render/) | Demoted to a hint; fail-stale removed (returns "unknown" instead of "stale") | No |
| `BuildService` (render/) | Unchanged Gradle fallback; gains "don't start if a build is already running" | No |
| `RenderApiProbe` (render/) | Three new capability checks gating each layer | **Yes** |

## Testing

- **Pure unit (JUnit, no fixture):** `RenderPlanner.plan` across the full matrix of (`BuildState` × heuristic `true`/`false`/`null` × fast-compile available/not) — this is where the phase's branching risk lives, and it is exactly the logic that would otherwise only be reachable in a running IDE. Plus `RenderCacheKey` equality/derivation, and `ModuleFreshness`'s new "unknown" (rather than stale) result.
- **Manual `runIde` gate, one per layer** (the standing posture since Phase 2): layer 1 — selecting a preview right after an external build does not trigger another build; a build started elsewhere is never duplicated; an unresolvable module renders instead of building. Layer 2 — repeated selections/re-renders and comparison-view tabs are visibly faster, and a build's completion still updates what is rendered (no stale classes). Layer 3 — editing a composable and re-rendering compiles in-process (no Gradle invocation), and disabling/failing Fast Preview falls back to a Gradle build without a crash.
- **Measurement (D8):** for the same preview, record "selection → first pixel" and "was a build triggered" before and after each layer; report the numbers in the changelog.

## Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|------|-----------|--------|------------|
| R1 | A reused `RenderTask`/class loader serves **stale classes** after a build (V4). | Medium | High | Invalidate on the build listener (the same signal AS uses) plus module change; gate explicitly re-checks a render after a build; if it cannot be made reliable, cache only within one selection. |
| R2 | Fast Preview's output never reaches our render (V3), making layer 3 dead work. | Medium | Medium | Layer 3 is last and independently gated; failure drops it with layers 1–2 intact and today's Gradle fallback unchanged. |
| R3 | Optimistic rendering (D3) shows a **briefly outdated** render before the background build lands. | Medium | Low | The user chose this posture; the background re-render replaces it silently, and `NEEDS_BUILD` still covers "nothing renderable." |
| R4 | Holding a warm task/class loader leaks native/layoutlib memory while the tool window is idle (V4). | Medium | Medium | Idle TTL + invalidate on tool-window/panel disposal + project close; gate watches memory across many selections. |
| R5 | Removing fail-stale (D2) lets a genuinely unbuilt module reach the renderer and fail. | Low | Low | The render failure is caught (existing posture) and falls back to the build path — one failed cheap render instead of one needless Gradle build. |
| R6 | Three AS-internal surfaces added at once increases exposure to an IDE-build change. | Medium | Low | Each is probed and independently degradable (D7); an unavailable layer means today's behaviour, never a crash. |

## Acceptance Criteria

- **AC1** Selecting a preview whose module was just built elsewhere (Run/Make, or a build the gallery did not start) renders **without triggering another build**.
- **AC2** While a build is in flight, the gallery never starts a second build; if something is renderable it renders instead of blocking.
- **AC3** When neither the build state nor the heuristic can tell, the gallery **attempts a render** rather than forcing a build; a genuinely missing-class failure falls back to the build path without a crash.
- **AC4** A repeated render of the same preview (re-selection, picker refresh, a comparison-view tab) reuses the warm render stack and is measurably faster than the same action before this phase.
- **AC5** After a build completes, the next render reflects the new classes — no stale render survives an invalidation signal.
- **AC6** With Fast Preview available, changing a composable's code and re-rendering compiles in-process (no Gradle invocation); with it unavailable, disabled, or failing, the gallery falls back to today's Gradle build path with no crash and no lost behaviour.
- **AC7** Each layer's capability probe returning false leaves the pipeline behaving exactly as it does today.
- **AC8** "Selection → first pixel" is recorded before/after each layer and reported in the changelog; Phase 1–6 behaviour does not regress and `./gradlew test` is green (existing plus the new `RenderPlanner` matrix).
