# Live rendering pipeline

**Status:** shipped · **Phases:** PG2, PG3 (partial), PG4 (partial), PG7 (design only), PG11-1 (code label), PG12 (partial), PG24 (partial) · **Last change:** 8667333 [PG24-10] 2026-09-14

Turns a selected `@Preview` into an image by driving Android Studio's own layoutlib render services from inside
the plugin — building a stale module first when needed, applying each preview's own device/API/size/showSystemUi
configuration, and handling Kotlin Multiplatform and `@PreviewParameter` previews. All coupling to Android
Studio's internal render API is isolated to a handful of `render/` classes, each guarded so a newer IDE degrades
one feature instead of crashing the plugin. A further performance rework (skip a build Android Studio already
knows is unnecessary, keep a warm `RenderTask`, compile in-process) was fully designed in PG7 but never
implemented — every render still starts cold (see Open items).

## What the user gets

- Selecting a preview in the tree renders it automatically — no button. A stale module builds first,
  automatically; the **Render** button appears only as a manual retry after a failure.
- Render states shown in the panel: `IDLE` (nothing selected), `RENDERING` ("Rendering…", or "Building module…"
  while a build is in flight), `LIVE` (the image), `FAILED` (message + **Render** retry + **Open file** +
  expandable detail), `UNSUPPORTED` (reason + **Open file**). (`REFERENCE`/`NO_REFERENCE` belong to a snapshot
  row, which never renders at all — see the snapshot-verify doc.)
- Each preview renders at its own `@Preview(device=, apiLevel=, widthDp=/heightDp=, showSystemUi=)` — the same
  device Android Studio's editor preview falls back to when none is named — instead of one fixed configuration.
- A `@Preview` in a Kotlin Multiplatform `commonMain` source set renders against its Android target,
  transparently — no separate action needed.
- A `@PreviewParameter` preview renders once per value the provider yields (up to 16), shown as a stacked strip;
  one failing value does not blank the rest.
- Every render failure is also written to `idea.log` with the same summary and detail the panel's Details area
  shows, so a report can be diagnosed without reproducing it live.

## How it works

1. Selection reaches `RenderPipeline.select(entry)` (debounced 400 ms via a Swing `Alarm`; a `generation` counter
   makes a later selection or build silently supersede an earlier one instead of cancelling it outright).
2. `dispatch` classifies the entry — `unsupportedReason` wins outright; otherwise `ModuleFreshness.isModuleFresh`
   (a bounded, TTL-cached mtime comparison, most of it off any read action) decides `RENDERING` (render now) or
   `NEEDS_BUILD` (`buildThenRender`, PG3-5). `RenderPipeline.classify` is the pure form of this decision.
3. `buildThenRender` calls `BuildService.build(module)`, which compiles through the IDE's own Gradle integration
   (`ExternalSystemUtil.runTask`, never a second daemon), single-flighted and `DumbService`-gated. On success it
   invalidates `ModuleFreshness`'s cache for that module and renders; on failure it publishes `FAILED`.
4. `render` submits straight to a background executor — no outer read action (PG3-6) — and calls
   `LiveRenderer.render(entry, override, moduleWrapper)`.
5. `RenderModelResolver.resolve` turns the entry into a `(RenderModelModule, Configuration, RenderLogger,
   instances)` tuple: `AndroidModuleResolver` hops from a KMP `commonMain` module to its Android target's facet
   first (PG11-1); Android Studio's own `AnnotationFilePreviewElementFinder` supplies the config-aware preview
   element (device/api/size/showSystemUi) *lock-free*, before any read action opens, because the finder is a
   `suspend` function whose own read access would otherwise starve the EDT under an outer read lock (a 65 s
   freeze was observed) — `RenderTaskContext` gives that suspend bridge the cancellable context it needs
   (PG12-6). A `@PreviewParameter` composable resolves to one `Instance` per value its provider yields, capped at
   `MAX_PARAMETER_INSTANCES` (16).
6. `LiveRenderer` builds one `RenderTask` per instance and drives Android Studio's own compose render sequence —
   `inflate()` → `render()` (thrown away) → drain Compose's frame callbacks → `render()` again — because a single
   inflate+render captures Compose before it has ever composed (the PG2-2 blank-frame fix). A single instance
   yields `RenderOutcome.Success`; more than one yields `MultiSuccess`, combined so one failing value does not
   fail the whole set. The raw `ViewInfo` tree is also converted to a plugin-owned `PreviewViewNode` tree and the
   render's own pixel density is read off the task's `HardwareConfig` (falling back to `Configuration`, then a
   160 dpi identity default) — both carried on the outcome for the render-view doc's hover/click and dp-accurate
   zoom to consume.
