# Preview Gallery — Phase 2 MVP: Live Rendering Design

| | |
|---|---|
| **Status** | Approved — ready for implementation planning |
| **Date** | 2026-07-23 |
| **Scope** | Phase 2 MVP of `compose-preview-gallery-plugin-spec.md` — render the selected preview |
| **Builds on** | [Phase 1](2026-07-23-preview-gallery-phase1-design.md) (index, tree, search, `PreviewRenderPlaceholder`) |
| **Target IDE** | Android Studio Panda 4 — build `AI-253.32098.37.2534.15336583` (platform branch 253) |

---

## 1. Scope

Phase 1 delivers discovery: a searchable tree of every `@Preview`, with navigation, and an empty
`PreviewRenderPlaceholder` in the lower panel. This phase fills that panel: selecting a preview renders it and
shows the image.

### In scope

- `LiveRenderer` — render a composable by FQN through Android Studio's own layoutlib pipeline, isolating all
  AS-internal API coupling in one class.
- `RenderPipeline` — orchestrate selection → render, with the states a render can be in.
- `BuildService` — build the target module on demand (explicit action only), through the IDE's Gradle
  integration, debounced, single-flight, cancellable.
- `PreviewRenderPanel` — replace `PreviewRenderPlaceholder`; show the image and the render states.

### Out of scope (later Phase 2 slices / Phase 3)

- Disk and memory image caches (§7.6 of the parent spec).
- Device / theme / API-level selectors — a single fixed render configuration is used.
- Rendering more than one preview at a time.
- CI-populated cache (Phase 3).
- Multiplatform (`org.jetbrains.compose`) preview support beyond "attempt it, show the failure".

### 1.1 Decisions taken during design

| # | Question | Decision | Rationale |
|---|---|---|---|
| D1 | Spike first, or straight to MVP | **Straight to MVP** | User chose it. The static API probe below already did Phase 0's desk-check; the runtime unknowns are resolved by making the first task a working vertical slice rather than a throwaway |
| D2 | Render entry point | **Low-level `org.jetbrains.android` path** (`StudioRenderService` + `RenderTask`), not the high-level `ComposeRenderer` | `ComposeRenderer` lives in `plugins/gemini/aiplugin.jar` — depending on the Gemini AI plugin is fragile and it may be absent. The low-level path is in `org.jetbrains.android`, which the plugin already `<depends>` on |
| D3 | Un-built module on select | **Selection never builds** (parent spec B3); a `NEEDS_BUILD` state offers a **Render** button that builds then renders | User's call. Browsing the tree must stay free; building is an explicit action |
| D4 | Navigation debounce / cancel | **400 ms debounce, single-flight, cancel any in-flight build or render when the selection changes** | User's call, matches parent spec B4. Arrow-keying through nodes must not queue builds or renders |
| D5 | Caching | **None in the MVP** — every render is live | Keeps the MVP focused. The disk/memory cache is a self-contained later slice; the pipeline is written so it can be inserted ahead of the live tier without touching `LiveRenderer` |
| D6 | Render configuration | **Fixed default** (no device/theme UI) | Selectors are a later slice; the MVP proves rendering works at all |
| D7 | Non-androidx previews | **Attempt regardless of annotation kind; a render failure is shown as `FAILED`** | The renderer keys on the composable FQN, not the annotation. Whether `org.jetbrains` / `commonMain` previews render is unknown (parent spec S5) and is observed, not pre-judged |

---

## 2. Verified API surface

A static probe of the Android Studio 253 jars was done during design (the desk-check half of the parent spec's
Phase 0). It replaces the parent spec's *unverified* Appendix A guesses with the **actual** signatures in this
build. Everything here is `com.android.tools.*` internal API — not a public extension point — so the API-stability
strategy in §8 applies to all of it.

### 2.1 Present and usable (in `org.jetbrains.android`)

