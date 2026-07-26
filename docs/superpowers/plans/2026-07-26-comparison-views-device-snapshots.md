# Comparison Views — Ephemeral Per-Preview Device Snapshots Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user compare the current preview across devices by adding ephemeral in-memory "views" (tabs) — each re-rendering the same composable at a chosen device without editing the `@Preview` — discarded when the selection changes.

**Architecture:** A pure `DeviceOption`/`DeviceCatalog` (curated device list) and a pure `ComparisonViewList` (ephemeral tab state) drive the UI. `PreviewRenderPanel` grows a tab strip: tab 0 is the untouched **Original** render; each extra tab hosts its own `ZoomableRenderView` and renders the same `PreviewEntry` through the existing pipeline with a new **device override**. The override — mapping a plugin-owned `DeviceOption.id` to an AS `Device` and setting it on the render `Configuration` — is the only new AS-internal touch, lives in `render/`, is capability-probed, and degrades to hiding the feature.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · Swing / `JBTabbedPane` · layoutlib render pipeline

**Spec:** [2026-07-26-comparison-views-device-snapshots-design.md](../specs/2026-07-26-comparison-views-device-snapshots-design.md)

## Global Constraints

- Package and Gradle group: `com.devomer.previewgallery`.
- **Never use the Kotlin `!!` operator.**
- **AS-internal API (`com.android.tools.*`) only in `render/`.** `DeviceOption`, `DeviceCatalog`, `ComparisonView`, `ComparisonViewList`, `PreviewRenderPanel`, `ZoomableRenderView` must NOT import `com.android.tools.*`. The only new AS-internal touch is the device override in `RenderModelResolver` and its capability check in `RenderApiProbe` (both `render/`).
- **Every AS-internal call site is guarded** against `Exception` and `LinkageError`, degrading to prior behaviour — never crash, never remove existing behaviour. The device-override capability is probed; when unavailable, the **＋ Add view** control is hidden and the panel behaves exactly as today.
- **Never render on the EDT; PSI/project-model access under a read action.** (Unchanged from Phase 2–5.)
- **Comparison views are ephemeral:** cleared on preview switch, bounded by a max-extras cap; the plugin holds no render cache beyond the live tabs.
- **Pure-logic tests are unit-tested (TDD); Swing/AS-internal behaviour is verified by a `runIde` gate** the user runs — the standing posture across Phase 2–5. Baseline: **131 tests green** (after PG5-6).
- Commit message format: `[PG6-N] - Task name`.
- All documentation, code comments, and commit messages in English.
- Phase 1–5 behaviour must not regress.

## Verification style