7. The outcome is published back via `invokeLater` at the modality captured before the executor hop, discarded if
   the pipeline's `generation` moved on or the panel was disposed meanwhile. `PreviewRenderPanel.show` maps the
   published `RenderResultView` to what is drawn.

`LiveRenderer.renderVariant` and `RenderPipeline.renderVariant` are separate, non-debounced entry points into this
same machinery: one pins a named `@Preview` instance to a fixed calibration device+theme for the screenshot-test
comparison (`pinCalibrationDevice`, PG22 — snapshot-verify doc), the other renders a comparison-view tab with a
`ViewOverride` property map (PG6 — render-view doc). A selected snapshot row bypasses the pipeline entirely
(`pipeline.select(null)`) and is shown from committed reference PNGs instead (`REFERENCE`/`NO_REFERENCE` —
snapshot-verify doc).

| Class / file | Responsibility |
|---|---|
| [LiveRenderer](../../../src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt) | FQN → `BufferedImage` via layoutlib; the inflate/render/drain sequence, blank-frame detection, failure/detail logging, density read, `ViewInfo` → `PreviewViewNode` conversion |
| [RenderModelResolver](../../../src/main/kotlin/com/devomer/previewgallery/render/RenderModelResolver.kt) | Builds the `RenderModelModule`/`Configuration`/`RenderLogger`/instance list; config-aware element lookup; `@PreviewParameter` instance resolution; KMP facet hop |
| [RenderPipeline](../../../src/main/kotlin/com/devomer/previewgallery/render/RenderPipeline.kt) | Selection → classify → build-then-render orchestration; debounce, generation-based cancellation, EDT publish |
| [RenderState](../../../src/main/kotlin/com/devomer/previewgallery/render/RenderState.kt) | The eight states the render panel can show |
| [RenderTaskContext](../../../src/main/kotlin/com/devomer/previewgallery/render/RenderTaskContext.kt) | Installs a cancellable context for the config-aware finder's `suspend` bridge |
| [BuildService](../../../src/main/kotlin/com/devomer/previewgallery/render/BuildService.kt) | Builds one module on demand through the IDE's Gradle integration; resolves which task(s) to run |
| [ModuleFreshness](../../../src/main/kotlin/com/devomer/previewgallery/render/ModuleFreshness.kt) | Source-vs-class mtime staleness check, bounded and cached; also the module's own "source clock" for snapshot staleness |
| [AndroidModuleResolver](../../../src/main/kotlin/com/devomer/previewgallery/render/AndroidModuleResolver.kt) | `Module` → `AndroidFacet`, including the KMP `commonMain` → Android-target hop (PG11-1) |
| [RenderApiProbe](../../../src/main/kotlin/com/devomer/previewgallery/render/RenderApiProbe.kt) | Reflective presence checks, one boolean per capability, gating every AS-internal feature independently |
| [RenderedImageInspector](../../../src/main/kotlin/com/devomer/previewgallery/render/RenderedImageInspector.kt) | Pure pixel check: is this frame degenerate or flat (nothing was actually drawn)? |
| [PreviewAnnotationLocator](../../../src/main/kotlin/com/devomer/previewgallery/render/PreviewAnnotationLocator.kt) | Re-resolves the PSI `@Preview` annotation an entry was indexed from. Only consumer today is the picker (`PreviewPickerBridge`) — picker doc |
| [RenderOutcome](../../../src/main/kotlin/com/devomer/previewgallery/model/RenderOutcome.kt) | `Success`/`MultiSuccess`/`Failure`/`Unsupported` — the plugin-owned render result, carrying the image, view tree and dpi |
| [RenderConfig](../../../src/main/kotlin/com/devomer/previewgallery/model/RenderConfig.kt) | Dead: the MVP's fixed-default config, superseded by config-aware render (see Open items) |
| [PreviewRenderPanel](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt) (partial) | `show(RenderResultView, entry)` maps each `RenderState` to what is drawn; the zoom/hover/comparison-tab UI it also contains belongs to the render-view doc |
| [PreviewGalleryPanel](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt) (partial) | Constructs the `RenderPipeline` and wires selection/Render-button/picker-refresh into it |

## Key decisions

