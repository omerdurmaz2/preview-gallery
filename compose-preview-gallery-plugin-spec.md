# Compose Preview Gallery — IntelliJ/Android Studio Plugin Spec

| | |
|---|---|
| **Status** | Draft — pending Phase 0 spike |
| **Date** | 2026-07-23 |
| **Target IDE** | Android Studio Panda 4 (2025.3.4 Patch 1) → IntelliJ Platform branch **253** |
| **Implementation repo** | Separate plugin repository (not the Android app repo) |
| **Reference corpus** | `hepsi-android` — 75 Gradle modules, 913 `@Preview` annotations |

---

## 1. Summary

An IntelliJ Platform plugin that gives developers a **project-wide, searchable browser for Jetpack Compose `@Preview` functions**, with the selected preview rendered inside the plugin's own panel.

Android Studio only renders previews for the file currently open in the editor. In a large multi-module codebase there is no way to answer *"what components do we have, and what do they look like?"* without knowing the file path in advance. This plugin closes that gap.

**Primary rendering strategy:** call Android Studio's own rendering engine (layoutlib via `RenderService`).
**Fallback:** previously rendered images from an on-disk cache.

---

## 2. Problem & motivation

| Problem | Impact |
|---|---|
| Previews are only visible per-open-file | Component discovery requires prior knowledge of file paths |
| No project-wide component catalog | Components get re-implemented because nobody knew they existed |
| Design system components are spread across modules | Highest-value components are the hardest to find |
| Onboarding cost | New developers cannot browse what the codebase already offers |

---

## 3. Goals & non-goals

### Goals

- **G1** — Index every `@Preview` function in the project, incrementally and persistently.
- **G2** — Present them as a tree grouped by module → package, in a tool window.
- **G3** — Filter the tree instantly by a search box (substring / fuzzy over the preview name).
- **G4** — Render the selected preview in the lower half of a split panel.
- **G5** — Never degrade IDE performance perceptibly; never spawn a second Gradle daemon.
- **G6** — Keep memory usage bounded and predictable regardless of how many previews are browsed.
- **G7** — Degrade gracefully: if live rendering is unavailable, the plugin still works.

### Non-goals

- **N1** — Not a replacement for Android Studio's in-file preview during component development.
- **N2** — No editing, hot reload, or interactive preview.
- **N3** — No simultaneous rendering of many previews (one at a time, on demand).
- **N4** — Not a published design-system documentation site.
- **N5** — No device-farm or physical-device screenshots.
- **N6** — Not a snapshot/regression testing tool (although Phase 3 shares infrastructure with one).

---

## 4. Target environment

| Item | Value |
|---|---|
| IDE | Android Studio Panda 4, 2025.3.4 Patch 1 |
| IntelliJ Platform branch | **253** |
| Plugin type | `AI` (Android Studio) via IntelliJ Platform Gradle Plugin |
| `sinceBuild` | `253` |
| `untilBuild` | Leave open or `253.*` — see §7.7 for the version-tolerance strategy |
| Required plugin dependency | `org.jetbrains.android` (provides the rendering stack) |
| Language | Kotlin |
| Min JDK | Match the target IDE's bundled JDK |

> **Note.** The rendering APIs used in §7.4 are **internal implementation details of the Android plugin**, not public extension points. Every signature in this document is marked as *unverified* until Phase 0 confirms it against this exact build.

---

## 5. UX specification

### 5.1 Layout

```
┌─ Compose Gallery ──────────────────────────────┐
│ [ 🔍 primus tab                              ] │  ← search field
├────────────────────────────────────────────────┤
│ ▼ primus/ui                          (312)     │
│    ▼ components/tabs                           │  ← tree (module → package → preview)
│       • PrimusTabsPreview                      │
│       • PrimusTabsScrollablePreview            │
│ ▶ features/basket/ui                  (47)     │
│ ▶ features/home/ui                    (89)     │
├──────────────────── splitter ──────────────────┤
│                                                │
│           [ RENDERED IMAGE ]                   │  ← render panel
│                                                │
│  device ▾   theme ▾   🔄 Render   ↗ Open file  │
└────────────────────────────────────────────────┘
```