```
com.android.tools.idea.rendering.StudioRenderService
    static RenderService getInstance(Project)

com.android.tools.rendering.RenderService
    RenderTaskBuilder taskBuilder(RenderModelModule, Configuration, RenderLogger)

com.android.tools.rendering.RenderService.RenderTaskBuilder
    RenderTaskBuilder withParserFactory(ILayoutPullParserFactory)
    RenderTaskBuilder withPsiFile(RenderXmlFile)
    RenderTaskBuilder usePrivateClassLoader()
    RenderTaskBuilder disableImagePool()
    RenderTaskBuilder withQuality(float)
    // NOTE: there is NO withXmlContent(...) — the parent spec guessed wrong. XML/parser is fed differently (§5.2).

com.android.tools.rendering.RenderTask
    CompletableFuture<RenderResult> inflate()
    CompletableFuture<RenderResult> render(IImageFactory)
    Future<?> dispose()
    boolean isDisposed()

com.android.tools.rendering.RenderResult
    boolean processImageIfNotDisposed(Consumer<ImagePool.Image>)
    // NOTE: no `renderedImage` field — the parent spec guessed wrong. The image arrives via this callback.

com.android.tools.idea.rendering.AndroidFacetRenderModelModule
    implements com.android.tools.rendering.api.RenderModelModule
    constructor(AndroidBuildTargetReference)

com.android.tools.idea.configurations.ConfigurationManager
    static ConfigurationManager getOrCreateInstance(Module)
    ConfigurationForFile getConfiguration(VirtualFile)

com.android.tools.preview.SingleComposePreviewElementInstance
    constructor(String composableFqn, PreviewDisplaySettings, T previousInstance, T ..., PreviewConfiguration, String)
    static forTesting(...)
```

### 2.2 Rejected

```
com.android.studio.ml.designer.compose.preview.agents.tools.ComposeRenderer
    // High-level: render(Project, String fqn, VirtualFile, ...) -> ComposeRendererResponse (a suspend fun).
    // Lives in plugins/gemini/aiplugin.jar — the Gemini AI plugin. Rejected per D2: fragile optional dependency.
```

### 2.3 Unverified — the first implementation task must resolve each against a running IDE

| # | Unknown | Where it bites |
|---|---|---|
| U1 | How to obtain an `AndroidBuildTargetReference` from a module / `AndroidFacet` | Building the `RenderModelModule` |
| U2 | How the `ComposeViewAdapter` bridge XML (`tools:composableName=...`) is fed — via `withParserFactory(ILayoutPullParserFactory)`, `withPsiFile(RenderXmlFile)`, or whether feeding a `SingleComposePreviewElementInstance` drives it | The core of the render call |
| U3 | What `IImageFactory` to pass to `render(...)`, or whether a default/pooled factory is available | Executing the render |
| U4 | Converting `ImagePool.Image` (from `processImageIfNotDisposed`) to a detached `BufferedImage` the panel can hold after `dispose()` | Handing the image to Swing |
| U5 | Constructing a default `PreviewConfiguration` and `PreviewDisplaySettings` | Building the preview element |
| U6 | Whether `com.android.tools.*` render classes are on the **compile** classpath via the bundled `org.jetbrains.android`, or need an extra dependency declaration | The build itself |

These are the reason the first task is a vertical slice verified in `runIde`, not a paper design.

---

## 3. Architecture

```
        PreviewGalleryPanel (Phase 1)
                │ selection (entry)
                ▼
        ┌───────────────┐      ┌──────────────────┐
        │ RenderPipeline│─────▶│ PreviewRenderPanel│  (replaces PreviewRenderPlaceholder)
        └──┬─────────┬──┘      └──────────────────┘
           │         │
   ┌───────▼──┐  ┌───▼─────────┐
   │ModuleFresh│ │ LiveRenderer │  ◀── ALL AS-internal API coupling, isolated
   │ ness      │ │ (guarded)   │
   └───────────┘ └───┬─────────┘
                     │ requires built module
                ┌────▼──────┐
                │BuildService│  (IDE Gradle integration — never a second daemon)
                └───────────┘
```

### 3.1 Responsibilities

