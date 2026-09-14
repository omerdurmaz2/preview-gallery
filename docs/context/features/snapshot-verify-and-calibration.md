# Snapshot verify and the screenshotTest render calibration

**Status:** partial — snapshot verify shipped and hardened; the render calibration is production code with no
shipped feature on top of it yet · **Phases:** PG19 (spike halves only), PG20, PG21, PG22, PG23 (all except
PG23-1) · **Last change:** eddccb1 [PG22-19] 2026-08-20

Snapshot verify runs the project's own `validate<Variant>ScreenshotTest` Gradle task from inside the gallery and
shows, per snapshot, the golden, what Gradle rendered and the diff — an exact, Gradle-backed answer, shipped in
PG20, hardened in PG21, and made visible outside the exact selected row in PG23. The render calibration is a
separate, later question: whether the IDE's own layoutlib render of a `screenshotTest` composable agrees with
Gradle's closely enough to build a Gradle-free diff on top of it (roadmap F5's diff half). Two throwaway spikes
(PG19) proved the classloader injection is possible; PG22 turned that into a **Compare live render** button and
kept the code — but every real gate run through PG22-19 has stopped at "nothing measured" (a variant, device or
size mismatch), so no percentage has ever been produced, and the tolerance metric (D6b) needed to get past the
last size mismatch is specified but not coded.

## What the user gets

**Snapshot verify:**

- Selecting a snapshot row in the tree starts a verify of its module automatically, debounced 400 ms
  (`RenderPipeline.DEBOUNCE_MS`) so arrow-keying through a preview's snapshot children does not fire one Gradle
  run per row (spec D1). A **Verify snapshots** toolbar button — visible only when a snapshot row is selected —
  forces one immediately and cancels whatever run is already in flight (spec D2).
- A failing snapshot gets a red `differs` badge (plus `· stale` when the code has changed since), and since PG23
  that badge rolls up onto the *preview* row it covers even when the covering snapshot lives under a different
  branch of the tree — the case that motivated H3: a snapshot whose body calls a shared design-system composable
  directly is filed under that composable's own preview, not the one the user is looking at.
- The render pane shows the committed golden, what Gradle rendered and the difference image side by side for a
  failing snapshot; a passing one shows only the first two — no diff image exists, and an empty third slot would
  read as "no difference" instead of "nothing to show."
- A run that measured a failure also raises a warning notification (H3, PG23) naming up to three failing
  functions and their variants, for whoever is not already looking at the row.
- Every non-answer is its own sentence, never a silent or falsely clean result: "could not run" (indexing, or the
  module is not a linked Gradle project), "ran, found nothing" (compile failure, or a task name Gradle does not
  have), and "stale result from `<time>`" are all distinguishable from a fresh pass (spec D8, D4).

**Render calibration:**

- A second toolbar button, **Compare live render** — also visible only on a snapshot row — renders that
  `@PreviewTest` composable inside the IDE, with no Gradle involved, and reports how far it is from the committed
  golden and from Gradle's own last rendered PNG, as a percentage in the screenshot engine's own vocabulary
  (`0.111% different`).
- This is deliberately not presented as a finished feature anywhere else: not in `README.md`'s feature list, not
  in `CHANGELOG.md`, not in `docs/feature-overview.md`. It exists to produce the calibration's own number. Until
  that number is trusted, "no percentage, here is why" (a stale compile, no golden, a device or variant that
  could not be pinned, a size mismatch) is the expected answer, not a bug.

## How it works

### Snapshot verify

1. `PreviewGalleryPanel.routeSelection` recognises a `PreviewNode.SnapshotLeaf` and calls `startVerify`, which
   re-arms a 400 ms alarm (shared with the render/reference debounce) unless a forced request
   (`forcedVerifyPending`) already outranks it.
2. `verifyTarget()` resolves, on the EDT, the module, the build variant (the one `ReferenceRoots` already found a
   committed reference directory for — spec D6) and the module name to key the store on; any missing piece means
   nothing to verify.