### 5.2 Interactions

| Action | Behaviour |
|---|---|
| Type in search field | Tree filters live. Empty query → full tree. Debounce 150 ms. |
| Select a tree node | Render panel updates per the pipeline in §7.3. **Never triggers a build.** |
| Arrow-key through nodes | Same as select, debounced 400 ms; in-flight render is cancelled. |
| Click **Render** | Explicitly requests a live render, building the module if required. |
| Click **Open file** / double-click node | Navigates the editor to the preview function. |
| Change device / theme | Invalidates the cache key and re-renders. |
| Hide the tool window | Memory image cache is cleared. |

### 5.3 Render panel states

| State | Display |
|---|---|
| `CACHED` | Image + subtle "cached" badge and timestamp |
| `RENDERING` | Previous image dimmed + progress indicator |
| `LIVE` | Image, no badge |
| `NEEDS_BUILD` | Message + **Render** button ("Module not built") |
| `FAILED` | Error summary + **Open file** + expandable log |
| `UNSUPPORTED` | Explanation (e.g. `@PreviewParameter` unsupported) + **Open file** |

---

## 6. Architecture

```
┌─────────────────────────────────────────────────────┐
│                    Tool Window                      │
│  ┌──────────────┐            ┌──────────────────┐   │
│  │ SearchField  │            │  RenderPanel     │   │
│  │ PreviewTree  │            │                  │   │
│  └──────┬───────┘            └────────▲─────────┘   │
└─────────┼───────────────────────────── │ ───────────┘
          │ select                       │ image
          ▼                              │
   ┌─────────────┐               ┌───────┴────────┐
   │ PreviewIndex│               │ RenderPipeline │
   │ (FileBased  │               └───┬────────┬───┘
   │  Index)     │                   │        │
   └─────────────┘         ┌─────────▼──┐  ┌──▼──────────────┐
                           │ImageCache  │  │ PreviewRenderer │
                           │ disk + mem │  │   (interface)   │
                           └────────────┘  └──┬───────────┬──┘
                                              │           │
                                   ┌──────────▼──┐   ┌────▼──────────┐
                                   │LiveRenderer │   │SnapshotRenderer│
                                   │(AS internal)│   │  (disk PNG)    │
                                   └──────┬──────┘   └────────────────┘
                                          │ requires
                                   ┌──────▼──────┐
                                   │ BuildService│
                                   │ (Gradle via │
                                   │  IDE daemon)│
                                   └─────────────┘
```

### 6.1 Component responsibilities

| Component | Responsibility | AS-internal API? |
|---|---|---|
| `PreviewIndex` | Persistent, incremental index of all `@Preview` functions | No |
| `PreviewTree` | Tree model, grouping, filtering | No |
| `RenderPipeline` | Orchestrates cache → live → snapshot → failure | No |
| `ImageCache` | Two-tier (disk + memory) image storage | No |
| `LiveRenderer` | Renders via `RenderService` / layoutlib | **Yes — isolated here** |
| `SnapshotRenderer` | Serves previously rendered PNGs | No |
| `BuildService` | Triggers minimal Gradle compilation on demand | No |

**All AS-internal coupling lives in `LiveRenderer` and nowhere else.**

---

## 7. Detailed design

### 7.1 Preview index

Implemented as a `FileBasedIndex` over Kotlin files. Persistent across IDE restarts, incrementally updated on file change.

**Why an index rather than on-demand PSI scanning:** with ~900 previews across ~75 modules, a full PSI sweep on every tool-window open is too slow. `FileBasedIndex` gives instant startup and only reprocesses changed files.

**Detected annotations — both must be indexed:**

| Annotation | Typical location |
|---|---|
| `androidx.compose.ui.tooling.preview.Preview` | Android modules |
| `org.jetbrains.compose.ui.tooling.preview.Preview` | Kotlin Multiplatform `commonMain` |

These are **distinct annotation classes with distinct rendering paths**. The index records which one applies; the renderer branches on it.

Also detect multipreview annotations — user-defined annotations that are themselves annotated with `@Preview`. Resolve one level of indirection at minimum.

**Indexed per entry:** see the data model in §8.

### 7.2 Tree & search

