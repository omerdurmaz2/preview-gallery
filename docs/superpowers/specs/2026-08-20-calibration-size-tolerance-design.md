# Design — let the calibration measure when the two renders differ by a pixel or two

**Status:** proposed, awaiting implementation · **Feature:** F5's diff half, phase 1 (the calibration) ·
**Commit prefix:** `PG22`, continuing from `PG22-16`
**Amends:** [2026-08-13-screenshottest-render-calibration-design.md](2026-08-13-screenshottest-render-calibration-design.md)
— decision **D6** (the metric). Everything else in that document stands.

> **This is a handoff document.** It is written to be picked up by a session that was not present for any of the
> work below, so it repeats the state rather than assuming it.

## Where the calibration stands

The calibration renders a `@PreviewTest` composable inside the IDE and measures the pixel distance between that
render and the golden PNG Gradle's Android screenshot-test plugin committed for the same function. The number
decides whether F5's diff half gets built at all.

**Everything up to the measurement now works, verified at the keyboard against `hepsi-android`:**

- The classloader injection works. The `screenshotTest` classes reach the render classpath, the composable loads,
  and the pane shows *golden*, *Gradle's rendered* and *live* side by side, drawing the same UI. Both spikes'
  unproven assumptions are answered by a working render.
- The device is pinned correctly. Three gate runs walked it in: `2152x2076` (the module's own landscape device) →
  `1079x190` (right device, dp rounding) → **`1080x250` against a `1080x252` golden** (right device, exact width).

**One thing blocks the number, and it is the metric itself.** D6 makes size equality a precondition, so a render
that is two pixels shorter than its golden measures nothing at all.

## The finding

For `CreateListContent_Default_Snapshot`, `phone` variant:

| Image | Size |
|---|---|
| Committed golden | 1080 × 252 |
| Gradle's own `rendered` PNG from the last `validate` | 1080 × 252 |
| The IDE's live render | 1080 × 250 |

The engine agrees with itself; the IDE is 2 px shorter. **And it is not a constant offset** — an earlier run of
`DeleteSelectedProductsDialog_Direct_Snapshot` produced `1079x190 vs 1080x190`, where the height matched *exactly*
and only the width was off (that width was the dp-rounding bug, fixed in `PG22-16`).

So the delta is content-dependent: a measurement rounding somewhere in layout, not a fixed border or chrome. These
goldens are shrink-to-content on both sides (the committed set contains 1080×147, 1080×308, 1080×476 alongside the
full-screen 1080×2400), so both engines shrink; they occasionally disagree by a pixel or two about how tall the
content is.

**Pixel-exact size equality is therefore not achievable, and D6 as written can never produce a number for a
content-shrunk snapshot that lands on the wrong side of that rounding.**

## Decision D6b — measure the overlap, always show the delta

Replace D6's "size equality is a precondition" with a two-band rule:

- **A large difference stays a refusal.** If either dimension differs by more than **2%** of the golden's, the pane
  says the configurations did not match and measures nothing, exactly as today. This is what keeps a wrong variant
  or a wrong device from being absorbed into a percentage — the property the whole calibration exists to protect,
  and the reason `small` (840 px wide) can never be silently measured against `phone` (1080 px).
- **A small difference is measured, with the delta stated.** Otherwise compare the images over their **overlapping
  region, anchored at the top-left corner**, report the differing-pixel percentage over that region, and **always**
  print the size delta beside it:

  ```
  live vs golden 0.42% different · live 2px shorter
  ```

  The delta is never dropped and never folded into the percentage. A reader who sees only the percentage has been
  told the whole truth about the pixels that were compared, and the delta tells them how many were not.

**Why the overlap rather than a pad-and-compare:** padding invents pixels and every invented pixel counts as a
difference, which is the opposite of what this measurement is for. Anchoring top-left is right because both engines
shrink to content from the top-left; a bottom-anchored difference would show up as a large percentage and be
correctly suspicious.

**What this buys diagnostically.** Today the two possible causes of a 2 px delta are indistinguishable because
nothing is measured. After this, they separate themselves:

- overlap ≈ 0% → the renders agree and the delta is two rows of chrome, a divider, or a rounding at the bottom
  edge. Green.
- overlap large → the engines genuinely draw differently and the size delta was only the first symptom. Red, and
  the percentage says how red.

**The decision table in the parent spec still applies to the overlap percentage**, with one addition: a refusal in
the first band is not a Red verdict, it is "not measured" — the same distinction D3a already draws for the assumed
variant.

## What to build

Small and contained. Three files.

### 1. `render/ImageDiff.kt` — the metric

Today `compare` returns `Result.SizeMismatch` whenever the dimensions differ, and `Result.Measured` only when they
are identical. Add the middle band. Suggested shape, to be adjusted to whatever reads best against the existing
sealed hierarchy:

- keep `Result.SizeMismatch(left, right)` for the >2% band, unchanged;
- extend the measured case to carry the compared region and the delta, so the caller can render both facts —
  for example `Result.Measured(differingPixels, totalPixels, comparedWidth, comparedHeight, leftSize, rightSize)`,
  with `percent` unchanged and a `sizeDelta` the message layer formats;
- compare only `0 until min(width)` × `0 until min(height)`, and keep the existing white-compositing step
  (`onWhite`) exactly as it is — the alpha decision from `PG22-8` is unrelated and still correct.

The 2% threshold belongs in one named constant with a KDoc that says where the number came from: 2 px on 252 is
0.8%, `small` versus `phone` is 22%, so the band separates a rounding from a wrong variant by more than an order of
magnitude.

**This file is pure and must stay pure** — no `com.intellij`, no `com.android`. It is the calibration's most
testable seam and already has `ImageDiffTest`.

### 2. `ui/PreviewGalleryPanel.kt` — the message

`formatComparison` currently maps `SizeMismatch` to `compare.sizeMismatch` / `compare.sizeMismatchAssumed` and
`Measured` to `compare.percent`. It gains the delta clause. New bundle key, in
`src/main/resources/messages/PreviewGalleryBundle.properties` beside the existing `compare.*` block:

```properties
compare.sizeDelta={0} · live {1}px {2}
```

or whatever composes cleanly with `compare.percent` — the existing keys are `compare.result`,
`compare.resultGoldenOnly`, `compare.percent`, `compare.sizeMismatch`, `compare.sizeMismatchAssumed`,
`compare.gradleStale`, `compare.gradleLabel`, `compare.liveLabel`. Follow that file's voice; do not invent a second
vocabulary for "shorter"/"taller".

Both comparisons (live↔golden and live↔Gradle) carry their own delta; they can differ.

### 3. The parent design doc

Append D6b to `2026-08-13-screenshottest-render-calibration-design.md` with the evidence table above, so the
decision and the numbers that forced it live with the rest of the calibration's reasoning. This file can then be
deleted or left as the working note it is.

## Tests

`ImageDiffTest` is plain JUnit with no fixture and already covers identical images, a known differing-pixel count,
and a size mismatch. Add:

1. A 2 px height delta inside the band: the overlap is measured, the percentage is over the overlap, and the delta
   is reported.
2. A delta just over 2%: still `SizeMismatch`, nothing measured.
3. A delta exactly at the boundary: pick the side deliberately and assert it, so the rule is pinned rather than
   inferred.
4. A width delta as well as a height delta — both dimensions, since the band checks each.
5. The existing cases stay green, including the alpha compositing ones.

**Revert-check at least one of the new cases:** break the production line, run the focused test, capture the failing
output, restore it, capture the pass, and paste both into the report. This project shipped two tests that passed
with their own fix reverted; the check exists because of that.

## Constraints that bind any session doing this work

- **Commit form** `[PG22-N] - <name>`, continuing from `PG22-16`, with a `Co-Authored-By: <model> <noreply@anthropic.com>`
  trailer naming the model that actually wrote the commit — this repo's history has per-commit authors, not a
  session-wide one.
- **No `!!`. No inline code comments — KDoc only.** Four tasks in this feature were sent back for the second rule.
- **Never run `./gradlew` while a `runIde` sandbox is live.** Check with exactly
  `pgrep -f "idea.plugin.in.sandbox.mode=true"` and `pgrep -f "gradlew.*runIde"` before every Gradle invocation; if
  either prints a pid, stop rather than building through it — the sandbox reads the plugin jar a concurrent build is
  rewriting, which corrupts the running plugin. Never run `./gradlew runIde` from the agent side; the human runs the
  gate.
- **The suite is 591 tests, all passing, at `1f48001`.** Run the full `./gradlew test` before committing.
- **Do not push.** The human pushes; this environment has no credentials. `main` is 37 commits ahead of `origin`.

## How the gate confirms it

Against `hepsi-android`, from a `runIde` sandbox:

1. Select a snapshot row in `features/favorites/ui` whose module has been validated recently (press **Verify
   snapshots** first if unsure — the calibration refuses on stale compiled classes by design, decision D5).
2. Press **Compare live render**.
3. Expect a percentage plus a delta, for example
   `phone variant assumed … · live vs golden 0.42% different · live 2px shorter · live vs Gradle …`.
4. Repeat on **at least five** rows across different files, including one full-screen snapshot (1080×2400) and one
   small component (1080×147). Record every number and delta verbatim.
5. Apply the parent spec's decision table to the overlap percentages.

## Read this before implementing D6b: an adversarial review changed the picture (2026-08-20)

A skeptic review found the screenshot engine's own renderer on disk —
`com.android.tools.compose:compose-preview-renderer:0.0.1-alpha15` in `~/.gradle/caches` — and compared the two
render paths instruction for instruction. The engine is not a black box: it drives the same
`com.android.tools.rendering` API this plugin does. Its findings reorder the work.

**The class loader is exonerated.** `getClassFileFinder`'s holder-module branch logs that warning and returns the
*main* module's finder. It cannot load a different implementation than Gradle compiled against: the injected
directory is tried first, and the two output trees (`built_in_kotlinc/debugScreenshotTest/…/classes` and
`built_in_kotlinc/debug`) share **zero** class-file paths, so the injection can only add, never shadow. No `R` class
exists in the injected directory at all. The warning is the platform lint for asking a holder module a source-set
question — Android Studio's own Compose preview takes the same path in this project.

**But the number, as the code stands, would not measure what the phase asks.** Three divergences dominate it, all
of them configuration rather than engine:

1. **The theme, and it is the largest term.** The engine's `RenderRequest.configurationModifier` is literally
   `PreviewConfigurationKt::applyTo` — the seam `PG22-15` removed — and `applyTo` ends with
   `setTheme(getPreferredTheme())`. Its `StandaloneThemeInfoProvider` hardcodes
   `@android:style/Theme.Material.Light`, whose `windowBackground` resolves to **`#FAFAFA`**. Decoding the golden
   the gate is about to measure (`CreateListActionBar_Submitting_Snapshot_phone_…png`, 1080×190) finds
   `(250,250,250,255)` in **83,044 of 205,200 pixels — 40.5%** of the image. The composable cannot be painting it:
   `CreateListActionBar` is a bare `Row`, and both `PrimusTheme` and `PreviewComponent` are `CompositionLocalProvider`
   wrappers with no `Surface`. The plugin disables decorations, so it paints no window background at all, and
   `ImageDiff.onWhite` then turns those pixels `#FFFFFF`. **Over 40% of the image differs before a single glyph is
   compared.**
2. **The device is the right size but the wrong device.** `PG22-16`'s px spec is correct about pixels — 1080×2400
   exactly — but `createDeviceInstance` also leaves `xdpi/ydpi = 0`, `nav`/`keyboard`/`touchScreen` null, and derives
   `ScreenSize` from the diagonal: `sqrt(1080²+2400²)/420 = 6.27 in` → **`LARGE`**, where the engine's catalogue
   `medium_phone` declares `normal`. layoutlib ORs that into `Configuration.screenLayout`, so anything reading
   `WindowSizeClass`, `isLayoutSizeAtLeast`, or a `-large`/`-normal` resource folder branches differently — and this
   module has exactly those adaptive shapes. **The size check no longer catches it**, because both sides are now
   1080×2400: D6a's claim that a wrong device cannot be absorbed into the number was true of the dp spec and is not
   true of the px spec.
3. **The frame is sampled at a different, irreproducible instant.** The engine calls `render()` once. The plugin
   runs `inflate()` → `render()` → `drainComposeCallbacks` (up to 16 rounds × 16 ms of stepped frame clock, exiting
   on a 100 ms **wall-clock** budget) → `render()`. `CreateListActionBar_Submitting_Snapshot` draws
   `PrimusLoadingSpinner` — `rememberInfiniteTransition` + `Modifier.rotate` — so the engine captures ≈0° and the
   plugin ≈92°, and the exact angle depends on how fast the machine is that day. Every `FavoritesSkeleton_*` and
   `ShimmerOptionsPlaceholder_*` snapshot shares this.

Also recorded: the engine builds with `showDecorations = true` and rendering mode `NORMAL`, where the plugin uses
`disableDecorations()` and `SHRINK`; and `applyTo` also sets `uiMode`, `fontScale = 1.0`, `locale = ANY` and the
API target, all of which the plugin currently inherits from a shared, workspace-persisted IDE `Configuration`.

### Do these three first, then D6b

Each is roughly one line, and together they cost far less than a session:

- **`CALIBRATION_DEVICE_SPEC = "id:medium_phone"`.** `findOrParseFromDefinition` already handles an `id:` prefix
  (`removePrefix("id:")`, then match on `Device.getId()`), so this hands the render the *same catalogue device* the
  engine picks from the same `devices.xml`, with every qualifier matching — strictly more correct than a synthesized
  spec, and self-documenting.
- **`configuration.setTheme("@android:style/Theme.Material.Light")`** inside `applyCalibrationDevice`. A literal
  string, so `getPreferredTheme()` is still never called and `MainManifestIndexNotReadyException` stays avoided.
  This reproduces the engine rather than approximating it.
- **Drop `disableDecorations()` and `SHRINK` on the calibration path only**, so the engine's own builder defaults
  apply. Ordinary preview renders keep both.

Then re-run the gate. **D6b may become unnecessary**: several of the size deltas being tolerated could be artefacts
of the mismatched device and rendering mode. Measure again before writing tolerance into the metric — and if a
delta survives all three fixes, D6b as specified above is still the right answer.

### What the first number will never prove

The two stacks ship different `libandroid_runtime.dylib` binaries (19.9 MB vs 25.0 MB), different platform builds
(36.0 vs 36.1, four months apart) and a different `Roboto-Regular.ttf`. Whatever residue survives after every
configuration is matched belongs to *this AS build against this AGP screenshot-plugin version*, and it moves when
either is upgraded. A threshold read from one measurement is a threshold against one pair of toolchain versions —
worth stating in the roadmap entry whichever way the number lands.

### One structural risk to guard cheaply

`compareLiveRender` takes the build variant from the first `ReferenceRoots.Root` that names one, while the
main-class finder resolves against the IDE's *selected* variant. On a flavoured module those can diverge and the
injected `screenshotTest` classes would link against another flavour's `main`. Compare the two and refuse when they
differ.

## What is still open beyond this fix

- **A skeptic review was running when this document was written** and its answer matters before the number is
  believed. It was asked whether the Android Studio warning the render logs on every class load —
  `ClassFileFinder for Module 'hepsi-android.features.favorites.ui' holder module requested. This is ambiguous.
  Falling back to the main module.`, raised through `ScreenshotTestClassLoader.projectClassFor` — can make the IDE
  load a *different* implementation than the one Gradle compiled and rendered. If it can, the percentage compares
  two different programs and means nothing regardless of how small it is. Find that answer, or ask the question
  again, before writing any conclusion into the roadmap.
- **The roadmap's F5 entry** (`docs/snapshot-testing-roadmap.md`) is waiting for the number. F5's diff half is the
  last open item in the whole snapshot theme; the entry should record the numbers, the decision, and — whichever
  way it goes — what the calibration cost and proved.
- **A deferred minor from `PG22-15`:** `applyCalibrationDevice` writes the device outside a
  `Configuration.startBulkEditing()` / `finishBulkEditing()` pair, where AS's own `applyTo` batches its writes. Not
  a correctness problem (the stop-not-guess guarantee holds either way) but it can fire a stray listener
  notification on a `Configuration` shared with an open editor. Two lines, worth taking if anything odd shows up in
  the pane.