Tasks 1 and 2 are pure Kotlin with real unit tests (TDD). Task 3 (render device override) is AS-internal plumbing that stays behaviour-inert until Task 4 wires it (all existing callers pass `null` → today's path), so it commits on `compileKotlin` + green suite; its runtime correctness is verified at Task 4's gate. Task 4 (tab UI) is **discovery-with-a-verification-gate**: the spec's unknowns V1 (can the render device be overridden on our layoutlib path), V2 (which curated device ids resolve), and V3 (memory/latency of N held renders) can only be settled in a running IDE, so it ends with a `runIde` gate, exactly as the Phase 2–5 render/UI gates did.

---

### Task 1: `DeviceOption` + `DeviceCatalog` — the curated device list

**Goal:** The plugin-owned device model. Pure data, no Swing, no AS API.

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/model/DeviceOption.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/model/DeviceCatalogTest.kt`

**Interfaces:**
- Produces:
  - `data class DeviceOption(val id: String, val label: String)` — `id` maps to an AS device in `render/` (Task 3); `label` is the tab/selector text.
  - `object DeviceCatalog { val DEFAULT: List<DeviceOption> }` — the curated v1 set.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/devomer/previewgallery/model/DeviceCatalogTest.kt`:

```kotlin
package com.devomer.previewgallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCatalogTest {

    @Test fun `default catalog is non-empty`() {
        assertTrue(DeviceCatalog.DEFAULT.isNotEmpty())
    }

    @Test fun `default catalog ids are unique`() {
        val ids = DeviceCatalog.DEFAULT.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `default catalog entries have non-blank id and label`() {
        assertTrue(DeviceCatalog.DEFAULT.all { it.id.isNotBlank() && it.label.isNotBlank() })
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `./gradlew test --no-configuration-cache --tests "com.devomer.previewgallery.model.DeviceCatalogTest"`
Expected: FAIL — `DeviceCatalog` / `DeviceOption` unresolved.

- [ ] **Step 3: Write `DeviceOption.kt`**

`src/main/kotlin/com/devomer/previewgallery/model/DeviceOption.kt`:

```kotlin
package com.devomer.previewgallery.model

/**
 * A curated device the user can view a preview on. [id] is a plugin-owned string mapped to an Android Studio
 * `Device` inside `render/` (never an AS type here); [label] is the tab/selector text. Pure data — no Swing, no AS.
 */
data class DeviceOption(val id: String, val label: String)

/**
 * The curated v1 device set for comparison views. A small representative set of form factors, NOT the full AS
 * catalog (that is a deliberate non-goal). Ids are Android Studio device ids; any that do not resolve on the
 * running build are dropped in `render/` (spec V2), so a stale id degrades to "not offered," never a crash.
 */
object DeviceCatalog {
    val DEFAULT: List<DeviceOption> = listOf(
        DeviceOption("pixel_4a", "Pixel 4a"),
        DeviceOption("pixel_7", "Pixel 7"),
        DeviceOption("pixel_tablet", "Pixel Tablet"),
        DeviceOption("pixel_fold", "Pixel Fold"),
    )
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `./gradlew test --no-configuration-cache --tests "com.devomer.previewgallery.model.DeviceCatalogTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/model/DeviceOption.kt src/test/kotlin/com/devomer/previewgallery/model/DeviceCatalogTest.kt
git commit -m "[PG6-1] - Curated device option model and catalog"
```

---

### Task 2: `ComparisonViewList` — the ephemeral tab state

**Goal:** All comparison-view lifecycle logic as a pure, unit-tested holder. No Swing, no AS.

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/ComparisonViewList.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/ui/ComparisonViewListTest.kt`

**Interfaces:**
- Consumes: `DeviceOption` (Task 1).
- Produces:
  - `data class ComparisonView(val id: Int, val device: DeviceOption?)` — `device == null` ⇒ the **Original** view (the preview's own `@Preview` config).
  - `class ComparisonViewList(maxExtras: Int = ComparisonViewList.DEFAULT_MAX_EXTRAS)` with `views: List<ComparisonView>`, `add(device: DeviceOption?): ComparisonView?` (null at cap), `close(id: Int)`, `setDevice(id: Int, device: DeviceOption)`, `clearExtras()`, and companion `ORIGINAL_ID = 0`, `DEFAULT_MAX_EXTRAS = 5`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/devomer/previewgallery/ui/ComparisonViewListTest.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.DeviceOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ComparisonViewListTest {

    private val pixel7 = DeviceOption("pixel_7", "Pixel 7")
    private val tablet = DeviceOption("pixel_tablet", "Pixel Tablet")

    @Test fun `starts with only Original at index 0 with no device`() {
        val list = ComparisonViewList()
        assertEquals(1, list.views.size)
        assertEquals(ComparisonViewList.ORIGINAL_ID, list.views[0].id)
        assertNull(list.views[0].device)
    }

    @Test fun `add appends an extra view carrying the given device`() {
        val list = ComparisonViewList()
        val v = list.add(pixel7)
        assertNotNull(v)
        assertEquals(2, list.views.size)
        assertEquals(pixel7, list.views[1].device)
    }

    @Test fun `add returns null once the extras cap is reached`() {
        val list = ComparisonViewList(maxExtras = 2)
        assertNotNull(list.add(pixel7))
        assertNotNull(list.add(tablet))
        assertNull(list.add(pixel7))          // third extra rejected
        assertEquals(3, list.views.size)       // Original + 2 extras
    }

    @Test fun `close removes an extra but never Original`() {
        val list = ComparisonViewList()
        val v = list.add(pixel7)
        val id = checkNotNull(v).id
        list.close(id)
        assertEquals(1, list.views.size)
        list.close(ComparisonViewList.ORIGINAL_ID)   // no-op
        assertEquals(1, list.views.size)
    }

    @Test fun `setDevice changes an extra's device and ignores Original`() {
        val list = ComparisonViewList()
        val id = checkNotNull(list.add(pixel7)).id
        list.setDevice(id, tablet)
        assertEquals(tablet, list.views[1].device)
        list.setDevice(ComparisonViewList.ORIGINAL_ID, tablet)   // ignored
        assertNull(list.views[0].device)
    }

    @Test fun `clearExtras returns to Original only`() {
        val list = ComparisonViewList()
        list.add(pixel7)
        list.add(tablet)
        list.clearExtras()
        assertEquals(1, list.views.size)
        assertEquals(ComparisonViewList.ORIGINAL_ID, list.views[0].id)
        assertNull(list.views[0].device)
    }

    @Test fun `extra view ids are distinct and not reused after close`() {
        val list = ComparisonViewList()
        val first = checkNotNull(list.add(pixel7)).id
        list.close(first)
        val second = checkNotNull(list.add(tablet)).id
        assertEquals(2, list.views.size)          // Original + the new extra
        org.junit.Assert.assertNotEquals(first, second)
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `./gradlew test --no-configuration-cache --tests "com.devomer.previewgallery.ui.ComparisonViewListTest"`
Expected: FAIL — `ComparisonViewList` unresolved.

- [ ] **Step 3: Write `ComparisonViewList.kt`**

`src/main/kotlin/com/devomer/previewgallery/ui/ComparisonViewList.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.DeviceOption

/**
 * One comparison tab. [device] == null is the **Original** view — the preview at its own `@Preview` config, which
 * never changes device. A non-null [device] is an ephemeral override: the same preview re-rendered on that device.
 */
data class ComparisonView(val id: Int, val device: DeviceOption?)

/**
 * The ephemeral tab state for the render pane: always an Original view at index 0, plus up to [maxExtras] override
 * views. Pure — no Swing, no AS, no rendered images (the panel owns those). Ids are monotonic and never reused, so a
 * closed-then-added tab is a distinct identity. [clearExtras] is called on every preview switch to free the extras.
 */
class ComparisonViewList(private val maxExtras: Int = DEFAULT_MAX_EXTRAS) {

    private val items = mutableListOf(ComparisonView(ORIGINAL_ID, null))
    private var nextId = ORIGINAL_ID + 1

    /** Original first, then the extras in add order. A defensive copy — callers cannot mutate the backing list. */
    val views: List<ComparisonView> get() = items.toList()

    /** Append an override view for [device]; null when the extras cap is already reached. */
    fun add(device: DeviceOption?): ComparisonView? {
        if (items.size - 1 >= maxExtras) return null
        val view = ComparisonView(nextId++, device)
        items.add(view)
        return view
    }

    /** Remove the extra with [id]; a no-op for [ORIGINAL_ID] (Original is never closable). */
    fun close(id: Int) {
        if (id == ORIGINAL_ID) return
        items.removeAll { it.id == id }
    }

    /** Set an extra view's device. Ignores [ORIGINAL_ID] and unknown ids (Original never changes device). */
    fun setDevice(id: Int, device: DeviceOption) {
        val index = items.indexOfFirst { it.id == id }
        if (index <= 0) return
        items[index] = items[index].copy(device = device)
    }

    /** Drop every extra view, returning to Original only (called on a preview selection change). */
    fun clearExtras() {
        val original = items.first()
        items.clear()
        items.add(original)
    }

    companion object {
        const val ORIGINAL_ID = 0
        const val DEFAULT_MAX_EXTRAS = 5
    }
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `./gradlew test --no-configuration-cache --tests "com.devomer.previewgallery.ui.ComparisonViewListTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test --no-configuration-cache`
Expected: BUILD SUCCESSFUL, **131 baseline + 3 (Task 1) + 7 (Task 2) = 141**. Report the real number.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/ComparisonViewList.kt src/test/kotlin/com/devomer/previewgallery/ui/ComparisonViewListTest.kt
git commit -m "[PG6-2] - Ephemeral comparison-view list state"
```

---

### Task 3: Device override in the render pipeline (AS-internal plumbing)

**Goal:** Thread an optional, plugin-owned `deviceOverride: DeviceOption?` through the render pipeline and, in `render/`, map its id to an AS `Device` and set it on the render `Configuration`. Add a capability probe. **Behaviour-inert until Task 4** (all existing callers pass `null` → today's config-aware path), so it commits on compile + green suite; the override's runtime correctness is verified at Task 4's gate.

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderPipeline.kt` (add the param, thread it)
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt` (accept + forward the override)
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderModelResolver.kt` (apply the override to the `Configuration`)
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderApiProbe.kt` (add a `deviceOverride` capability check)

**Interfaces:**
- Consumes: `DeviceOption` (Task 1).
- Produces (indicative — confirm exact existing signatures by reading each file first):
  - `RenderPipeline.render(entry: PreviewEntry, deviceOverride: DeviceOption? = null)` — the `= null` default keeps every current caller unchanged.
  - `LiveRenderer.render(entry: PreviewEntry, deviceOverride: DeviceOption? = null): RenderOutcome`
  - `RenderApiProbe.isDeviceOverrideAvailable(): Boolean`

- [ ] **Step 1: Study the AS device-override API (V1/V2 discovery)**

Against the AS-253 jars (`platformLocalPath` in `gradle.properties`; Android Studio install at `/Users/odurmaz/Applications/Android Studio.app`), use `find … -name '*.jar'` + `unzip -l` + `javap -p`/`javap -c` to confirm, and record in the report:
1. How `RenderModelResolver` currently obtains the `Configuration` (read the file) and which type exposes the device list — likely `com.android.tools.idea.configurations.ConfigurationManager` (`getDevices()` / `devices`) or `com.android.sdklib.devices.DeviceManager`.
2. The `Configuration.setDevice(...)` signature — argument types and arity (e.g. `setDevice(Device, boolean)` vs `setDevice(Device)`), and whether a `Device` is matched by `getId()`.
3. Which of the curated ids (`pixel_4a`, `pixel_7`, `pixel_tablet`, `pixel_fold`) actually resolve on this build (V2). Record the resolving ids; unresolved ones are simply not found at runtime and get dropped.

If the exact API cannot be pinned from the jars, implement the plumbing + the guarded override anyway using your best reading; the probe + the `null`-default keep everything degrade-safe, and Task 4's gate settles the runtime truth.

- [ ] **Step 2: Thread the override param (RenderPipeline → LiveRenderer)**

Read `RenderPipeline.kt` and `LiveRenderer.kt` fully. Add `deviceOverride: DeviceOption? = null` to `RenderPipeline.render(...)` and `LiveRenderer.render(...)`, forwarding it down to the `RenderModelResolver` call. Do not change any existing call site (the default preserves them). Keep `DeviceOption` (a `model/` type) in the signatures — do **not** introduce an AS type into these signatures.

- [ ] **Step 3: Apply the override in `RenderModelResolver` (guarded)**

Read `RenderModelResolver.kt` fully. Where it builds the `Configuration` (after the existing config-aware device is applied), add — using the exact API confirmed in Step 1:

```kotlin
// Ephemeral device override for comparison views (PG6). Applied AFTER the config-aware @Preview device, so a
// null override leaves today's behaviour untouched. Guarded: a shape change or an unknown id degrades to the
// config-aware device, never a crash. (Not a swallow — this is the design's degrade path; re-throw PCE.)
if (deviceOverride != null) {
    try {
        val device = configurationManager.devices.firstOrNull { it.id == deviceOverride.id } // V1: confirm accessor
        if (device != null) configuration.setDevice(device, true)                            // V1: confirm arity
    } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        // degrade: keep the config-aware device
    } catch (e: LinkageError) {
        // degrade: internal API shape changed
    }
}
```

Replace `configurationManager.devices` / `configuration.setDevice(device, true)` with the exact accessors from Step 1.

- [ ] **Step 4: Add the capability probe**

Read `RenderApiProbe.kt` fully (it checks render / picker / config-aware / view-tree reflectively). Add `isDeviceOverrideAvailable(): Boolean` that reflectively verifies the device-list accessor and `Configuration.setDevice` exist (mirror the existing probe style — return false on any `Exception`/`LinkageError`). This gates the UI control in Task 4.

- [ ] **Step 5: Compile and run the suite**

Run: `./gradlew compileKotlin --no-configuration-cache` — Expected: BUILD SUCCESSFUL.
Run: `./gradlew test --no-configuration-cache` — Expected: **141 green** (no new tests; the plumbing is inert). Report the real number.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render/RenderPipeline.kt src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt src/main/kotlin/com/devomer/previewgallery/render/RenderModelResolver.kt src/main/kotlin/com/devomer/previewgallery/render/RenderApiProbe.kt
git commit -m "[PG6-3] - Device override in the render pipeline (guarded, probed)"
```

---

### Task 4: `PreviewRenderPanel` tab strip + comparison views (GATE)

**Goal:** The render-pane tab strip — Original plus ephemeral override tabs — with ＋ Add view, per-tab device selector, per-tab `ZoomableRenderView`, close, clear-on-selection-change, and probe-gated degrade. Settles V1 (override renders), V2 (curated ids), V3 (memory/latency).

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt`
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties` (new keys)
- Read (do not modify): `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` (to learn how `show(...)` is driven and how a render result reaches the panel).

**Interfaces:**
- Consumes: `ComparisonViewList` / `ComparisonView` (Task 2), `DeviceCatalog` / `DeviceOption` (Task 1), `RenderPipeline.render(entry, deviceOverride)` + `RenderApiProbe.isDeviceOverrideAvailable()` (Task 3), `ZoomableRenderView` + `RenderOutcome.Success(image, viewTree)` (existing).

- [ ] **Step 1: Read the current structure**

Read `PreviewRenderPanel.kt` fully (it currently hosts a single `renderView` + `renderScroll`, an icon `ActionToolbar` via `updateActionsBar`, and a `showImage(success)` path), and skim `PreviewGalleryPanel.kt` to see how `show(view, entry)` is called and where a `RenderOutcome` originates. Note: extra-view renders must run **off the EDT** and post the result back to the right tab on the EDT, cancelled when the preview changes.

- [ ] **Step 2: Build the tab strip and per-view state**

In `PreviewRenderPanel`, for the `LIVE` state, replace the single scroll pane with a tab container driven by a `ComparisonViewList`:

1. Fields: keep the existing `renderView`/`renderScroll` as the **Original** view's widgets; add
   ```kotlin
   private val comparisonViews = ComparisonViewList()
   private val viewTabs = com.intellij.ui.components.JBTabbedPane()
   // per-extra-view widgets, keyed by ComparisonView.id:
   private val extraViews = HashMap<Int, ZoomableRenderView>()
   private val extraScrolls = HashMap<Int, com.intellij.ui.components.JBScrollPane>()
   ```
   Show the raw `renderScroll` directly (today's look) when only Original exists; swap to `viewTabs` (Original tab = `renderScroll`, one tab per extra) as soon as an extra exists. Keep `onNavigateToSource` wired to **every** view.

2. `showImage(success)` stays the Original render (unchanged). On a **new** `show(...)` for a different entry, call `comparisonViews.clearExtras()`, dispose/clear `extraViews`/`extraScrolls` (free their images), and collapse back to the single `renderScroll`.

3. Track the current `PreviewEntry` (store it in `show`) so ＋ Add view and device changes know what to render.

- [ ] **Step 3: ＋ Add view, device selector, close (probe-gated)**

Extend `renderControls()` / `updateActionsBar(entry)` (the PG5 icon toolbar): when `RenderApiProbe.isDeviceOverrideAvailable()` **and** there is a live Original image, add an **＋ Add view** icon action (`AllIcons.General.Add`, bundle key `render.addView`). It calls:
```kotlin
val device = DeviceCatalog.DEFAULT.firstOrNull() ?: return
val view = comparisonViews.add(device) ?: return   // null at cap → no-op
addExtraTab(view)                                    // build a ZoomableRenderView + JBScrollPane, add a closable tab
renderInto(view)                                     // async render (Step 4)
```
Each extra tab's header carries a small device selector (`com.intellij.openapi.ui.ComboBox<DeviceOption>` over `DeviceCatalog.DEFAULT`, renderer showing `label`) and a close (`InplaceButton` with `AllIcons.Actions.Close`). Selector change → `comparisonViews.setDevice(view.id, chosen)` then `renderInto(view)`. Close → `comparisonViews.close(view.id)`, remove the tab, drop `extraViews[id]`/`extraScrolls[id]`; if no extras remain, collapse to the single `renderScroll`. When the probe is false, **no ＋ Add view action is added** and the panel is exactly today's.

- [ ] **Step 4: Async per-view render routing**

Add a `renderInto(view: ComparisonView)` that renders the current entry at `view.device` and delivers the image to that view's `ZoomableRenderView`, cancelling stale results:
- Run the render **off the EDT** (reuse the pipeline/executor pattern `RenderPipeline` already uses; do NOT call layoutlib on the EDT). Call the Task 3 path — `RenderPipeline.render(entry, view.device)` or `LiveRenderer.render(entry, view.device)` — per how the existing Original render is driven.
- Guard staleness with a per-view generation token (an `Int` bumped on each `renderInto` for that id and on `clearExtras`); when the async result returns on the EDT, drop it if the token changed or the view was closed.
- On `RenderOutcome.Success`, call `extraViews[view.id]?.setContent(image, viewTree)`; on failure, show a failed/retry affordance inside that tab (reuse the existing `render.failed` label). Only the **active** tab renders eagerly; a not-yet-rendered inactive tab renders on first activation (a `viewTabs` change listener), then caches.

- [ ] **Step 5: Bundle keys**

Add to `src/main/resources/messages/PreviewGalleryBundle.properties`:
```
render.addView=Add comparison view
render.originalView=Original
render.viewOnDevice=View on device
```

- [ ] **Step 6: Compile and run the suite**

Run: `./gradlew compileKotlin --no-configuration-cache` — Expected: BUILD SUCCESSFUL.
Run: `./gradlew test --no-configuration-cache` — Expected: **141 green** (Task 4 adds no unit tests; UI is gated). Report the real number.

- [ ] **Step 7: runIde gate (needs the user)**

Do NOT commit before this passes. A `runIde` sandbox must be freshly started (no other sandbox live, per the project's build rule). Open a Compose project, select a preview, then verify:
1. **Add view (V1):** ＋ Add view adds a second tab showing the **same** preview; its device selector re-renders it on the chosen device, and the `@Preview` source is **not** edited. (AC1)
2. **Multiple views:** add several, each on a different device; tabs switch; each supports zoom/pan and click-to-source independently. (AC2)
3. **Original untouched:** tab 0 always shows the preview at its own `@Preview` config; with no extras the strip is hidden. (AC3)
4. **Ephemeral (V3):** selecting a different preview drops all extra tabs (memory freed); closing a tab frees it. (AC4)
5. **Curated ids (V2):** confirm which of Pixel 4a / 7 / Tablet / Fold actually render; note any id that does not resolve.
6. **Degrade:** (if testable) when the override probe is false, ＋ Add view is absent and the panel is exactly today's. (AC5)
7. **Failure isolation:** a view whose render fails shows a failed/retry state without breaking other tabs. (AC6)

Capture any id that does not resolve (V2), any override that renders the wrong device (V1), or memory/latency problems (V3), and report — the controller decides the next move.

- [ ] **Step 8: Commit (after the gate passes)**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt src/main/resources/messages/PreviewGalleryBundle.properties
git commit -m "[PG6-4] - Comparison-view tab strip with ephemeral device snapshots"
```

---

### Task 5: Changelog & final verification

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Run the whole suite**

Run: `./gradlew test --no-configuration-cache`
Expected: PASS — all existing plus the new pure tests (DeviceCatalog 3, ComparisonViewList 7). No skips. Report the real total.

- [ ] **Step 2: Manual verification (AC1–AC7, needs the user)**

In `runIde` against a real Compose project, confirm each acceptance criterion from the spec: AC1 add-view re-renders on a device without editing source; AC2 multiple views, each zoom/pan/click-to-source; AC3 Original untouched, strip hidden with no extras; AC4 selection change frees extras, close frees a tab; AC5 degrade when override unavailable; AC6 per-tab failure isolation; AC7 `./gradlew test` green, Phase 1–5 unchanged.

- [ ] **Step 3: Changelog and commit**

Add an "Added — comparison views: view the current preview on extra devices in ephemeral tabs without editing `@Preview`" entry to `CHANGELOG.md` under `### Added`, then:

```bash
git add CHANGELOG.md
git commit -m "[PG6-5] - Changelog for comparison views"
```