- Render through the low-level `org.jetbrains.android` path (`StudioRenderService` + `RenderTask`), not the
  high-level `ComposeRenderer` — avoids a fragile optional dependency on the Gemini AI plugin. — spec D2
  (phase2-mvp-design.md)
- Two render passes with a Compose callback drain in between, mirroring Android Studio's own
  `updatePreviewsAndRefresh`/`LayoutlibSceneRenderer` sequence exactly — a single inflate+render captures Compose
  before it has ever composed, producing a blank frame. — `a25f6cb [PG2-2]`
- Selection auto-builds a stale module (400 ms debounce, in-flight cancellation); **Render** is only a retry
  after `FAILED` — the original manual-button MVP proved too much friction. — spec D3/B3 revision,
  `d10545d [PG2-0]`, `7afaa4e [PG2-11]`
- No render cache anywhere in this area: explicitly deferred for the MVP, and PG7's later warm-`RenderTask`
  alternative was designed but never implemented — every render is still live from scratch. — spec D5
  (phase2-mvp-design.md); render-performance-design.md D4 (unimplemented)
- Config-aware render reuses Android Studio's own `AnnotationFilePreviewElementFinder` +
  `ConfigurablePreviewElement.applyTo` instead of hand-parsing `@Preview` arguments — matches AS's own behaviour
  exactly, with far less code. — spec D1 (config-aware-render design); `e568749 [PG4-2]`
- The config-aware finder's `suspend` call runs lock-free, before any read action opens — running it under an
  already-held read lock starves the EDT's write-intent request and freezes the IDE (a 65 s freeze was observed).
  — `be75b2f [PG4-2]`
- The render itself runs on a plain background executor with no outer read action, trading away
  `ReadAction.nonBlocking`'s built-in cancellation for a generation-counter + disposal check applied only when
  publishing the result. — `ef71a24 [PG3-6]`