3. `SnapshotVerifyRunner.verify` derives the task name (`validate<Variant>ScreenshotTest` — never read from the
   IDE's Gradle model, because the project is synced without
   `-Pandroid.experimental.enableScreenshotTest=true` and AGP's screenshot plugin is therefore absent from that
   model, spec D5) and runs it through `ExternalSystemUtil.runTask`, single-flight and cancellable exactly like
   [`BuildService`](../../../src/main/kotlin/com/devomer/previewgallery/render/BuildService.kt). A claimed
   `ExternalSystemTaskId` (confirmed against the submitted task names) is what stops an unrelated Gradle task —
   triggered by, say, the user's next preview selection — from being cancelled by mistake.
4. On completion, `SnapshotVerifyResults.readForRun` parses every `TEST-*.xml` under
   `build/test-results/<task>/` newer than the run's own start (spec D7), resolving `refImagePath`/`newImagePath`
   against the Gradle build root rather than the JVM's own working directory — falling back to an unfiltered read
   when the build succeeded but rewrote nothing, Gradle's own UP-TO-DATE proof that the results on disk already
   describe this run (PG21-8).
5. `SnapshotVerifyStore.record` keeps the last **measurement** (only written when a run actually produced
   results) apart from the last **attempt** (written on every run) — so an attempt that measured nothing, for
   any reason, can never erase a measurement that still stands (spec D4).
6. `showVerifyOutcome` repaints the tree (badges), re-routes the current selection through the same path a fresh
   selection takes, and raises the H3 notification if anything failed.

| Class / file | Responsibility |
|---|---|
| [`render/SnapshotVerifyRunner.kt`](../../../src/main/kotlin/com/devomer/previewgallery/render/SnapshotVerifyRunner.kt) | Runs `validate<Variant>ScreenshotTest` through the IDE's external-system integration; single-flight, cancellable, reports `RAN`/`BUILD_FAILED`/`NOT_RUN` |
| [`service/SnapshotVerifyResults.kt`](../../../src/main/kotlin/com/devomer/previewgallery/service/SnapshotVerifyResults.kt) | Pure JUnit-XML reader (no `com.intellij` import, spec D10); resolves image paths against the build root, applies the timestamp guard and the UP-TO-DATE fallback |
| [`service/SnapshotVerifyStore.kt`](../../../src/main/kotlin/com/devomer/previewgallery/service/SnapshotVerifyStore.kt) | Project-level: last measurement and last attempt per module, kept apart; module-scoped staleness (`isStale`) |
| [`service/VerifyFailureNotificationText.kt`](../../../src/main/kotlin/com/devomer/previewgallery/service/VerifyFailureNotificationText.kt) | H3: the balloon sentence for a run that measured a failure, `null` for a clean one |
| [`ui/VerifySnapshotsAction.kt`](../../../src/main/kotlin/com/devomer/previewgallery/ui/VerifySnapshotsAction.kt) | Toolbar action, hidden unless a snapshot row is selected |
| [`ui/PreviewGalleryPanel.kt`](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt) | `startVerify`/`runVerify`/`verifyTarget`/`verifyMessage`/`showVerifyOutcome`: debounce, staleness, message assembly, wiring to the store and the runner |
| [`ui/PreviewTreeCellRenderer.kt`](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRenderer.kt) | Snapshot-row `differs`/`stale` badge, and (`previewFailureBadge`, H3) its roll-up to the covering preview row |
| [`ui/PreviewRenderPanel.kt`](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt) | `showVerified`: golden/rendered/diff strip plus the always-visible outcome sentence |

### Render calibration

1. `CompareLiveRenderAction` (visible only on a snapshot row) calls `compareLiveRender`, which resolves — on the
   EDT — the module directory, every committed reference root that names a build variant, and the variant the
   IDE currently has selected (`AndroidModuleResolver.selectedVariantName`).
2. The reference root is chosen by matching that selected variant (`ScreenshotTestClasses.variantMatches`,
   PG22-19), not by taking whichever root sorts first: on a flavoured module, the injected `screenshotTest`
   classes must belong to the *same* flavour as the `main` classes the render resolves, or the two halves of one
   render come from two different flavours and the percentage measures two programs. No matching root refuses
   the press outright (`reportCompareVariantMismatch`).