| Component | Responsibility | AS-internal API? |
|---|---|---|
| `RenderPipeline` | On selection: classify (unsupported / fresh / needs-build), drive states, debounce, cancel in-flight work | No |
| `LiveRenderer` | FQN → `BufferedImage` via `StudioRenderService`/`RenderTask`; capability probe; `LinkageError`+`Exception` guard | **Yes — isolated here** |
| `BuildService` | Compile the module on demand via `ExternalSystemUtil.runTask`; single-flight; cancel; `DumbService`-gated | No |
| `ModuleFreshness` | Compare newest source mtime vs newest class-output mtime | No |
| `PreviewRenderPanel` | Render the five states; image display; `Render` / `Open file` actions | No |
| `RenderModelResolver` | Build a `RenderModelModule` + `Configuration` from a `PreviewEntry`'s module/file (resolves U1) | **Yes — isolated with `LiveRenderer`** |

**All AS-internal coupling lives in `LiveRenderer` and `RenderModelResolver`, nowhere else.** Every other
component is plain platform/PSI code and unit-testable.

---

## 4. Render pipeline

```
onSelect(entry):
    cancel any in-flight build and render
    debounce 400 ms, then:
        if entry.unsupportedReason != null            → UNSUPPORTED   (class-nested; from Phase 1 index)
        if entry.hasPreviewParameter                  → UNSUPPORTED   (cannot be invoked without a provider)
        if ModuleFreshness.isFresh(entry.module)      → render()      (state RENDERING → LIVE / FAILED)
        else                                          → NEEDS_BUILD   (offer the Render button)

onRenderButton(entry):
    cancel any in-flight build and render
    BuildService.compile(entry.module)                (state RENDERING with a "building" note)
        on success → render()
        on failure → FAILED (build log)

render():
    LiveRenderer.render(entry, RenderConfig.DEFAULT)   off the EDT
        Success(image) → LIVE
        Failure        → FAILED
        Unsupported    → UNSUPPORTED
    image handed to the panel on the EDT
```

**Key property carried from the parent spec:** selection never builds; only the explicit **Render** button does.
Cache is absent in the MVP, so the "cached" tiers of the parent spec's §7.3 pipeline are simply not present —
the pipeline is a single live tier plus build-on-demand.

---

## 5. LiveRenderer

### 5.1 Threading and lifecycle (mandatory, from parent spec §7.4)

1. `task.dispose()` in a `finally` block — always. Skipping it leaks layoutlib render contexts.
2. Never render on the EDT. Respect `RenderService`'s threading contract; the pipeline calls it off the EDT.
3. PSI / project-model access under a read action.
4. A render timeout (30 s); expiry is a `Failure`, not a hang.
5. The image handed out must be a standalone `BufferedImage` copied out of the `ImagePool.Image` inside
   `processImageIfNotDisposed`, before `dispose()` — the pooled image is invalid after disposal (U4).

### 5.2 Call sequence (from §2.1; the ⚠️ steps are the §2.3 unknowns)

```kotlin
val module    = entry.module                                   // resolved in Phase 1
val facet     = AndroidFacet.getInstance(module) ?: return Unsupported("no Android facet")
val buildRef  = /* ⚠️ U1: AndroidBuildTargetReference from facet/module */
val renderMod = AndroidFacetRenderModelModule(buildRef)
val config    = ConfigurationManager.getOrCreateInstance(module).getConfiguration(entry.file)
val logger    = /* RenderLogger for the module */

val element   = SingleComposePreviewElementInstance(
    entry.composableFqn, defaultDisplaySettings(), null, null, PreviewConfiguration.DEFAULT /* ⚠️ U5 */, entry.file.path,
)

val task = StudioRenderService.getInstance(project)
    .taskBuilder(renderMod, config, logger)
    .withParserFactory(/* ⚠️ U2: ComposeViewAdapter parser for element */)
    .build()
    .get(TIMEOUT_MS, MILLISECONDS)

try {
    task.inflate().get(TIMEOUT_MS, MILLISECONDS)
    val result = task.render(/* ⚠️ U3: IImageFactory */).get(TIMEOUT_MS, MILLISECONDS)
    var image: BufferedImage? = null
    result.processImageIfNotDisposed { pooled -> image = pooled.copy() /* ⚠️ U4 detach */ }
    Success(image ?: return Failure("no image"))
} finally {
    task.dispose()   // MANDATORY
}
```

The first task's job is to turn every ⚠️ into a concrete, working line, verified by a rendered PNG in `runIde`.

### 5.3 API-stability guard (parent spec §7.7)