- `ModuleFreshness` takes one short read action for project-model data only; the mtime walk itself holds no lock
  and is bounded to 8 directory levels rather than the whole output tree — an accepted approximation ("a wrong
  answer costs at most one redundant build"). — `8a713ba [PG3-5]`; spec §6 (phase2-mvp-design.md)
- A KMP `commonMain` `@Preview` resolves through `Module.findAndroidModule()` to its Android target's facet,
  mirroring Android Studio's own path, instead of `AndroidFacet.getInstance(module)`, which has no facet at all
  for a common source set. — code-labelled PG11-1, shipped in `545eecb`/`4736f87`
- `BuildService` asks Android Studio's own `GradleTaskFinder` (`BuildMode.COMPILE_JAVA`) which tasks compile a
  module instead of guessing a task name — AS sync deliberately never populates a module's Gradle task list
  (`SKIP_GRADLE_TASKS_LIST = isAndroidStudio()`), so the guess always fell back to `compileDebugKotlin` and broke
  on Kotlin Multiplatform modules. — `834225e [PG12-7]`, `ca31fdc [PG4-BUILDFIX]`
- A `@PreviewParameter` composable renders once per provider value (its own `RenderTask` each, capped at 16)
  instead of being refused outright; a failing value costs only that value. — `4615321 [PG24-2]`
- Every render failure is logged, not just shown, and a layoutlib `RenderProblem` is flattened from `getHtml()`/
  `getThrowable()` instead of relying on `toString()`, which prints only an identity hash. — `ede9848 [PG24-4]`,
  `9ded3fd [PG24-5]`
- Build (and render) completion is always delivered on the EDT via an unconditional `invokeLater` — a Gradle
  task-notification thread calling straight into Swing once threw an EDT-only assertion and stranded the panel on
  "Rendering…" forever. — `5045774 [PG24-6]`
- Every AS-internal call site is guarded by a capability probe (one boolean per feature) plus a per-call
  `Exception`/`LinkageError` catch, with `ProcessCanceledException` always re-thrown — a signature change on a
  newer IDE degrades one feature or one render, never the whole plugin. — spec §5.3/§7.7 (phase2-mvp-design.md,
  parent spec)

## Android Studio and platform internals relied on

- **Capability probe** (`RenderApiProbe`): seven independent reflective checks — `isAvailable` (core render),
  `isConfigAwareAvailable` (`AnnotationFilePreviewElementFinder`), `isViewTreeAvailable`
  (`ComposeViewInfoParserKt` — render-view doc), `isAndroidModuleWalkAvailable` (`Module.findAndroidModule`),
  `isCompileTaskFinderAvailable` (`GradleTaskFinder`), plus `isPickerAvailable`/`isViewOverrideAvailable` (picker
  doc). `RenderApiProbeTest` asserts every one of these actually exists on the IDE the plugin compiles against —
  the guard that catches a wrong member name at test time rather than degrading silently, added after PG24-10
  showed a typo would otherwise compile clean and disable rendering entirely.
- **Threading:** never render on the EDT; a read action is taken only for the short project-model slice each
  class needs, never around the render itself or a filesystem scan; the config-aware finder's `suspend` bridge
  must run before any read action opens and needs a hand-installed `EmptyProgressIndicator`
  (`RenderTaskContext`) because a plain Swing-to-executor hop carries neither a `Job` nor a `ProgressIndicator` —
  the platform reports that as an internal-error log line rather than throwing, so no `catch` in this plugin ever
  saw it before PG12-6. Every render future is capped at a 30 s timeout.
- **The exact AS render call sequence is mirrored**, not guessed: `inflate()` → `render()` (thrown away) → drain
  Compose's frame callbacks (a `SteppingSessionClock`, 16 ms/step, capped at 100 ms wall-clock / 16 rounds) →
  `render()` again; and the decorations/rendering-mode branch (`disableDecorations()` + `SHRINK` vs. leaving AS's
  own defaults for `showSystemUi`) — both reverse-engineered from Android Studio's own `design-tools.jar`
  bytecode (see `LiveRenderer`'s class doc for the exact method names read).
- **Density** comes from the render task's own `HardwareConfigHelper` (`RenderTask.hardwareConfigHelper.config
  .density.dpiValue`), falling back to `Configuration.density`, then to a 160 dpi identity default — verified
  against `RenderTask` bytecode, not assumed.
- **Render classpath seam:** `RenderModelModule` is an interface and `AndroidFacetRenderModelModule` merely
  implements it, so `RenderModelResolver`/`LiveRenderer` accept an optional `RenderModuleWrapper` that can
  intercept `getClassLoaderProvider` before a `RenderTask` is built — verified reachable, and everything below it
  public, by [the render-classloader spike](../../superpowers/specs/2026-08-10-render-classloader-spike.md). Only
  the screenshot-test calibration path (snapshot-verify doc) uses it today.
- **Compatibility:** Android Studio auto-updated itself from Panda 4 (`AI-253`) to `2026.1.3` (`AI-261`) on
  2026-08-31 — `platformLocalPath` in `gradle.properties` points at the live install, so the plugin compiles
  against whatever is currently installed. `RenderResult.processImageIfNotDisposed` and `RenderTask.dispose()`
  disappeared on 261; `8667333 [PG24-10]` moved to `getRenderedImage()` and `disposeAsync()` and fixed
  `RenderApiProbe`'s reflective member names to match. `sinceBuild` is still `"253"`
  ([build.gradle.kts:38](../../../build.gradle.kts)), but Panda 4 has no `getRenderedImage()` — see Open items.

## Tests

- [LiveRendererInstanceSetTest](../../../src/test/kotlin/com/devomer/previewgallery/render/LiveRendererInstanceSetTest.kt) — `combineInstanceRenders` (a `@PreviewParameter` set's success/failure combination rules) and `flattenHtml` (layoutlib problem HTML → one readable line), both pulled into `LiveRenderer`'s companion so they are testable without layoutlib
- [RenderModelResolverTest](../../../src/test/kotlin/com/devomer/previewgallery/render/RenderModelResolverTest.kt) — `decideVariantResolution`/`decideDevicePin`, the pure decision tables behind the screenshot-test calibration's variant-name and device-pin resolution (PG22 — the calibration feature itself is the snapshot-verify doc's)
- [RenderPipelineTest](../../../src/test/kotlin/com/devomer/previewgallery/render/RenderPipelineTest.kt) — `RenderPipeline.classify`, the pure unsupported/fresh/stale → state decision
- [RenderTaskContextTest](../../../src/test/kotlin/com/devomer/previewgallery/render/RenderTaskContextTest.kt) — the platform's "no ProgressIndicator or Job" report and `runCancellable`'s fix, including that a caller-supplied indicator is preserved rather than shadowed
- [ModuleFreshnessTest](../../../src/test/kotlin/com/devomer/previewgallery/render/ModuleFreshnessTest.kt) — the pure `isFresh`/bounded-scan rules: depth cap, a missing directory, source-vs-build-dir exclusion
- [ModuleFreshnessModuleTest](../../../src/test/kotlin/com/devomer/previewgallery/render/ModuleFreshnessModuleTest.kt) — `isModuleFresh`/`newestModuleSourceMtime`/`cachedModuleSourceMtime` against a real on-disk module (mtime needs a real file, not the in-memory fixture FS), including the TTL-serves-the-expired-value-not-unknown behaviour
- [BuildServiceCompileTaskTest](../../../src/test/kotlin/com/devomer/previewgallery/render/BuildServiceCompileTaskTest.kt) — `compileTargetOf`/`chooseCompileTaskName`, the pure "which Gradle task compiles this module" candidate priority (AGP / KMP target / plain JVM)
- [BuildServiceCompileTargetTest](../../../src/test/kotlin/com/devomer/previewgallery/render/BuildServiceCompileTargetTest.kt) — the compile-task-finder capability exists on the compiled-against IDE; a non-Gradle module's build fails through the callback, on the EDT, instead of throwing
- [AndroidModuleResolverTest](../../../src/test/kotlin/com/devomer/previewgallery/render/AndroidModuleResolverTest.kt) — the contract half (`findAndroidModule` exists on the compiled-against IDE; a non-Android module resolves to `null`, not a throw); the real KMP `commonMain` → `androidMain` walk needs a synced KMP project and is verified only manually, at the `runIde` gate against `hepsi-android/primus`
- [RenderApiProbeTest](../../../src/test/kotlin/com/devomer/previewgallery/render/RenderApiProbeTest.kt) — every capability the probe names by reflection actually exists on the IDE the plugin compiles against
- [RenderedImageInspectorTest](../../../src/test/kotlin/com/devomer/previewgallery/render/RenderedImageInspectorTest.kt) — `isBlank`'s pixel-uniformity rule: transparent, single-colour and degenerate-size frames are blank; a single painted pixel is not

**Notable gaps:** `RenderPipeline`'s asynchronous half — `dispatch`/`buildThenRender`/`render`/`rerenderCurrent`,
the debounce, the generation/disposal cancellation, the EDT hops — has no automated coverage at all; only the
pure `classify` function is unit-tested (the project has no mocking framework set up). `LiveRenderer`'s actual
layoutlib calls (`inflate`/`render`/callback-drain) and `RenderModelResolver`'s config-aware/KMP resolution are
likewise verified only manually at `runIde` gates, per the project's standing posture that AS-internal calls
"cannot be unit-tested without a full IDE + a real Android module + the SDK" (phase2-mvp-design.md §10).

## Open items

- [gap] PG7's render-performance rework — skip a build when Android Studio's own build state already says the
  output is usable, reuse a warm `RenderTask`/class loader instead of one per render, compile in-process via Fast
  Preview — was fully designed (`RenderPlanner`/`BuildStateProbe`/`RenderTaskCache`/`FastCompileBridge`, D1-D9)
  but never implemented: no PG7-1+ commit exists and none of those classes are in the codebase. Every render
  still builds and disposes a fresh `RenderTask`/`RenderModelModule`/`Configuration`. — [render-performance-design.md](../../superpowers/specs/2026-07-27-render-performance-design.md)
- [bug] `sinceBuild` is still `"253"` ([build.gradle.kts:38](../../../build.gradle.kts)) but PG24-10's probe
  requires `RenderResult.getRenderedImage()`/`RenderTask.disposeAsync()`, which Panda 4 (253) does not have — so a
  user on the plugin's own declared minimum IDE now gets "Live rendering is unavailable on this IDE build"
  instead of a working renderer. Left open in the commit message (raise `sinceBuild`, or branch the probe/call
  sites per API generation). — `8667333 [PG24-10]`
- [gap] No render output cache (disk or memory) as the parent spec's §7.6 originally envisioned — every render is
  live; explicitly out of scope for the MVP and never revisited (the PG7 alternative above didn't land either).
  — [compose-preview-gallery-plugin-spec.md](../../../compose-preview-gallery-plugin-spec.md) §7.6; phase2-mvp-design.md D5/G1
- [debt] `RenderConfig` ([model/RenderConfig.kt](../../../src/main/kotlin/com/devomer/previewgallery/model/RenderConfig.kt)) is dead code: the MVP's fixed-default config
  (`1fe403b [PG2-3]`) was superseded by the config-aware `PreviewConfiguration` path (`PG4-2`) and by
  `ViewOverride`, but the file was never deleted and nothing imports it any more.
- [debt] `RenderPipeline`'s async orchestration has no automated test coverage (see Tests — Notable gaps); a
  regression there would only surface at a manual `runIde` gate or in the field.
- [limitation] A component that cannot render on its own (for example, one needing a theme wrapper its `@Preview`
  does not supply) fails here for the same reason it fails in Android Studio's own editor preview — the plugin
  can only report layoutlib's own diagnosis, not fix the composable. — [README.md](../../../README.md) "Known limitations"
- [limitation] `@PreviewParameter` values are capped at `MAX_PARAMETER_INSTANCES = 16` per composable; a provider
  yielding more has the extras silently dropped (logged at info). — `RenderModelResolver.kt`, `4615321 [PG24-2]`
- [limitation] Non-androidx (`org.jetbrains.compose`) multiplatform previews are attempted like any other and
  shown as `FAILED` on error, but proper multiplatform preview support beyond "attempt it, observe the failure"
  was explicitly out of scope for the MVP and no later phase in this area revisited it (no branch on
  `AnnotationKind` exists anywhere in `render/`). — phase2-mvp-design.md D7/R5/G3

## History

| Phase | Dates | Commits | What changed |
|---|---|---|---|
| PG2 | 2026-07-24 | `e4a6db5` `a25f6cb` `1fe403b` `53f2dd1` `2c93a91` `4ef80ff` `7f6ce80` `41fbdd2` `d10545d` `4db8af5` `7afaa4e` | Phase 2 MVP: capability probe, the `LiveRenderer`/`RenderModelResolver` vertical slice, the render config/state/outcome model, `ModuleFreshness`, `BuildService`, `RenderPipeline`, `PreviewRenderPanel`, wired into the tool window; revised so selection auto-builds a stale module instead of waiting for a button |
| PG3 (partial) | 2026-07-24 | `8a713ba` `ef71a24` | Took `ModuleFreshness`'s scan and the render call off the read lock / read action to stop IDE-wide freezes |
| PG4 (partial) | 2026-07-25 – 2026-07-26 | `e568749` `be75b2f` `ca31fdc` | Config-aware render via `AnnotationFilePreviewElementFinder`; ran the finder off the read lock; fixed the Kotlin Multiplatform/JVM compile-task name fallback |
| PG7 | 2026-07-27 | `fa838df` | Render-performance design only (build-state probe, warm render-task cache, in-process Fast Preview compile) — never implemented, see Open items |
| PG11-1 (code label; shipped under `[PG12-0]`/unlabeled) | 2026-07-29 | `545eecb` `4736f87` | `AndroidModuleResolver` created and wired into `RenderModelResolver`/`RenderApiProbe`, so a KMP `commonMain` preview resolves to its Android target's facet; `RenderOutcome.Success` gained `dpi` (PG12-2) the same day |
| PG12 (partial) | 2026-07-30 | `0220352` `834225e` | Gave the config-aware finder's suspend bridge a cancellable context; asked Android Studio which Gradle tasks compile a module instead of guessing |
| PG24 (partial) | 2026-08-20 – 2026-09-14 | `4615321` `f22d222` `ede9848` `9ded3fd` `5045774` `8667333` | `@PreviewParameter` renders as a set instead of being refused; falls back to the same default device Android Studio's editor uses; render failures fully logged with readable layoutlib detail; build results always delivered on the EDT; rebuilt against Android Studio 2026.1.3's render API (`getRenderedImage`/`disposeAsync`) |

## References

- [Phase 2 MVP design](../../superpowers/specs/2026-07-23-preview-gallery-phase2-mvp-design.md) and [plan](../../superpowers/plans/2026-07-23-preview-gallery-phase2-mvp.md)
- [Config-aware render & interactive layers design](../../superpowers/specs/2026-07-25-config-aware-render-and-interactive-layers-design.md) (render half — Feature A) and [plan](../../superpowers/plans/2026-07-25-config-aware-render-and-interactive-layers.md)
- [Render performance design](../../superpowers/specs/2026-07-27-render-performance-design.md) — unimplemented, see Open items
- [Render classloader spike](../../superpowers/specs/2026-08-10-render-classloader-spike.md) — the render classpath injection seam
- [compose-preview-gallery-plugin-spec.md](../../../compose-preview-gallery-plugin-spec.md) — original parent spec (§7.4, §7.6, §7.7)
- [README.md](../../../README.md) "Known limitations"; [CHANGELOG.md](../../../CHANGELOG.md)