- Grouping: `module → package → preview`. Module nodes show a preview count.
- Sorting: alphabetical; a configurable list of modules can be pinned to the top (e.g. the design-system module).
- Search: case-insensitive substring over preview name, function name and package. Fuzzy matching is optional (`com.intellij.util.text.Matcher`).
- Performance: with <10k entries, filtering is a plain in-memory scan. No optimisation required.
- Optional extra: register a `SearchEverywhereContributor` so previews are reachable from Shift-Shift.

### 7.3 Render pipeline

```
selectPreview(entry, config):
    key = cacheKey(entry, config)

    1. memory cache hit?   → display immediately, done
    2. disk cache hit?     → decode, display (state = CACHED)
    3. module outputs fresh?
         yes → live render in background (state = RENDERING → LIVE)
         no  → state = NEEDS_BUILD, wait for explicit user action
    4. live render failed?
         disk image exists → keep showing it (state = CACHED)
         otherwise         → state = FAILED
```

**Key property:** the live renderer writes every successful render to the disk cache. The fallback tier is therefore populated by the primary path — no separate snapshot infrastructure is required for the fallback to be useful.

### 7.4 LiveRenderer — ⚠️ unverified APIs

Renders through the same layoutlib pipeline Android Studio uses for its own Compose preview.

**Mechanism.** Android Studio renders a Compose preview by inflating a bridge view, `androidx.compose.ui.tooling.ComposeViewAdapter`, and passing it the fully-qualified name of the composable, which it invokes reflectively at render time. Reflection is why **`private` preview functions render fine** — visibility is not a constraint on this path.

**XML fed to the render task:**

```xml
<androidx.compose.ui.tooling.ComposeViewAdapter
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    tools:composableName="com.example.feature.FooKt.BarPreview" />
```

**Call sequence (pseudocode — every symbol must be verified in Phase 0):**

```kotlin
val module        = ModuleUtilCore.findModuleForFile(virtualFile, project)
val facet         = AndroidFacet.getInstance(module)
val configuration = ConfigurationManager.getOrCreateInstance(module)
                        .getConfiguration(virtualFile)

val task = RenderService.getInstance(project)
    .taskBuilder(facet, configuration)
    .withXmlContent(composeViewAdapterXml(entry.composableFqn))
    .build()

try {
    val result: RenderResult = task.render().get(TIMEOUT_MS, MILLISECONDS)
    val image: BufferedImage = result.renderedImage
    // persist to disk cache, hand to panel
} finally {
    task.dispose()   // MANDATORY — layoutlib contexts leak otherwise
}
```

**Hard requirements:**

1. `task.dispose()` in a `finally` block. Skipping it leaks layoutlib render contexts and will degrade the IDE.
2. Never render on the EDT. Respect `RenderService`'s own threading contract.
3. PSI access requires a read action.
4. Apply a render timeout (suggested 30 s) and treat expiry as a failure, not a hang.
5. The module must be compiled — see §7.5.

**Multiplatform previews.** Whether `org.jetbrains.compose` previews in `commonMain` render through this path is **unknown** and is an explicit Phase 0 question. If they do not, the design-system module needs a separate decision (see §11, R2).

### 7.5 Build on demand

Live rendering requires the target module's classes on disk.

**Rules — all mandatory:**

| # | Rule | Rationale |
|---|---|---|
| B1 | Use the IDE's Gradle integration (`ExternalSystemUtil.runTask`, `GradleConstants.SYSTEM_ID`). **Never spawn a Gradle daemon directly.** | A second daemon costs gigabytes of RAM. This is the single largest performance risk. |
| B2 | Compile the minimum: `:path:to:module:compileDebugKotlin`, never `assembleDebug`. | Transitive deps are pulled in automatically; app packaging is not needed. |
| B3 | Selection must not trigger a build. Builds run only on explicit **Render**, or when outputs are already fresh. | Browsing the tree must stay free. |
| B4 | Debounce 400 ms; single-flight; cancel any in-flight build when the selection changes. | Arrow-keying through 20 nodes must not queue 20 builds. |
| B5 | Disabled while `DumbService.isDumb`. | Never compete with indexing. |
| B6 | Surface build progress in the standard IDE progress UI, cancellable. | Predictability. |

