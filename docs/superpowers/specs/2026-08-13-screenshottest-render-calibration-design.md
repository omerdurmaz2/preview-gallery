# Design — calibrate the IDE's render against the committed golden

**Status:** approved, 2026-08-13 · **Feature:** F5's diff half, phase 1 · **Commit prefix:** `PG22`
**Follows:** [2026-08-10-screenshottest-render-spike.md](2026-08-10-screenshottest-render-spike.md),
[2026-08-10-render-classloader-spike.md](2026-08-10-render-classloader-spike.md)

## The question this answers

The plugin can draw a composable inside the IDE. Gradle draws the same composable when it runs the screenshot
tests, and commits the result as a golden PNG. **Nobody has checked whether those two drawings are the same
image.**

Everything F5's diff half promises rests on that answer. If the two renders agree, the plugin can tell a developer
whether a composable still matches its golden in seconds, without Gradle. If they disagree, any diff UI built on
top would report a difference on every unchanged composable — a machine for false alarms, which is worse than no
feature at all.

So phase 1 produces a **number**, not a user interface.

## Why a number is worth a session

Measuring costs one button. Building the diff UI first and discovering the renders disagree costs a session and
leaves a feature nobody trusts. Both outcomes of the measurement are useful:

- **Green:** the diff UI is designed next, against a proven premise.
- **Red:** F5's diff half closes as *tried, does not work, here is why*, and the roadmap question that has been
  open since July stops being relitigated. Two spikes have already closed this way in this project, and both
  earned their cost.

## Where this leads (recorded so it is not relitigated later)

The destination is not a button. It is: **you edit a composable, save, and the plugin tells you whether its golden
still holds** — without you asking, and without a Gradle task run. That flow is
`save → compile the module → render → compare → mark the row`, and its compile step is ordinary incremental
compilation (which `BuildService` already performs on selection), not the `validate` task that redraws all 100
snapshots in the module.

The staging is deliberate:

1. **This phase** — a button and two numbers. Is it the same picture?
2. **If green** — the real diff surface: what changed, where.
3. **After that** — automatic on save, using `CaretPreviewResolver` to know which composable is being edited.

Step 3 is the valuable one and step 1 is the only honest way to reach it. An automatic checker built on renders
that do not agree would fire on every save and be muted within two days.

## Decisions

**D1 · Keep the code, not a spike.** The classloader composition, the render and the measurement are written as
production code and stay in the tree. The two prior investigations were throwaway spikes because they were
answering *whether an API exists*; this one runs the real chain, and the chain is what F5 needs either way.
Rewriting it a second time would be the only thing a throwaway version bought.

**D2 · Two comparisons, not one.** The live render is compared against **the committed golden** — the product
question — and against **the `rendered` PNG the last `validate` run wrote** — the diagnostic one. The second costs
almost nothing: `SnapshotVerifyStore` already holds that path per snapshot (PG20). Without it, a bad first number
cannot be attributed: a difference between engines and a golden that has simply gone stale look identical.

**D3 · One variant: `phone`.** The consuming project's `@SnapshotPreviews` multipreview renders every snapshot at
two widths (`phone`, and `small` at `widthDp = 320`), and each writes its own golden. Comparing a `phone` render
against a `small` golden would produce a large number that means nothing — and would read as "the engines
disagree", which it would not be. The calibration fixes `phone` on both sides. Matching every variant is the diff
UI's problem, not the measurement's.

The instance is selected by the `@Preview` `name` the multipreview declares, which is also the `variant` string in
the results XML and in the golden's file name — one vocabulary across all three. If the resolver turns out to yield
a single instance for a multipreview rather than one per `@Preview`, **that is a finding to record and stop on**,
not something to work around by rendering whatever came back: a render whose variant is unknown cannot be compared
against a variant-specific golden.

**D4 · An explicit action, not automatic.** The measurement runs when a button is pressed on a selected snapshot
row. A false alarm the user triggered is a data point; a false alarm that arrives on its own accumulates behind
them. Automatic triggering is step 3 above, and it is gated on this phase reading green.

**D5 · Refuse rather than measure something stale.** The compiled `screenshotTest` classes directory must exist
and must not be older than the module's sources. When it is missing or stale, nothing is rendered and the pane
names the task to run (`validate<Variant>ScreenshotTest`). The staleness comparison reuses
`ModuleFreshness.newestModuleSourceMtime`, which PG21 already shaped for exactly this question. A number derived
from stale classes is a wrong number, and this phase exists to produce a right one.

Concretely: the classes side of that comparison is the **newest** `.class` file under the directory, not the
directory's own mtime. A directory's timestamp moves when an entry is added or removed and stays put when a file
is overwritten in place, which is the common case for a recompile — exactly the mistake `ModuleFreshness`'
own bounded scan documents.

**D6 · The engine's own metric.** Size equality is a precondition; the measurement itself is the share of
differing pixels, reported as a percentage — the same vocabulary the Android screenshot engine uses in its own
report (`0.111% different`), so the two numbers can be compared directly. A perceptual metric with its own
tolerance would answer a different question and could not be checked against the engine's.

**D7 · The threshold is fixed before the number is seen.** See the decision table below. Choosing it afterwards is
how "actually 3% is fine" gets written.

**D8 · Degrade, never break.** Every AS-internal call is guarded against `Exception` and `LinkageError` and falls
back to the pane the row shows today. This is the posture `render/` already holds for `LiveRenderer`,
`RenderModelResolver` and `RenderApiProbe`; the classloader composition adds no exception to it.