1. **Capability probe.** On first use, `LiveRenderer.isAvailable()` reflectively verifies the classes and method
   signatures in §2.1 exist. On mismatch: log once, disable live rendering for the session, every render returns
   `Unsupported("renderer unavailable on this IDE build")`.
2. **Runtime guard.** Every AS-internal call site catches both `Exception` and `LinkageError`
   (`NoSuchMethodError`, `NoClassDefFoundError`). A signature change on a newer IDE degrades one preview to
   `FAILED` instead of breaking the plugin.

---

## 6. BuildService

Compiles the target module's classes so `LiveRenderer` has something to load.

| # | Rule | Rationale |
|---|---|---|
| B1 | Use the IDE's Gradle integration: `ExternalSystemUtil.runTask` with `GradleConstants.SYSTEM_ID`. **Never spawn a Gradle daemon directly.** | A second daemon costs gigabytes. The single largest performance risk |
| B2 | Compile the minimum: `:path:to:module:compileDebugKotlin`, never `assembleDebug`. | Transitive deps come along; app packaging is not needed |
| B3 | Selection must not build. Builds run only on the explicit **Render** button. | Browsing stays free |
| B4 | Debounce 400 ms; single-flight; cancel any in-flight build when the selection changes. | Arrow-keying must not queue builds |
| B5 | Disabled while `DumbService.isDumb`. | Never compete with indexing |
| B6 | Surface build progress in the standard IDE progress UI, cancellable. | Predictability |

**Freshness heuristic (`ModuleFreshness`).** Compare the newest source-file mtime in the module against the
newest class-file mtime in its build output. Cheap and adequate; a wrong answer costs at most one redundant
build or one `NEEDS_BUILD` prompt. Pure and unit-testable given two mtime inputs.

---

## 7. UI — `PreviewRenderPanel`

Replaces `PreviewRenderPlaceholder` in the lower splitter half. States (parent spec §5.3, minus the cache
tiers):

| State | Display |
|---|---|
| `RENDERING` | Progress indicator (+ "building…" note when a build is running) |
| `LIVE` | The image, no badge |
| `NEEDS_BUILD` | "Module not built" + **Render** button |
| `FAILED` | Error summary + **Open file** + expandable log |
| `UNSUPPORTED` | Reason (class-nested / `@PreviewParameter` / renderer unavailable) + **Open file** |

The image is scaled to fit the panel, preserving aspect ratio. No zoom/export in the MVP.

---

## 8. Data model

```kotlin
data class RenderConfig(
    val deviceSpec: String = "spec:width=411dp,height=891dp",
    val themeName: String? = null,
    val uiMode: Int = 0,
    val apiLevel: Int = -1,       // -1 = module default
    val fontScale: Float = 1.0f,
) {
    companion object { val DEFAULT = RenderConfig() }
}

sealed interface RenderOutcome {
    data class Success(val image: BufferedImage) : RenderOutcome
    data class Failure(val message: String, val detail: String?) : RenderOutcome
    data class Unsupported(val reason: String) : RenderOutcome
}

enum class RenderState { RENDERING, LIVE, NEEDS_BUILD, FAILED, UNSUPPORTED }
```

`RenderConfig` exists as a fixed default now so the later device/theme slice has a seam to widen; the MVP only
ever uses `RenderConfig.DEFAULT`.

---

## 9. Build / dependency setup

- The render classes are in the bundled `org.jetbrains.android` plugin (already a `<depends>` and a
  `bundledPlugins(...)` entry from Phase 1). The first task confirms whether `com.android.tools.*` is on the
  **compile** classpath through that bundled plugin or needs an explicit dependency (U6). If an extra dependency
  is required, it is added to `build.gradle.kts`'s `intellijPlatform { ... }` block, not as a raw jar.
- `plugin.xml` needs no new `<depends>` — `org.jetbrains.android` is already declared.
- No new Kotlin/plugin-mode declaration beyond Phase 1's `supportsK2`.

---

## 10. Testing