**Freshness heuristic.** Compare the newest source-file mtime in the module against the newest class-file mtime in its build output directory. Cheap and adequate; a wrong answer costs at most one redundant build or one `NEEDS_BUILD` prompt.

### 7.6 Caching

#### Memory tier

Raw `BufferedImage` is the memory-bloat risk:

| | Bytes |
|---|---|
| 1080 × 2340 ARGB (full-res) | ≈ 10.1 MB |
| 20 full-res images retained | ≈ 202 MB ❌ |
| 800 × 600 ARGB (display-scaled) | ≈ 1.9 MB |
| **3 display-scaled images retained** | **≈ 5.8 MB** ✅ |

**Policy:**

- Retain at most **3** decoded images (current + previous + next) for smooth keyboard navigation.
- Store **display-scaled** images in memory; full resolution lives on disk only and is decoded on demand for zoom/export.
- Clear the memory tier entirely when the tool window is hidden.
- Do **not** use `SoftReference` — GC timing is unpredictable. Use an explicit bounded LRU.

#### Disk tier

- Location: `PathManager.getSystemPath()/compose-preview-gallery/<projectHash>/`. Never inside the user's project directory.
- Format: PNG, full resolution.
- Sizing: ≈ 200 KB per image × ~900 previews ≈ **180 MB**.
- Eviction: LRU by access time, with a configurable cap (default 500 MB).
- Read + decode latency: ~10–30 ms — imperceptible.

#### Cache key

```
key = sha256(composableFqn + sourceFileContentHash + renderConfigHash)
```

Including the source content hash means editing a file invalidates its entry automatically. Stale images can never be shown as current.

### 7.7 API stability strategy

Two layers of protection around the internal APIs:

**1. Startup capability probe.** On first use, `LiveRenderer.isAvailable()` reflectively verifies that the required classes and method signatures exist. On mismatch: log once, disable live rendering for the session, fall through to the snapshot tier.

**2. Runtime guard.** Every internal call site catches both `Exception` and `LinkageError` (`NoSuchMethodError`, `NoClassDefFoundError`). A signature change on a newer IDE build therefore degrades one preview to the cached image instead of breaking the plugin.

**Rationale.** Compile against the target platform for clean, readable code; guard at runtime for forward tolerance. Worst case after an IDE upgrade: the gallery keeps serving cached images while the renderer is repaired.

**Do not** pin `untilBuild` aggressively as the sole mitigation — that only converts a soft failure into a plugin that refuses to load.

---

## 8. Data model

```kotlin
enum class AnnotationKind { ANDROIDX, JETBRAINS }

data class PreviewEntry(
    val id: String,                 // stable identity: "$composableFqn#$previewName"
    val displayName: String,        // @Preview(name=...) if present, else functionName
    val functionName: String,
    val packageName: String,
    val jvmClassName: String,       // e.g. "FooKt"
    val composableFqn: String,      // e.g. "com.example.FooKt.BarPreview"
    val moduleName: String,
    val filePath: String,
    val lineNumber: Int,
    val annotationKind: AnnotationKind,
    val isPrivate: Boolean,         // informational only — not a render constraint
    val hasPreviewParameter: Boolean,
    val previewGroup: String?,      // @Preview(group=...)
)

data class RenderConfig(
    val deviceSpec: String,         // e.g. "spec:width=411dp,height=891dp"
    val themeName: String?,
    val uiMode: Int,
    val apiLevel: Int,
    val fontScale: Float = 1.0f,
)

sealed interface RenderOutcome {
    data class Success(val image: BufferedImage, val source: Source) : RenderOutcome
    data class NeedsBuild(val moduleName: String) : RenderOutcome
    data class Failure(val message: String, val detail: String?) : RenderOutcome
    data class Unsupported(val reason: String) : RenderOutcome

    enum class Source { LIVE, DISK_CACHE }
}
```

### 8.1 FQN derivation rules