**D9 · The measurement is pure.** `ImageDiff` takes two `BufferedImage`s and returns a result. No platform types,
no I/O, no `com.intellij` imports — so it is unit-testable without a fixture, like `RenderedImageInspector` beside
it.

## Components

| Component | Kind | Responsibility |
|---|---|---|
| `render/ScreenshotTestClasses.kt` | pure | Locate `build/intermediates/built_in_kotlinc/<variant>ScreenshotTest/compile<Variant>ScreenshotTestKotlin/classes/`, and decide present / missing / stale against a source mtime |
| `render/ScreenshotTestClassLoader.kt` | AS-internal, guarded | The spike's composition: a `StudioModuleRenderContext` subclass whose `createInjectableClassLoaderLoader()` returns a `ProjectSystemClassLoader` reading `.class` files from that directory, wrapped by a `RenderModelModule` delegate whose `getClassLoaderProvider` calls `StudioModuleClassLoaderManager.getPrivate`. Private loader per render, `Reference` closed afterwards |
| `render/RenderModelResolver.kt` | modified | Accepts an optional class-loader override and applies it to the `AndroidFacetRenderModelModule` it already builds (line 116) |
| `render/ImageDiff.kt` | pure | Two images → size match, differing pixel count, percentage |
| `ui/CompareLiveRenderAction.kt` | UI | Toolbar action, visible only on a snapshot row, mirroring `VerifySnapshotsAction`'s hidden-not-disabled convention |
| `ui/PreviewGalleryPanel.kt` | modified | Orchestrates the flow below and publishes through the existing `ReferenceStripView` |

The variant name comes from `ReferenceRoots`, which already resolves `Debug` versus `GoogleDebug` per module. The
golden comes from `ReferenceImageLocator`, which already returns PNGs per function and variant. Gradle's own
rendered PNG comes from `SnapshotVerifyStore`'s measurement. Nothing here re-derives a path that an existing
component owns.

## Flow

1. The user selects a snapshot row and presses **Compare live render**.
2. Off the EDT: locate the classes directory for the row's module and variant.
3. **The staleness gate (D5).** Missing, or older than the module's sources → publish the explanatory message and
   stop. No render is attempted.
4. Compose the private class loader over that directory.
5. Resolve and render the `@PreviewTest` function through the existing `RenderModelResolver` / `LiveRenderer` path,
   taking the multipreview's `phone` instance.
6. Decode the `phone` golden.
7. Decode the `rendered` PNG from the module's last measurement, if there is one.
8. Measure: live ↔ golden, and live ↔ Gradle's render when step 7 produced an image.
9. On the EDT: publish golden, Gradle's render and the live render side by side in the strip, with one line above
   them — `live vs golden 0.04% · live vs Gradle 0.00%`.
10. Release the loader reference. A loader carrying `screenshotTest` classes must never outlive its render, or it
    leaks test classes into the IDE's shared class cache.

## Error handling

| Where | What the user gets |
|---|---|
| Classes directory missing or stale | No number. The message names `validate<Variant>ScreenshotTest`. Nothing is rendered. |
| Class-loader composition throws (`Exception` / `LinkageError`) | "Could not render live" plus the reason in the log; the row's committed goldens still display. |
| Render fails | The existing `RenderOutcome.Failure` pane. No new failure surface. |
| No golden for this function and variant | Nothing to compare against; the message says so. |
| No `rendered` PNG (the module never ran a verify) | The second number is omitted. One `?:`, not a second path. |
| Images differ in size | No percentage. The sizes are printed (`1080x2340 vs 371x168`) — a size mismatch is itself the red result. |

## Decision table

| Result | What happens next |
|---|---|
| ≤ 0.1% on both comparisons | Green. The diff surface is designed; F5 continues. |
| 0.1% – 1% | Amber. A thresholded diff becomes its own decision — can this much difference be called noise? |
| > 1%, or a size mismatch | Red. F5's diff half closes in the roadmap as tried, with the number and the reason. |

## Testing

Everything pure is unit-tested: `ImageDiff` (identical images, a known number of differing pixels, a size
mismatch), the classes-directory locator, and the staleness decision. These need no fixture.

The class loader and the render are AS-internal and cannot be unit-tested — the same position `SnapshotVerifyRunner`
holds, and the reason PG20's four real bugs were found at a manual gate rather than in the suite. They are verified
at the gate against `hepsi-android`. **The plan must not invent a mock that pretends otherwise.**

The gate is also where the calibration itself happens: the number this feature exists to produce is read from a
real module, and it decides what the roadmap says next.

## Non-goals

No diff image. No synchronised zoom across the strip. No automatic triggering. No second variant. No change to the
verify path, its badges, or its messages. Each of these is a consequence of the measurement, not an input to it.

## Risks

- **The composition may not work on the first render.** The classloader spike proved every piece is public and the
  seam is the plugin's, but it never ran the chain: whether `ProjectSystemClassLoader`'s lambda is consulted for
  this class rather than an earlier loader in `MultiLoaderWithAffinity` answering first is unknown until it runs.
  Realising this risk is a phase-1 finding, not a failure — it is exactly what the phase is for.
- **The measurement may be dominated by something trivial and fixable** — a device configuration, a density, a
  background. If the first number is red, the gate should record *what the images look like*, not only the
  percentage, before the roadmap conclusion is written.