3. Everything else runs off the EDT, in `compareOffEdt`. `ScreenshotTestClasses.stateOf` must read `Ready` — the
   module's compiled `screenshotTest` classes must exist and be newer than its newest source file (spec D5) —
   or the pane names the `validate…` task to run and nothing is rendered.
4. `ScreenshotTestClassLoader.wrapperFor` builds a `RenderModelModule` wrapper whose class loader tries the
   compiled `screenshotTest` classes directory first and falls back to the project's own class-file finder for
   every `main` class the composable touches (the theme, `R`, the composable under test itself) — layered over
   the ordinary loader, never in its place.
5. `LiveRenderer.renderVariant` calls `RenderModelResolver.resolve` with that wrapper, `requiredVariant = "phone"`
   and `pinCalibrationDevice = true`. The resolver selects the multipreview's `phone` `@Preview` instance by name
   (or, when `AnnotationFilePreviewElementFinder` cannot see the file's source set at all and returns nothing,
   assumes the plugin's default configuration and marks the render `variantAssumed` — spec D3a), then overwrites
   the device and theme onto the exact ones the Android screenshot engine itself renders under
   (`id:medium_phone`, `@android:style/Theme.Material.Light` — spec D6a).
6. `ImageDiff.compare` measures the live render against the decoded golden, and separately against the
   `rendered` PNG the module's last verify wrote, if any (spec D2 — the second number is what tells "the engines
   disagree" apart from "the golden is simply stale"). Both sides are composited onto opaque white first, so a
   transparent-backed live render and an opaque golden PNG do not register as 100% different on the alpha
   channel alone.
7. The pane publishes golden / Gradle's render / live render side by side with one summary line, or a named
   reason nothing was measured. A size mismatch is printed as two sizes, never coerced into a percentage.

| Class / file | Responsibility |
|---|---|
| [`render/ImageDiff.kt`](../../../src/main/kotlin/com/devomer/previewgallery/render/ImageDiff.kt) | Pure pixel metric: percentage of differing pixels after compositing onto white, or `SizeMismatch` when dimensions differ at all (spec D6, D9) |
| [`render/ScreenshotTestClasses.kt`](../../../src/main/kotlin/com/devomer/previewgallery/render/ScreenshotTestClasses.kt) | Pure: locates the compiled `screenshotTest` classes directory, decides `Ready`/`Missing`/`Stale` against a source mtime, and (`variantMatches`, PG22-19) whether a reference root's variant matches the IDE's selected one |
| [`render/ScreenshotTestClassLoader.kt`](../../../src/main/kotlin/com/devomer/previewgallery/render/ScreenshotTestClassLoader.kt) | AS-internal, guarded: subclasses `StudioModuleRenderContext` to inject the classes directory into the render classpath via `StudioModuleClassLoaderManager.getPrivate` |
| [`render/RenderModelResolver.kt`](../../../src/main/kotlin/com/devomer/previewgallery/render/RenderModelResolver.kt) | Calibration parts: `pinCalibrationDevice`/`applyCalibrationConfiguration` (device + theme pin), `decideVariantResolution`/`decideDevicePin` (pure decision tables), `CALIBRATION_DEVICE_SPEC`/`CALIBRATION_THEME` constants |
| [`render/LiveRenderer.kt`](../../../src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt) | `renderVariant`: the calibration's one entry point into the ordinary render path, with a named-variant + device-pin request |
| [`ui/CompareLiveRenderAction.kt`](../../../src/main/kotlin/com/devomer/previewgallery/ui/CompareLiveRenderAction.kt) | Toolbar action, hidden unless a snapshot row is selected |
| [`ui/PreviewGalleryPanel.kt`](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt) | `compareLiveRender`/`compareOffEdt`/`formatComparison`: EDT lookups, the off-EDT pipeline, and the published sentence |
| [`ui/PreviewRenderPanel.kt`](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt) | Reuses `showVerified` to publish the golden/Gradle/live strip — the calibration adds no UI state of its own |

## Key decisions

**Snapshot verify** (spec: [2026-08-10-snapshot-verify-design.md](../../superpowers/specs/2026-08-10-snapshot-verify-design.md)):

- D1/D2 — auto-verify on a snapshot row is debounced, and any new verify cancels the one in flight — one question
  at a time, and arrow-keying through a module's snapshots must not queue a run per row.
- D5 — the task name is derived, never read off the IDE's task list, because the project is synced without the
  screenshot-test experimental flag and AGP never registers the task in that model at all.
- D7 — an XML file older than the run's own start is ignored, so a human's own terminal `update` run is never
  shown as this verify's answer.
- D8 — "could not run" and "ran, found nothing" are different states, never both green — a green badge for a run
  that never happened is worse than none.
- D10 — the results reader imports no `com.intellij` class, keeping the door open for it to be served over MCP
  later without a rewrite.
- PG21-8, "the fifth thing" (roadmap H2) — an UP-TO-DATE Gradle run rewrites no XML, so D7's own staleness guard
  was discarding a still-correct answer on the *common* case (nothing changed since the last check). `readForRun`
  now falls back to an unfiltered read specifically when the build succeeded and nothing was rewritten.
- H3 (PG23) — the `differs` badge rolls up from a covering snapshot to the preview row that shows it, matched by
  the snapshot's *own* module, because a snapshot can cover a preview in a different module from itself; a
  finished failing run also raises a notification, since a verdict that only shows on one row of a 900-preview
  tree "measures the right thing and cannot be found," per the roadmap's own account of the bug this closed.

**Render calibration** (specs:
[2026-08-13-screenshottest-render-calibration-design.md](../../superpowers/specs/2026-08-13-screenshottest-render-calibration-design.md),
[2026-08-20-calibration-size-tolerance-design.md](../../superpowers/specs/2026-08-20-calibration-size-tolerance-design.md)):

- D1 — keep the code, not a spike: unlike PG19's two throwaway probes, this composition is what F5 needs either
  way, so rewriting it a second time would buy nothing.
- D2 — two comparisons (live vs. golden, live vs. Gradle's own last rendered PNG), because without the second one
  a bad first number cannot be attributed — a stale golden and a genuine engine disagreement look identical.
- D3/D3a — exactly one variant, `phone`, selected **by name**, never inferred from size. Amended at the gate:
  when `AnnotationFilePreviewElementFinder` returns no elements at all for the file (the project is synced
  without the experimental flag, so the finder has none of the plugin's own VFS fallback), the default
  configuration is rendered in `phone`'s place and the result is marked `variantAssumed` — safe only because the
  project's `phone` `@Preview` declares no properties of its own. A finder that *did* return instances, just none
  named `phone`, still stops (`VariantUnresolved`) — a genuine naming disagreement is never papered over.
- D5 — refuse rather than measure something stale: the compiled `screenshotTest` classes must be newer than the
  module's newest source file, using the newest `.class` file's mtime, not the directory's own (a directory
  timestamp does not move when a file is overwritten in place — the common recompile case).
- D6/D6a — the metric is the engine's own percentage of differing pixels, with exact size equality as a
  precondition. The device and theme are pinned to the *engine's own* defaults, found by reading
  `compose-preview-renderer`'s own jar rather than guessed: three iterations were needed — a dp spec (off by one
  pixel on rounding), a synthesized px spec (right pixel size, but `ScreenSize` computed `LARGE` instead of the
  catalogue's `normal`, invisible to a same-pixel-size check), and finally the catalogue device by id
  (`id:medium_phone`), plus the theme as a literal string (`Theme.Material.Light`, which alone accounted for 40%
  of one image's pixels — the module's own persisted theme, not the engine's, was being rendered).
- D6b — **specified, not implemented.** A two-band rule: refuse (as today) when either dimension differs by more
  than 2% of the golden's; below that, measure the top-left-anchored overlap and always print the size delta
  beside the percentage, never folding it in. Proposed after gate evidence that shrink-to-content snapshots can
  differ from their golden by one or two pixels even once the device and theme fully agree — see Open items.
- D8/D9 — every AS-internal call degrades to today's pane on `Exception`/`LinkageError` rather than breaking the
  render, and `ImageDiff` stays pure (no `com.intellij`/`com.android` import) so it needs no fixture to test.
- The classloader injection is exonerated, not merely assumed safe: an adversarial review read the actual
  `compose-preview-renderer` jar and confirmed the injected `debugScreenshotTest` output tree and the ordinary
  `debug` one share **zero** class-file paths, so the injected loader can only add a class the project finder
  does not already have — it can never shadow one Gradle also compiled against. The `ClassFileFinder … holder
  module … falling back to the main module` warning the render logs on every class load is the platform's own
  lint for the question, not evidence of a wrong class loading (spec, "Read this before implementing D6b").

## Android Studio and platform internals relied on

- **Verify's Gradle integration** is entirely the IDE's own external-system stack: `ExternalSystemUtil.runTask`,
  `ExternalSystemTaskExecutionSettings`, `ExternalSystemProgressNotificationManager` (an application-level bus —
  every listener must reject tasks it did not submit itself), `GradleModuleData`/`GradleUtil` for the project
  path and results directory. No daemon is spawned directly (the same rule `BuildService` holds).
- **`PsiModificationTracker` is explicitly rejected** as a staleness clock for a measurement: it is project-wide
  and fires for anything that can affect PSI, not for edits — writing one file under `build/outputs` moved it by
  5 in a measured case, an actual source edit by 3 — so a verify that writes into `build/` throughout its own
  multi-minute run always outran its own pre-launch stamp. Module-scoped source mtimes
  (`ModuleFreshness.newestModuleSourceMtime`/`cachedModuleSourceMtime`) replaced it.
- **The calibration's class-loader seam**: `StudioModuleRenderContext` (subclassed; `createInjectableClassLoaderLoader()`
  overridden), `StudioModuleClassLoaderManager.getPrivate` (a **private** loader per render — a shared one would
  leak `screenshotTest` classes into every ordinary `@Preview` render's cache), `ProjectSystemClassLoader`,
  `ClassContent.loadFromFile`, and `RenderModelModule.ClassLoaderProvider` — confirmed by runtime probe (the
  render does ask the plugin's own `RenderModelModule` for its loader) and by `javap` against the shipped IDE
  jars for every symbol's exact shape (two details the classloader spike could not have known: the provider
  interface, not a bare loader reference, is what the platform actually asks for; and there is no public
  `buildTargetReference` accessor, so `BuildTargetReference.gradleOnly(module.getIdeaModule())` is used instead,
  confirmed to compile to the same thing AS's own `forModule` factory does).
- **The device/theme pin bypasses `PreviewConfiguration.applyTo`** deliberately:
  `Configuration.setDevice`/`setTheme` are called directly, because `applyTo` resolves a *preferred* theme via
  `Configuration.getPreferredTheme()`, which needs the main manifest index — a `src/screenshotTest` file's module
  has none, and a real gate run threw `MainManifestIndexNotReadyException` from exactly that path.
  `findOrParseFromDefinition` (AS's own device-definition entry point, the same one `applyTo` itself uses) is
  what resolves `id:medium_phone` against the live device catalogue.
- **`AnnotationFilePreviewElementFinder.findPreviewElements` is `suspend`** and must never run under a
  synchronously held read lock — doing so once froze the whole IDE for 65 seconds (see
  [`RenderTaskContext`](../../../src/main/kotlin/com/devomer/previewgallery/render/RenderTaskContext.kt)), which
  is why the config-aware/variant lookup always runs before `RenderModelResolver.resolve`'s own read action, not
  inside it.
- **Every AS-internal call in both features is guarded** against `Exception` and `LinkageError`, with
  `ProcessCanceledException` always re-thrown first — the same posture `LiveRenderer`/`RenderModelResolver`/
  `RenderApiProbe` hold everywhere else in `render/`.

## Tests

- [`ImageDiffTest`](../../../src/test/kotlin/com/devomer/previewgallery/render/ImageDiffTest.kt) — identical
  images, a known differing-pixel count, a size mismatch, and the alpha-compositing cases (transparent vs. opaque
  white, a half-transparent pixel against its composited opaque twin). **No case yet exercises a same-content,
  different-size pair** — D6b's overlap/delta band does not exist to test.
- [`ScreenshotTestClassesTest`](../../../src/test/kotlin/com/devomer/previewgallery/render/ScreenshotTestClassesTest.kt) —
  the lower-camel/upper-camel directory naming for a plain and a flavoured variant, `Missing`/`Ready`/`Stale`
  against a real filesystem clock (`TemporaryFolder`, so the check cannot pass vacuously), and `variantMatches`'s
  casing and flavour/build-type rules.
- [`RenderModelResolverTest`](../../../src/test/kotlin/com/devomer/previewgallery/render/RenderModelResolverTest.kt) —
  the two pure decision tables extracted specifically so they need no Android Studio: `decideVariantResolution`
  (D3a's four reachable combinations) and `decideDevicePin` (D6a's three). Nothing else in
  `RenderModelResolver`/`ScreenshotTestClassLoader` is unit-tested — both are AS-internal and, per the calibration
  spec, verified only "at the gate against `hepsi-android`."
- [`SnapshotVerifyResultsTest`](../../../src/test/kotlin/com/devomer/previewgallery/service/SnapshotVerifyResultsTest.kt) —
  real passing and failing XML shapes (including the engine-wide `validate` file layout versus `update`'s
  per-facade one), D7's timestamp guard, `readForRun`'s UP-TO-DATE fallback in both build outcomes, and resolving
  relative/absolute/empty image paths against the build root.
- [`SnapshotVerifyStoreTest`](../../../src/test/kotlin/com/devomer/previewgallery/service/SnapshotVerifyStoreTest.kt) —
  measurement-vs-attempt separation across all three `Outcome`s, staleness by module-scoped source mtime (not the
  rejected `PsiModificationTracker`), and that a cancelled or UP-TO-DATE run never erodes a standing measurement.
- [`VerifyFailureNotificationTextTest`](../../../src/test/kotlin/com/devomer/previewgallery/service/VerifyFailureNotificationTextTest.kt) —
  silent on a clean or empty run, function+variant grouping, and the three-function cap with "and N more".
- [`PreviewGalleryPanelTest`](../../../src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt) —
  the verify slice covers the debounce-vs-forced interplay, the badge reading staleness from the cache rather
  than a blocking walk on the paint thread, the H3 badge roll-up (with and without a real failure), "nothing to
  verify" vs. "no reference" wording, an indexing refusal, and the UP-TO-DATE-keeps-the-measurement case.
  **No test in this file exercises `compareLiveRender`/`compareOffEdt` at all** — the calibration's own plan
  record says why: it is a private orchestration inside a 1600+-line platform-coupled panel, driving a render
  that itself cannot run outside a real IDE, so it is verified only at the manual gate.
- [`PreviewTreeCellRendererTest`](../../../src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRendererTest.kt) —
  `previewFailureBadge`'s own pure table: no covering snapshots, all-passing snapshots, a cross-module lookup, and
  the stale-wins rule, all without a `Project`.
- **Revert-check debt, worth knowing before trusting a new test in this area:** two PG21 tests passed with their
  own production fix reverted and were only caught by the final review's revert-and-re-run — one used a fixture
  whose parent directories carried a `now` mtime that defeated the very staleness it was arranging, the other
  never exercised the call site it existed for. See the roadmap's H2 section.
- `SnapshotVerifyRunner` itself has no dedicated test file — like `ScreenshotTestClassLoader`, it is
  external-system/AS-internal and, per its own design doc, verified at the manual gate (this is where PG20's four
  gate-only bugs were found).

## Open items

- [gap] **No calibration number has ever been produced**, for any snapshot: every real gate run through PG22-19
  stopped at "nothing measured" — a variant mismatch, a device mismatch, or a size mismatch — never at a
  percentage. — source: [2026-08-20-calibration-size-tolerance-design.md](../../superpowers/specs/2026-08-20-calibration-size-tolerance-design.md);
  confirmed in code (`render/ImageDiff.kt` unchanged since 11dc9c9 [PG22-8]).
- [gap] **D6b is specified but not implemented.** `ImageDiff.compare` still only returns an exact-size `Measured`
  or a `SizeMismatch`; there is no overlap measurement, no size-delta reporting, and no `compare.sizeDelta`
  bundle key in `PreviewGalleryBundle.properties`. — source: spec D6b; `render/ImageDiff.kt`;
  `src/main/resources/messages/PreviewGalleryBundle.properties`.
- [gap] **The gate has not been re-run since PG22-19's engine-parity fixes** (theme, device-by-catalogue-id,
  matching decorations/rendering mode). Until it is, it is not known whether a size delta even survives once the
  device and theme genuinely match — i.e., whether D6b is still needed at all. — source: spec's closing
  paragraph, "So D6b stands as proposed but unimplemented. Re-run the gate on the reconfigured render first."
- [gap] **`docs/snapshot-testing-roadmap.md`'s F5 entry has not been updated for the calibration phase.** Every
  other phase in this area ends with a "record in the roadmap" commit (PG19-8, PG20-11, PG21-11, PG23-3/PG23-6);
  PG22 has none, and the roadmap's F5 section still reads as of the classloader spike, with no mention of D3a,
  D6a, D6b or the "no number yet" outcome. — source: `git log -- docs/snapshot-testing-roadmap.md`; the
  size-tolerance spec's own closing note asking for this update.
- [limitation] **Only the `phone` variant is ever compared.** Matching `small` (or any other multipreview
  variant) is explicitly left to a future diff UI, not to this measurement — a `phone` render compared against a
  `small` golden is treated as a stop, never as a number. — source: spec D3.
- [limitation] **A module with no local `screenshotTest` build output cannot be compared at all**, including on a
  fresh clone: the classes-freshness gate (D5) refuses rather than rendering something potentially stale, with no
  fallback. — source: [2026-08-10-screenshottest-render-spike.md](../../superpowers/specs/2026-08-10-screenshottest-render-spike.md)
  point 4; spec D5.
- [limitation] **Any threshold this calibration eventually picks is a threshold against one pair of toolchain
  versions** — this Android Studio build and this AGP screenshot-plugin version ship different
  `libandroid_runtime.dylib` binaries, different platform builds, and a different `Roboto-Regular.ttf` — and it
  moves whenever either is upgraded. — source: both calibration specs' closing sections.
- [debt] **`SnapshotVerifyRunner` duplicates roughly 120 lines of `BuildService`'s single-flight/generation-guard/
  listener-lifetime machinery** rather than sharing it; the two copies have already diverged once (the same
  task-id ownership bug had to be fixed in both). — source: roadmap F6 section, "Debt".
- [debt] **No test exercises the compare orchestration or the verify runner end-to-end** — both are AS-internal
  or external-system code verified only at the manual `runIde` gate; see Tests above.
- [idea] **A "failing snapshots only" tree filter** (the mirror of F2's uncovered-preview toggle) and **making the
  H3 notification click through to the row** are both named as natural follow-ups and neither is built. — source:
  roadmap H3 section, "Deliberately not built yet".
- [debt] **A deferred minor from PG22-15:** `applyCalibrationConfiguration` writes the device outside a
  `Configuration.startBulkEditing()`/`finishBulkEditing()` pair, unlike AS's own `applyTo`, so it can fire a
  stray listener notification on a `Configuration` shared with an open editor. Not a correctness problem — the
  stop-not-guess guarantee holds either way. — source: size-tolerance spec's closing section.

## History

| Phase | Dates | Commits | What changed |
|---|---|---|---|
| PG19 (spike halves) | 2026-08-10 | 652aaae [PG19-0] · 3731528 [PG19-9] | Two throwaway, reverted spikes settle F5's two blocking unknowns. The first: rendering a `screenshotTest` composable resolves module attribution, facet, configuration and layoutlib inflation without the experimental flag, and fails only at class load — `ClassFileFinder` calls the module's holder ambiguous and falls back to `main`. The second: a plugin-supplied `RenderModelModule.getClassLoaderProvider` is confirmed, at runtime, to be what the render actually asks, and every piece below it is public API (`javap` against the shipped jars). |
| PG20 | 2026-08-10 – 2026-08-12 | 8d54ad7 .. 25b3c21 (23 commits) | Snapshot verify ships: `SnapshotVerifyRunner`, `SnapshotVerifyResults`, `SnapshotVerifyStore`, the debounced auto-verify plus the toolbar button, tree badges and the golden/rendered/diff strip. The roadmap's own account: four defects survived every review and were only found at the manual gate — build-root-relative image paths decoded against the JVM's working directory, `PsiModificationTracker` reading every `build/` write as a source edit, an assumed `diffImagePath` property that does not exist, and one store slot conflating the last measurement with the last attempt. |
| PG21 | 2026-08-12 – 2026-08-13 | 8461a91 .. be5dafc (12 commits) | Hardening (H2): `forcedVerifyPending` lets an explicit Verify survive a selection change inside its own debounce window; `verifyTarget() ?: return` gives "nothing to verify" its own sentence; the staleness walk moves off Swing's paint callback behind a cached, module-scoped source mtime; PG21-8 — "the fifth thing" the gate found — makes `readForRun` fall back to an unfiltered read when Gradle reports UP-TO-DATE and the build succeeded, so a clean module's own six-second verify stops being discarded by D7's staleness guard. Two of this phase's own tests passed with their fix reverted, caught only by the final review's revert-and-re-run. |
| PG22 | 2026-08-13 – 2026-08-20 | a9dc8af .. eddccb1 (22 commits) | Calibration phase 1. PG22-1..7 build `ImageDiff`, `ScreenshotTestClasses`, `ScreenshotTestClassLoader` and the `Compare live render` action/wiring as production code; the first whole-branch review found 1 Critical + 4 Important, all about whether the number could be trusted (delegation dropped, no variant pin, no Gradle-staleness check, silent exceptions, alpha compared raw) — all fixed in PG22-8. Gate run 1 (PG22-9/10/11/12) found `AnnotationFilePreviewElementFinder` returns nothing at all for a `screenshotTest` file on this project, and added D3a's `variantAssumed` render. Gate runs 2 and 3 (PG22-13..19) pinned the device and theme the goldens were actually drawn on — three iterations (dp spec → px spec → catalogue id by name) after each one exposed a new way a wrong device could hide inside a same-pixel-size render — and an adversarial review of the screenshot engine's own renderer jar found the theme accounted for 40% of one image's pixels, exonerated the class loader, and proposed D6b (unimplemented; see Open items). |
| PG23 (H3 only) | 2026-08-19 | 81ed381 .. 77f87f1 (5 of 6 commits; PG23-1 belongs to the project-level doc) | A verify's own recorded failure becomes visible without opening the exact row: the `differs` badge rolls up from a covering snapshot to the preview row (`previewFailureBadge`, across module boundaries), and a run that measured a failure raises a warning notification naming up to three functions (`VerifyFailureNotificationText`). Prompted by a real session where a snapshot calling a shared dialog composable directly was filed under that dialog's own preview, in a collapsed branch nobody had opened. |

## References

- [2026-08-10-screenshottest-render-spike.md](../../superpowers/specs/2026-08-10-screenshottest-render-spike.md) — the first spike (mine: the spike only, not the reference-view work in the same phase)
- [2026-08-10-render-classloader-spike.md](../../superpowers/specs/2026-08-10-render-classloader-spike.md) — the second spike
- [2026-08-10-snapshot-verify-design.md](../../superpowers/specs/2026-08-10-snapshot-verify-design.md) — PG20's design, D1–D10
- [2026-08-13-screenshottest-render-calibration-design.md](../../superpowers/specs/2026-08-13-screenshottest-render-calibration-design.md) — PG22's design, D1–D9, D3a, D6a, D6b
- [2026-08-20-calibration-size-tolerance-design.md](../../superpowers/specs/2026-08-20-calibration-size-tolerance-design.md) — the handoff document proposing D6b, and the adversarial review of the engine's own renderer jar
- [2026-08-13-screenshottest-render-calibration.md](../../superpowers/plans/2026-08-13-screenshottest-render-calibration.md) — PG22's execution plan (tasks 1–6, superseded in detail by the design doc's later D3a/D6a/D6b amendments)
- [2026-08-12-snapshot-verify-hardening.md](../../superpowers/plans/2026-08-12-snapshot-verify-hardening.md) — PG21's plan
- [Snapshot testing roadmap](../../snapshot-testing-roadmap.md) — F5, F6, H2, H3 sections (F5's own entry is stale; see Open items)
- [README.md](../../../README.md) — "Known limitations": the calibration is explicitly not presented as a finished feature
- [feature-overview.md](../../feature-overview.md) — "Status, honestly": the same framing, for a non-engineer audience