| Declaration | JVM class | `composableFqn` |
|---|---|---|
| Top-level `fun BarPreview()` in `Foo.kt`, package `com.example` | `com.example.FooKt` | `com.example.FooKt.BarPreview` |
| File annotated `@file:JvmName("Custom")` | `com.example.Custom` | `com.example.Custom.BarPreview` |
| `fun` inside an `object Previews` | `com.example.Previews` | `com.example.Previews.BarPreview` |
| `fun` inside a class | — | Not supported in v1; mark `Unsupported` |

Derive these from PSI at index time, not by string manipulation at render time.

---

## 9. Phases

| Phase | Scope | Estimate | Gated by |
|---|---|---|---|
| **0** | **Spike — verify the rendering APIs** | **2–3 days** | — |
| 1 | Index + tree + search + split panel + open-file | ~1 week | none (independent of rendering) |
| 2 | `LiveRenderer` + disk/memory cache + build-on-demand | ~1.5–2 weeks | Phase 0 |
| 3 | *(optional)* Pre-populate the disk cache in CI | ~1.5 weeks | independent |

**Phase 1 carries no rendering risk** and delivers standalone value: project-wide preview discovery with navigation. It is the floor of the project — it ships even if every rendering approach fails.

### 9.1 Phase 3 (optional) — pre-populated cache

Renders every preview to PNG in CI and publishes the result as a cache artifact, so a freshly cloned repository shows images without building anything locally.

Candidate tooling:

- **Roborazzi** — screenshot library running Compose UI on the JVM via Robolectric.
- **ComposablePreviewScanner** — scans the classpath for `@Preview`-annotated functions and returns them as a list, so a single test renders all previews instead of one test per component. Essential at this corpus size.
- **Paparazzi** — layoutlib-based alternative to Roborazzi; highest fidelity to the IDE preview, also combinable with the scanner.

Known constraint: these scanners target the **androidx** annotation. Multiplatform (`org.jetbrains`) previews are out of scope for them and would need custom handling.

Only worth building if "view previews without ever building locally" proves to be a real requirement.

---

## 10. Phase 0 — spike (the gate)

**Deliverable:** a throwaway plugin that renders one hard-coded composable FQN to a PNG, plus a written findings report.

### Day 1 — Can a third-party plugin reach the renderer?

- Create a minimal plugin targeting platform 253 with `<depends>org.jetbrains.android</depends>`.
- Confirm `RenderService`, `RenderResult`, `ConfigurationManager`, `AndroidFacet` are resolvable and record their **actual** signatures in Panda 4.
- Render one **public** `androidx` preview from a pre-built module → PNG on disk.

### Day 2 — Coverage and behaviour

- Render a **private** `androidx` preview. *(Expected to work; verify.)*
- Render from a module that is **not** built → observe and document the failure mode.
- Render a preview using `@PreviewParameter` → document the behaviour.
- Measure: cold render latency, warm render latency, memory delta after 20 sequential renders with `dispose()`.

### Day 3 — Multiplatform

- Attempt a `commonMain` preview using `org.jetbrains.compose.ui.tooling.preview.Preview`.
- If it fails, determine whether the module's `androidMain` source set exposes an androidx-annotated equivalent.
- Record the outcome and the implication for the design-system module.

### Success criteria

| # | Question | Blocking? |
|---|---|---|
| S1 | `RenderService` reachable from a third-party plugin on Panda 4? | **Yes** — no → live rendering is off the table |
| S2 | Public androidx preview renders to `BufferedImage`? | **Yes** |
| S3 | Private preview renders? | No — informational, expected pass |
| S4 | 20 sequential renders leak no significant memory? | **Yes** |
| S5 | `commonMain` / `org.jetbrains` previews render? | No — scopes the design-system module |
| S6 | Cold render latency acceptable (< ~5 s on a built module)? | No — informational |

**Gate.** S1, S2 and S4 must pass for Phase 2 to proceed as specified. If S1 fails, fall back to Phase 3 tooling as the primary renderer and reduce Phase 2 to a snapshot-only pipeline.

---