| Target | How | Cases |
|---|---|---|
| `ModuleFreshness` | Plain JUnit against two mtime inputs | source newer → stale; class newer → fresh; no classes → stale; empty module → stale |
| `RenderPipeline` classification | Plain JUnit against a fake renderer + fake freshness + fixed `PreviewEntry`s | unsupported reason → UNSUPPORTED; `@PreviewParameter` → UNSUPPORTED; fresh → render called; stale → NEEDS_BUILD; selection change cancels in-flight |
| `RenderState` transitions | Plain JUnit | RENDERING → LIVE / FAILED / UNSUPPORTED; NEEDS_BUILD → RENDERING on build |
| `RenderConfig` default | Plain JUnit | `DEFAULT` values |
| `LiveRenderer`, `RenderModelResolver`, `BuildService` | **Manual, in `runIde`** against a real multi-module Compose project | a built androidx preview renders to an image; an un-built module shows NEEDS_BUILD then renders after Render; `@PreviewParameter` → UNSUPPORTED; 20 sequential renders leak no significant memory (parent spec S4); no second Gradle daemon appears (parent spec R3) |

The AS-internal path cannot be unit-tested without a full IDE + a real Android module + the SDK, so it is
verified manually, exactly as the parent spec's Phase 0 success criteria (S2, S4) intended. The pure components
carry the automated coverage.

---

## 11. Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | The ⚠️ call-chain unknowns (U1-U5) don't resolve cleanly | Medium | High | First task is a `runIde`-verified vertical slice; if a step is intractable, fall back to the parent spec's Phase 3 (CI-rendered PNG cache) as the image source |
| R2 | Internal render APIs change on IDE upgrade | High over time | Medium | §5.3 probe + `LinkageError` guard; all coupling in `LiveRenderer`/`RenderModelResolver` |
| R3 | Second Gradle daemon spawned | Low if B1 honoured | Severe | Mandatory B1; assert in review; manual check for a second daemon |
| R4 | layoutlib contexts leak | Medium | High | `dispose()` in `finally`; manual 20-render memory check |
| R5 | `org.jetbrains.compose` / `commonMain` previews don't render | Unknown | Medium | D7: attempt, show `FAILED`; the design-system module's fate is a later decision, not an MVP blocker |
| R6 | `com.android.tools.*` not on the compile classpath | Medium | High — blocks everything | U6 is the very first thing the first task checks; resolved before any renderer code is written |

### 11.1 Guaranteed fallback

If the IDE-side render path proves intractable, the parent spec's §11.1 fallback stands: render previews in CI
to PNGs (Roborazzi / Paparazzi + ComposablePreviewScanner) and serve those. Heavier, held in reserve.

---

## 12. Acceptance criteria

| # | Criterion |
|---|---|
| AC1 | Selecting a built androidx preview shows its rendered image in the lower panel |
| AC2 | Selecting an un-built module's preview shows `NEEDS_BUILD`; clicking **Render** builds the module and then renders |
| AC3 | Arrow-keying through previews cancels the in-flight render/build and does not queue work (400 ms debounce) |
| AC4 | A `@PreviewParameter` or class-nested preview shows `UNSUPPORTED`, not a crash |
| AC5 | A render that fails shows `FAILED` with a log and an **Open file** action; the plugin stays usable |
| AC6 | No second Gradle daemon appears during a build (verified by process inspection) |
| AC7 | 20 sequential renders do not leak significant memory (each `RenderTask` disposed) |
| AC8 | On an IDE build where the render API is absent/changed, the capability probe disables live rendering and previews show `UNSUPPORTED` — the plugin still loads and Phase 1 discovery still works |
| AC9 | `./gradlew test` passes (the pure components); the plugin loads in Android Studio 253 with no errors in `idea.log` |

---

## 13. Known gaps carried forward

| # | Gap | Consequence | Owner |
|---|---|---|---|
| G1 | No image cache | Every selection re-renders; re-selecting a preview rebuilds the image | Later Phase 2 slice |
| G2 | Fixed render config | No device/theme/API switching | Later Phase 2 slice |
| G3 | Multiplatform previews unproven | `commonMain` design-system previews may show `FAILED` | Depends on R5 outcome |
| G4 | Manual-only coverage for the AS-internal path | No automated regression on `LiveRenderer` | Inherent to internal-API rendering; Phase 3 CI cache would add it |