## 11. Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Internal render APIs change on IDE upgrade | High over time | Medium | §7.7 probe + `LinkageError` guard; all coupling in one class; cached images keep working |
| R2 | Multiplatform (`org.jetbrains`) previews do not render | Unknown | High — affects the design system | Phase 0 Day 3; fallbacks: androidMain equivalents, or an on-device gallery for that module |
| R3 | Second Gradle daemon spawned | Low if B1 is honoured | **Severe** — GBs of RAM | Mandatory rule B1; assert in code review |
| R4 | Memory bloat from retained images | Low with §7.6 | Medium | Bounded LRU of 3 display-scaled images; clear on hide |
| R5 | layoutlib contexts leak | Medium | High | `dispose()` in `finally`; Phase 0 S4 measures it |
| R6 | Build-on-demand degrades IDE responsiveness | Medium | Medium | Rules B3–B6: build only on explicit action |
| R7 | Previews with runtime dependencies (DI, network images) fail | Medium | Low | Per-preview failure is isolated; show the error and offer Open file |
| R8 | `@PreviewParameter` previews cannot be invoked | Certain for some | Low | Mark `Unsupported`; small share of the corpus |

### 11.1 Guaranteed fallback

If **every** IDE-side rendering approach fails, one option is known to work unconditionally: generate a preview registry with KSP, embed a gallery screen in a debug build, drive an emulator, and capture screenshots via `adb`. The composable runs inside the real app, so nothing can fail to render for environmental reasons. Heavier to build and slower to run — held in reserve, not planned.

---

## 12. Reference corpus

Measured on `hepsi-android` — use for sizing, not as a hard requirement.

| Metric | Value |
|---|---|
| Gradle modules | 75 |
| Feature modules | 29 |
| `@Preview` annotations | 913 |
| — `androidx.compose.ui.tooling.preview.Preview` | 434 files |
| — `org.jetbrains.compose.ui.tooling.preview.Preview` | 48 files (design-system module, `commonMain`) |
| `@PreviewParameter` usages | 31 |
| `private` preview functions | ≈ 394 of ≈ 866 (~45%) |
| Files containing `@Composable` | 1053 |
| Files containing `@Preview` | 484 |
| Custom preview wrappers | `PreviewComponent`, `PreviewComponentBasket`, `PreviewComponentCreditCards`, `PrimusThemePreview` |

Notable: the design-system module is fully multiplatform (`androidMain`, `commonMain`, `desktopMain`, `iosMain`) and its previews are `private` and use the JetBrains annotation — the single hardest combination in the corpus, and also the highest-value content for a gallery. This is why R2 is a Phase 0 gate.

---

## 13. Open questions

1. Should device/theme selectors ship in v1, or is a single fixed render configuration sufficient?
2. Should multipreview annotations (custom annotations meta-annotated with `@Preview`) expand into one tree entry per underlying preview, or collapse into one?
3. Is a preview-coverage report (composables with no `@Preview`) in scope? The index makes it nearly free.
4. Should the gallery be filterable to a single module, tied to the current editor context?
5. Does the disk cache need to be shareable between developers, or is it strictly local?

---

## Appendix A — APIs to verify in Phase 0

Record the actual package, class and signature for each on Android Studio Panda 4 (platform 253). None of these are public API; all are subject to change.

| Symbol | Expected role |
|---|---|
| `RenderService` | Entry point; obtains a task builder |
| `RenderService.taskBuilder(...)` | Builds a render task from facet + configuration |
| `RenderTask.render()` | Executes the render, returns a future |
| `RenderTask.dispose()` | Releases the layoutlib context |
| `RenderResult.renderedImage` | The produced image |
| `ConfigurationManager.getOrCreateInstance(module)` | Device / theme / API configuration |
| `AndroidFacet.getInstance(module)` | Android module facet |
| `androidx.compose.ui.tooling.ComposeViewAdapter` | Bridge view; `tools:composableName` attribute |
| `ExternalSystemUtil.runTask(...)` | Gradle task execution through the IDE (public-ish) |

## Appendix B — References

- IntelliJ Platform SDK — Tool Windows, `FileBasedIndex`, `OnePixelSplitter`
- Android Studio source (AOSP `tools/adt/idea`) — authoritative reference for the rendering stack
- Roborazzi — JVM screenshot testing via Robolectric
- ComposablePreviewScanner — classpath scanning for `@Preview` functions
- Paparazzi — layoutlib-based screenshot testing
