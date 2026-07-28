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

---

## Revision (2026-07-27): copies, not device pickers

The user refined the design after Tasks 1–3 landed and while Task 4 was still uncommitted. **Tasks 1–3's
device-only model is superseded by Tasks 6–8 below**; Task 4's tab-strip work stays as the base that Task 8
reworks. The new shape (see the revised spec, decisions D2–D6):

- **＋ Add view** adds an exact **copy of Original** — no setting is chosen at add time; it renders identically.
- **Properties is context-aware**: on Original it opens Android Studio's `@Preview` picker exactly as today; on a
  copy it opens the plugin's own **ephemeral view-settings** popup (device / theme / font scale) that never
  writes source. AS's picker is source-editing — verified in the AS 253 jars:
  `PsiCallParameterPropertyItem.setValue` → `writeNewValue` → `WriteCommandAction.runWriteCommandAction` +
  `KtPsiFactory.createArgument` — so routing a copy's edits through it would rewrite the shared `@Preview` and
  change every tab, defeating comparison.
- **Each tab carries a title**: `Original`, `View N` for an unconfigured copy, else a settings summary.
- **Three axes**, all confirmed present on `com.android.tools.configurations.Configuration`:
  `setDevice(Device, boolean)`, `setNightMode(NightMode)`, `setFontScale(float)`.

---

### Task 6: `ViewConfig` model + `ViewTitle` + `ComparisonViewList` migration

**Goal:** Replace the device-only view state with a three-axis `ViewConfig`, add pure tab-title derivation, and
migrate `ComparisonViewList` to carry a config. All pure, no Swing, no AS.

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/model/ViewConfig.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/model/ViewConfigTest.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/ViewTitle.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/ui/ViewTitleTest.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/ComparisonViewList.kt` (device → config)
- Modify: `src/test/kotlin/com/devomer/previewgallery/ui/ComparisonViewListTest.kt` (same migration)

**Interfaces:**
- Consumes: `DeviceOption`, `DeviceCatalog.DEFAULT` (Task 1, unchanged).
- Produces:
  - `enum class ThemeOption(val label: String) { LIGHT("Light"), DARK("Dark") }`
  - `data class ViewConfig(device: DeviceOption? = null, theme: ThemeOption? = null, fontScale: Float? = null)`
    with `val isDefault: Boolean`
  - `object ViewSettingsCatalog { val DEVICES: List<DeviceOption>; val FONT_SCALES: List<Float> }`
  - `object ViewTitle { fun of(view: ComparisonView, ordinal: Int): String }`
  - `ComparisonView(val id: Int, val config: ViewConfig)`; `ComparisonViewList.add(config: ViewConfig)`,
    `setConfig(id: Int, config: ViewConfig)`

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/com/devomer/previewgallery/model/ViewConfigTest.kt`:

```kotlin
package com.devomer.previewgallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewConfigTest {

    @Test fun `an empty config is default`() {
        assertTrue(ViewConfig().isDefault)
    }

    @Test fun `any set axis makes it non-default`() {
        assertFalse(ViewConfig(device = DeviceOption("pixel_7", "Pixel 7")).isDefault)
        assertFalse(ViewConfig(theme = ThemeOption.DARK).isDefault)
        assertFalse(ViewConfig(fontScale = 1.3f).isDefault)
    }

    @Test fun `catalog devices are the curated list and font scales are sane`() {
        assertEquals(DeviceCatalog.DEFAULT, ViewSettingsCatalog.DEVICES)
        assertTrue(ViewSettingsCatalog.FONT_SCALES.isNotEmpty())
        assertTrue(ViewSettingsCatalog.FONT_SCALES.all { it > 0f })
        assertTrue(ViewSettingsCatalog.FONT_SCALES.contains(1.0f))
        assertEquals(ViewSettingsCatalog.FONT_SCALES.size, ViewSettingsCatalog.FONT_SCALES.toSet().size)
    }

    @Test fun `theme options carry display labels`() {
        assertEquals("Light", ThemeOption.LIGHT.label)
        assertEquals("Dark", ThemeOption.DARK.label)
    }
}
```

`src/test/kotlin/com/devomer/previewgallery/ui/ViewTitleTest.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.DeviceOption
import com.devomer.previewgallery.model.ThemeOption
import com.devomer.previewgallery.model.ViewConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewTitleTest {

    private val pixel7 = DeviceOption("pixel_7", "Pixel 7")

    @Test fun `the original view is titled Original`() {
        assertEquals("Original", ViewTitle.of(ComparisonView(ComparisonViewList.ORIGINAL_ID, ViewConfig()), 0))
    }

    @Test fun `an unconfigured copy is titled by its tab position`() {
        assertEquals("View 2", ViewTitle.of(ComparisonView(1, ViewConfig()), 1))
        assertEquals("View 3", ViewTitle.of(ComparisonView(2, ViewConfig()), 2))
    }

    @Test fun `a single axis titles just that axis`() {
        assertEquals("Pixel 7", ViewTitle.of(ComparisonView(1, ViewConfig(device = pixel7)), 1))
        assertEquals("Dark", ViewTitle.of(ComparisonView(1, ViewConfig(theme = ThemeOption.DARK)), 1))
        assertEquals("1.3×", ViewTitle.of(ComparisonView(1, ViewConfig(fontScale = 1.3f)), 1))
    }

    @Test fun `whole-number font scales drop the decimal`() {
        assertEquals("1×", ViewTitle.of(ComparisonView(1, ViewConfig(fontScale = 1.0f)), 1))
        assertEquals("2×", ViewTitle.of(ComparisonView(1, ViewConfig(fontScale = 2.0f)), 1))
        assertEquals("0.85×", ViewTitle.of(ComparisonView(1, ViewConfig(fontScale = 0.85f)), 1))
    }

    @Test fun `several axes are joined in device theme scale order`() {
        val config = ViewConfig(device = pixel7, theme = ThemeOption.DARK, fontScale = 1.3f)
        assertEquals("Pixel 7 · Dark · 1.3×", ViewTitle.of(ComparisonView(1, config), 1))
    }
}
```

Migrate `src/test/kotlin/com/devomer/previewgallery/ui/ComparisonViewListTest.kt`: replace every
`DeviceOption` argument with a `ViewConfig`, `add(pixel7)` → `add(ViewConfig(device = pixel7))`,
`setDevice(id, tablet)` → `setConfig(id, ViewConfig(device = tablet))`, and assert on `views[i].config`
(e.g. `assertEquals(ViewConfig(device = tablet), list.views[1].config)`; Original asserts
`assertTrue(list.views[0].config.isDefault)`). Keep all seven behaviours (start state, add, cap, close,
setConfig-ignores-Original, clearExtras, non-reused ids) — only the payload type changes.

- [ ] **Step 2: Run the tests, verify they fail**

Run: `./gradlew test --no-configuration-cache --tests "com.devomer.previewgallery.model.ViewConfigTest" --tests "com.devomer.previewgallery.ui.ViewTitleTest"`
Expected: FAIL — `ViewConfig`, `ThemeOption`, `ViewSettingsCatalog`, `ViewTitle` unresolved.

- [ ] **Step 3: Write the model**

`src/main/kotlin/com/devomer/previewgallery/model/ViewConfig.kt`:

```kotlin
package com.devomer.previewgallery.model

/** Light or dark rendering for a comparison copy. Mapped to Android Studio's `NightMode` inside `render/`. */
enum class ThemeOption(val label: String) { LIGHT("Light"), DARK("Dark") }

/**
 * One comparison copy's ephemeral view settings. Every axis is optional: `null` means "inherit whatever the
 * preview's own `@Preview` says", which is exactly what a freshly added copy of Original looks like. Pure data —
 * no Swing, no AS; the mapping to a render `Configuration` lives in `render/`.
 */
data class ViewConfig(
    val device: DeviceOption? = null,
    val theme: ThemeOption? = null,
    val fontScale: Float? = null,
) {
    /** True when nothing is overridden — the copy renders exactly like Original. */
    val isDefault: Boolean get() = device == null && theme == null && fontScale == null
}

/** The curated option lists offered in a copy's view-settings popup. Deliberately small (spec non-goal: no full
 *  AS device catalog, no manual sizes). */
object ViewSettingsCatalog {
    val DEVICES: List<DeviceOption> = DeviceCatalog.DEFAULT
    val FONT_SCALES: List<Float> = listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 2.0f)
}
```

`src/main/kotlin/com/devomer/previewgallery/ui/ViewTitle.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.ViewConfig

/**
 * Pure tab-title derivation for the comparison-view strip: Original is named as such, an unconfigured copy is
 * named by its tab position, and a configured copy summarises its own settings so tabs are self-describing while
 * comparing. No Swing, no AS — unit-tested.
 */
object ViewTitle {

    /** [ordinal] is the view's index in the strip (0 = Original), so the first copy reads "View 2". */
    fun of(view: ComparisonView, ordinal: Int): String {
        if (view.id == ComparisonViewList.ORIGINAL_ID) return PreviewGalleryBundle.message("render.originalView")
        val config = view.config
        if (config.isDefault) return PreviewGalleryBundle.message("render.viewNumbered", ordinal + 1)
        return listOfNotNull(
            config.device?.label,
            config.theme?.label,
            config.fontScale?.let { "${formatScale(it)}×" },
        ).joinToString(" · ")
    }

    /** 1.0 -> "1", 2.0 -> "2", 1.15 -> "1.15": whole scales read better without a decimal tail. */
    private fun formatScale(scale: Float): String =
        if (scale == scale.toInt().toFloat()) scale.toInt().toString() else scale.toString()
}
```

Add to `src/main/resources/messages/PreviewGalleryBundle.properties`:

```
render.viewNumbered=View {0}
```

- [ ] **Step 4: Migrate `ComparisonViewList`**

In `src/main/kotlin/com/devomer/previewgallery/ui/ComparisonViewList.kt`: change `ComparisonView`'s payload from
`device: DeviceOption?` to `config: ViewConfig`, seed Original with `ViewConfig()`, rename `add(device)` →
`add(config: ViewConfig)` and `setDevice(id, device)` → `setConfig(id, config: ViewConfig)` (same guards:
`index <= 0` returns, cap check unchanged, ids still monotonic and never reused). Update the KDoc so it describes
settings rather than a device.

- [ ] **Step 5: Run the tests, verify they pass**

Run: `./gradlew test --no-configuration-cache`
Expected: BUILD SUCCESSFUL. The suite gains ViewConfig 4 + ViewTitle 5 = **150** (141 + 9), with the migrated
`ComparisonViewListTest` still at 7. Report the real number.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/model/ViewConfig.kt src/test/kotlin/com/devomer/previewgallery/model/ViewConfigTest.kt src/main/kotlin/com/devomer/previewgallery/ui/ViewTitle.kt src/test/kotlin/com/devomer/previewgallery/ui/ViewTitleTest.kt src/main/kotlin/com/devomer/previewgallery/ui/ComparisonViewList.kt src/test/kotlin/com/devomer/previewgallery/ui/ComparisonViewListTest.kt src/main/resources/messages/PreviewGalleryBundle.properties
git commit -m "[PG6-6] - Three-axis view config, tab titles, and view-list migration"
```

---

### Task 7: Apply a `ViewConfig` in the render pipeline (AS-internal)

**Goal:** Widen the committed device-only override to the three-axis `ViewConfig`: device, theme (night mode) and
font scale, all guarded, plus the capability probe. Behaviour stays inert when the config is null/default.

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderPipeline.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderModelResolver.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderApiProbe.kt`

**Interfaces:**
- Consumes: `ViewConfig`, `ThemeOption`, `DeviceOption` (Task 6).
- Produces:
  - `RenderPipeline.renderVariant(entry: PreviewEntry, config: ViewConfig, onResult: (RenderOutcome) -> Unit)`
  - `LiveRenderer.render(entry: PreviewEntry, viewConfig: ViewConfig? = null): RenderOutcome`
  - `RenderApiProbe.isViewOverrideAvailable(): Boolean`

- [ ] **Step 1: Replace the `DeviceOption?` parameter with `ViewConfig?`**

Read `RenderPipeline.kt`, `LiveRenderer.kt` and `RenderModelResolver.kt` first. Change the committed
`deviceOverride: DeviceOption?` parameter (threaded in `[PG6-3]`) to `viewConfig: ViewConfig?` everywhere it
appears, including `renderVariant`, which now takes a non-null `ViewConfig`. Keep the plugin-owned types in every
signature — no AS type may appear there.

- [ ] **Step 2: Apply all three axes in `RenderModelResolver` (guarded)**

Replace the committed device-only override block with one that applies each set axis, keeping the existing guard
shape (re-throw `ProcessCanceledException`; degrade on `Exception` and `LinkageError`; a null/default config
leaves today's path untouched):

```kotlin
// Ephemeral view override for comparison copies (PG6). Applied AFTER the config-aware @Preview values, so a
// null/default config leaves today's behaviour untouched. Each axis is independent: an unresolved device or an
// unsupported setter degrades to the config-aware value rather than failing the render.
if (viewConfig != null && !viewConfig.isDefault) {
    try {
        viewConfig.device?.let { option ->
            configurationManager.getDeviceById(option.id)?.let { configuration.setDevice(it, true) }
        }
        viewConfig.theme?.let { theme ->
            configuration.setNightMode(
                when (theme) {
                    ThemeOption.DARK -> com.android.resources.NightMode.NIGHT
                    ThemeOption.LIGHT -> com.android.resources.NightMode.NOTNIGHT
                },
            )
        }
        viewConfig.fontScale?.let { configuration.setFontScale(it) }
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        thisLogger().info("Could not apply the comparison view config; keeping the @Preview configuration", e)
    } catch (e: LinkageError) {
        thisLogger().info("View-override API is incompatible with this IDE build", e)
    }
}
```

Use the accessors already confirmed against the AS 253 jars: `ConfigurationManager.getDeviceById(String): Device?`,
`Configuration.setDevice(Device, Boolean)`, `Configuration.setNightMode(com.android.resources.NightMode)`,
`Configuration.setFontScale(Float)`.

- [ ] **Step 3: Widen the probe**

In `RenderApiProbe.kt`, rename the committed `isDeviceOverrideAvailable()` to `isViewOverrideAvailable()` and
extend its required-member list so it also covers `setNightMode` and `setFontScale` alongside `getDeviceById` and
`setDevice`, mirroring the existing probe style (any `Exception`/`LinkageError` → false).

- [ ] **Step 4: Compile and run the suite**

Run: `./gradlew compileKotlin --no-configuration-cache` — Expected: BUILD SUCCESSFUL.
Run: `./gradlew test --no-configuration-cache` — Expected: **150 green** (no new tests; this is plumbing).
Report the real number.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render/RenderPipeline.kt src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt src/main/kotlin/com/devomer/previewgallery/render/RenderModelResolver.kt src/main/kotlin/com/devomer/previewgallery/render/RenderApiProbe.kt
git commit -m "[PG6-7] - Apply device, theme and font-scale overrides in the render pipeline"
```

---

### Task 8: Copy tabs, titles, and context-aware Properties (GATE)

**Goal:** Rework the tab strip to the copy model: ＋ Add view adds an unconfigured copy of Original, every tab
carries a title, and Properties edits whatever is active — AS's picker on Original, the plugin's ephemeral
view-settings popup on a copy. Settles V1 (do the axes render), V2 (which device ids resolve), V3 (memory).

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/ViewSettingsPopup.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt`
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties`

**Interfaces:**
- Consumes: `ComparisonViewList`/`ComparisonView` + `ViewTitle` (Task 6), `ViewConfig`/`ThemeOption`/
  `ViewSettingsCatalog` (Task 6), `RenderPipeline.renderVariant(entry, config, onResult)` +
  `RenderApiProbe.isViewOverrideAvailable()` (Task 7), `ZoomableRenderView`, `RenderOutcome.Success`.
- Produces: `ViewSettingsPopup.show(anchor: JComponent, config: ViewConfig, onChange: (ViewConfig) -> Unit)`.

- [ ] **Step 1: Write `ViewSettingsPopup`**

A plugin-owned, AS-free popup (`JBPopupFactory.getInstance().createComponentPopupBuilder(...)`) holding three
labelled rows — Device, Theme, Font scale — each a `ComboBox` whose first item is a "Default (@Preview)" entry
representing `null`:

- Device: `null` + `ViewSettingsCatalog.DEVICES`, rendered by `DeviceOption.label`.
- Theme: `null` + `ThemeOption.values()`, rendered by `ThemeOption.label`.
- Font scale: `null` + `ViewSettingsCatalog.FONT_SCALES`, rendered as `1.3×` (whole numbers without a decimal
  tail, matching `ViewTitle`).

Every change builds a new `ViewConfig` from the three current selections and calls `onChange(config)` — the popup
never writes source and holds no AS type. Bundle keys: `render.viewSettings`, `render.viewSettings.device`,
`render.viewSettings.theme`, `render.viewSettings.fontScale`, `render.viewSettings.default`.

- [ ] **Step 2: ＋ Add view adds a copy**

In `PreviewRenderPanel`, change the ＋ Add view action (added only when `deviceOverrideAvailable` — now fed by
`RenderApiProbe.isViewOverrideAvailable()` — and a live Original image exists) so it adds an **unconfigured**
copy and renders it:

```kotlin
val view = comparisonViews.add(ViewConfig()) ?: return   // null at the cap
addExtraTab(view)
renderInto(view)                                          // renders identically to Original
```

Remove the per-tab device `ComboBox` from the tab header — a tab header now carries only its **title** (from
`ViewTitle.of(view, ordinal)`) and the close button. Refresh a tab's title whenever its config changes.

- [ ] **Step 3: Context-aware Properties**

Make the Properties action target the active tab:

```kotlin
override fun actionPerformed(e: AnActionEvent) {
    val anchor = e.inputEvent?.component ?: actionsBar
    val entry = currentEntry ?: return
    val view = activeComparisonView()          // null (or ORIGINAL_ID) => Original
    if (view == null || view.id == ComparisonViewList.ORIGINAL_ID) {
        onProperties(entry, RelativePoint(anchor, Point(0, anchor.height)))   // today's AS picker
    } else {
        ViewSettingsPopup.show(anchor as JComponent, view.config) { updated ->
            comparisonViews.setConfig(view.id, updated)
            refreshTabTitle(view.id)
            renderInto(comparisonViews.views.first { it.id == view.id })
        }
    }
}
```

Add `activeComparisonView(): ComparisonView?` next to the existing `activeView()` helper (same
`viewTabs.selectedComponent` → extra-id lookup, then `comparisonViews.views.firstOrNull { it.id == id }`).
Rebuild the actions bar on tab change so the Properties action's gating matches the active tab: it is added when
the active tab is Original and `propertiesAvailable`, or when the active tab is a copy and the view-override
capability is available — never as a dead control.

- [ ] **Step 4: Compile and run the suite**

Run: `./gradlew compileKotlin --no-configuration-cache` — Expected: BUILD SUCCESSFUL.
Run: `./gradlew test --no-configuration-cache` — Expected: **150 green** (UI is gate-verified, no new unit tests).
Report the real number.

- [ ] **Step 5: runIde gate (needs the user)**

Do NOT commit before this passes. Start a fresh `runIde` (no other sandbox live), open a Compose project, select a
preview, then verify:
1. **Copy (AC1):** ＋ Add view adds a tab that looks identical to Original, with **no setting prompt**.
2. **Copy settings (AC2/V1):** with the copy active, Properties opens the view-settings popup; changing device,
   theme and font scale re-renders **only that tab**; Original and the `@Preview` **source** are unchanged.
3. **Original Properties (AC3):** with Original active, Properties opens Android Studio's picker as before.
4. **Titles (AC4):** `Original`, `View 2` before configuring, then the settings summary.
5. **Several copies (AC5):** each with its own settings; per-tab zoom/pan/click-to-source; the toolbar's
   zoom/fit/hand-tool/Save-PNG/Copy act on the **active** tab.
6. **Ephemeral (AC6/V3):** selecting another preview drops every copy; closing a tab frees it; with no copies the
   strip is hidden.
7. **Degrade + failure (AC7):** ＋ Add view absent when the capability is unavailable; a failed copy render shows
   a retry inside its own tab.
8. **V2:** record which of Pixel 4a / 7 / Tablet / Fold actually render as that device.

- [ ] **Step 6: Commit (after the gate passes)**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/ViewSettingsPopup.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt src/main/resources/messages/PreviewGalleryBundle.properties
git commit -m "[PG6-8] - Copy tabs with titles and context-aware view settings"
```

---

## Revision 2 (2026-07-27): the real picker, over an in-memory model

The PG6-8 gate showed the copy tabs working (add, titles, per-tab render, toolbar) but the user rejected the
reduced three-axis popup: a copy must offer **the same dialog with the same properties as Original**, held in
memory per tab. Two feasibility studies against the AS 253 jars settled how:

- **The picker's source-writing lives in its property items, not its UI.** `PsiPickerManager.show(Point, String,
  PsiPropertiesModel, Balloon.Position)` takes the **abstract** `PsiPropertiesModel`, and the whole chain
  (`createPickerPanel` → `PsiPropertyView` → `PropertiesPanel`) touches no PSI. Subclass `PsiPropertiesModel`
  (public no-arg constructor; implement `properties`, `inspectorBuilder`, `tracker`), fill it with a subclass of
  the **open** `MemoryParameterPropertyItem` that overrides `setValue` to notify us (its own `setValue` is a bare
  field write), reuse AS's `PreviewPropertiesInspectorBuilder(EnumSupportValuesProvider)` for the identical
  layout, and get the dropdown values from the public `PreviewPickerValuesProvider.createPreviewValuesProvider(
  module, file)`.
- **Applying the overrides:** derive a preview element with
  `ComposePreviewElementInstance.createDerivedInstance(displaySettings, configuration)` and let AS's own
  `applyTo(configuration)` — already used by `RenderModelResolver.applyConfigAware` — do the mapping. This covers
  every property including the ones no `Configuration` setter reaches (`showBackground`/`backgroundColor`/
  `widthDp`/`heightDp` travel through the bridge XML that AS's `toPreviewXml()` writes).
- **Trap (spec V4):** `PreviewConfiguration.Companion.cleanAndGet(...)` treats `null` as "reset to the layoutlib
  sentinel" (`UNDEFINED_API_LEVEL = -1`, `UNDEFINED_DIMENSION = -1`, `NO_DEVICE_SPEC = ""`, `UNSET_UI_MODE_VALUE
  = 0`, `NO_WALLPAPER_SELECTED = -1`), **not** "keep the current value". Every unedited axis must be passed
  through explicitly from the base configuration.

**Tasks 6–8's `ViewConfig` (device/theme/fontScale) is superseded by `ViewOverride`** below; the interim
`ViewSettingsPopup` and the `applyAxis` block are removed with it, so there is only ever one override mechanism.

---

### Task 9: `ViewOverride` — the full-property override model (TDD)

**Goal:** Replace the three-axis `ViewConfig` with a plugin-owned, name→value override carrying whatever the
picker offers. Pure, no Swing, no AS.

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/model/ViewOverride.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/model/ViewOverrideTest.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/ComparisonViewList.kt` (payload `ViewConfig` → `ViewOverride`)
- Modify: `src/test/kotlin/com/devomer/previewgallery/ui/ComparisonViewListTest.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/ViewTitle.kt` (summarise an override)
- Modify: `src/test/kotlin/com/devomer/previewgallery/ui/ViewTitleTest.kt`
- Delete: `src/main/kotlin/com/devomer/previewgallery/model/ViewConfig.kt`, `src/test/kotlin/com/devomer/previewgallery/model/ViewConfigTest.kt`,
  `src/main/kotlin/com/devomer/previewgallery/model/DeviceOption.kt`, `src/test/kotlin/com/devomer/previewgallery/model/DeviceCatalogTest.kt`,
  `src/main/kotlin/com/devomer/previewgallery/ui/ViewSettingsPopup.kt`

**Interfaces:**
- Produces:
  - `data class ViewOverride(val values: Map<String, String> = emptyMap())` with `val isDefault: Boolean` and
    `fun with(name: String, value: String): ViewOverride`
  - `ComparisonView(val id: Int, val override: ViewOverride)`; `ComparisonViewList.add(override)`,
    `setOverride(id, override)`
  - `ViewTitle.of(view: ComparisonView, ordinal: Int): String`

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/com/devomer/previewgallery/model/ViewOverrideTest.kt`:

```kotlin
package com.devomer.previewgallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewOverrideTest {

    @Test fun `an empty override is default`() {
        assertTrue(ViewOverride().isDefault)
    }

    @Test fun `any value makes it non-default`() {
        assertFalse(ViewOverride(mapOf("device" to "id:pixel_7")).isDefault)
    }

    @Test fun `with adds a value without mutating the original`() {
        val base = ViewOverride()
        val next = base.with("fontScale", "1.3")
        assertTrue(base.isDefault)
        assertEquals(mapOf("fontScale" to "1.3"), next.values)
    }

    @Test fun `with replaces an existing value`() {
        val override = ViewOverride().with("fontScale", "1.3").with("fontScale", "2.0")
        assertEquals(mapOf("fontScale" to "2.0"), override.values)
    }

    @Test fun `with keeps the other values`() {
        val override = ViewOverride().with("device", "id:pixel_7").with("fontScale", "1.3")
        assertEquals(2, override.values.size)
        assertEquals("id:pixel_7", override.values["device"])
    }
}
```

Rewrite `ViewTitleTest` for the new payload — same three behaviours, override-shaped:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.ViewOverride
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewTitleTest {

    @Test fun `the original view is titled Original`() {
        assertEquals("Original", ViewTitle.of(ComparisonView(ComparisonViewList.ORIGINAL_ID, ViewOverride()), 0))
    }

    @Test fun `an untouched copy is titled by its tab position`() {
        assertEquals("View 2", ViewTitle.of(ComparisonView(1, ViewOverride()), 1))
        assertEquals("View 3", ViewTitle.of(ComparisonView(2, ViewOverride()), 2))
    }

    @Test fun `a single override is summarised as name and value`() {
        val view = ComparisonView(1, ViewOverride(mapOf("fontScale" to "1.3")))
        assertEquals("fontScale 1.3", ViewTitle.of(view, 1))
    }

    @Test fun `several overrides are joined in insertion order`() {
        val override = ViewOverride().with("device", "Pixel 7").with("fontScale", "1.3")
        assertEquals("device Pixel 7 · fontScale 1.3", ViewTitle.of(ComparisonView(1, override), 1))
    }
}
```

And migrate `ComparisonViewListTest`: every `ViewConfig(...)` becomes a `ViewOverride(...)`, `add(config)` →
`add(override)`, `setConfig(id, ...)` → `setOverride(id, ...)`, assertions read `views[i].override`. Keep all
seven behaviours (start state, add, cap, close, setOverride-ignores-Original, clearExtras, non-reused ids).

- [ ] **Step 2: Run the tests, verify they fail**

Run: `./gradlew test --no-configuration-cache --tests "com.devomer.previewgallery.model.ViewOverrideTest" --tests "com.devomer.previewgallery.ui.ViewTitleTest"`
Expected: FAIL — `ViewOverride` unresolved.

- [ ] **Step 3: Write `ViewOverride`**

`src/main/kotlin/com/devomer/previewgallery/model/ViewOverride.kt`:

```kotlin
package com.devomer.previewgallery.model

/**
 * One comparison copy's ephemeral property overrides, keyed by Android Studio's own `@Preview` picker property
 * names ("device", "apiLevel", "locale", "fontScale", "uiMode", "showSystemUi", "showBackground",
 * "backgroundColor", "widthDp", "heightDp", "wallpaper"). Values are the picker's own strings; `render/` maps
 * them onto AS types, so no AS type ever reaches `model/` or `ui/`. An empty map is an untouched copy of
 * Original. Insertion order is preserved so a tab title reads in the order the user edited.
 */
data class ViewOverride(val values: Map<String, String> = emptyMap()) {

    /** True when nothing is overridden — the copy renders exactly like Original. */
    val isDefault: Boolean get() = values.isEmpty()

    /** This override plus [name] = [value]; replaces an existing entry, keeps the rest, never mutates this one. */
    fun with(name: String, value: String): ViewOverride =
        ViewOverride(LinkedHashMap(values).apply { put(name, value) })
}
```

- [ ] **Step 4: Migrate `ComparisonViewList` and `ViewTitle`**

In `ComparisonViewList.kt`: `ComparisonView`'s payload becomes `override: ViewOverride`, Original is seeded with
`ViewOverride()`, `add(override: ViewOverride)` and `setOverride(id, override: ViewOverride)` replace their
config-named counterparts; guards (`index <= 0`, the cap check, monotonic `nextId`) are unchanged.

In `ViewTitle.kt`, the summary comes from the override's entries instead of three typed fields:

```kotlin
    fun of(view: ComparisonView, ordinal: Int): String {
        if (view.id == ComparisonViewList.ORIGINAL_ID) return PreviewGalleryBundle.message("render.originalView")
        val override = view.override
        if (override.isDefault) return PreviewGalleryBundle.message("render.viewNumbered", ordinal + 1)
        return override.values.entries.joinToString(" · ") { "${it.key} ${it.value}" }
    }
```

Delete `ViewConfig.kt`, `ViewConfigTest.kt`, `DeviceOption.kt`, `DeviceCatalogTest.kt` and `ViewSettingsPopup.kt`
(the interim three-axis model and popup), and drop their references from `PreviewRenderPanel` — the panel's
Properties action now calls the bridge added in Task 10; until that task lands, have the copy branch do nothing
(a one-line TODO-free no-op is not acceptable, so instead keep the panel compiling by leaving the copy branch
calling `onRequestVariant` with the unchanged override, i.e. a plain re-render).

- [ ] **Step 5: Run the suite**

Run: `./gradlew test --no-configuration-cache`
Expected: BUILD SUCCESSFUL. The suite loses ViewConfig 4 + DeviceCatalog 3 and gains ViewOverride 5, with
`ViewTitleTest` back to 4 and `ComparisonViewListTest` at 7 — report the real number.

- [ ] **Step 6: Commit**

```bash
git add -A src/main/kotlin/com/devomer/previewgallery/model src/main/kotlin/com/devomer/previewgallery/ui src/test/kotlin/com/devomer/previewgallery
git commit -m "[PG6-9] - Replace the three-axis view config with a full property override"
```

---

### Task 10: `EphemeralPickerBridge` + full override application (AS-internal)

**Goal:** Show AS's own picker over an in-memory model for a copy, and apply the resulting overrides to that
copy's render by deriving a preview element.

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/render/EphemeralPickerBridge.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderModelResolver.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt`, `RenderPipeline.kt` (`ViewConfig` → `ViewOverride`)
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderApiProbe.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt`, `PreviewGalleryPanel.kt` (wire the bridge)

**Interfaces:**
- Consumes: `ViewOverride` (Task 9).
- Produces: `EphemeralPickerBridge.showEphemeralPicker(entry, override, at, onEdit): Boolean`,
  `RenderApiProbe.isViewOverrideAvailable()`.

- [ ] **Step 1: Write `EphemeralPickerBridge`**

Model it on the existing `PreviewPickerBridge.kt` — read that file first; it already carries the
`@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")` header these Kotlin-`internal` AS types need, the
off-EDT + read-action discipline, and the `Exception`/`LinkageError` guards. The new bridge:

1. Resolves the module + file for the entry (same lookup the existing bridge uses).
2. `val enumProvider = PreviewPickerValuesProvider.createPreviewValuesProvider(module, entry.file)`.
3. Builds one notifying item per property, seeded from the entry's current `@Preview` values merged with
   [override]:
   ```kotlin
   private class NotifyingItem(
       name: String,
       defaultValue: String,
       private val onEdit: (String, String) -> Unit,
   ) : MemoryParameterPropertyItem(name, defaultValue, { null }) {
       override fun setValue(value: String?) {
           super.setValue(value)
           if (value != null) onEdit(name, value)
       }
   }
   ```
   (Confirm `MemoryParameterPropertyItem`'s exact constructor and `setValue` signature with `javap` first; the
   validator parameter's type is `Function1<String, Pair<EditingErrorCategory, String>>`.)
4. Builds the model:
   ```kotlin
   private class EphemeralModel(
       private val items: List<PsiPropertyItem>,
       private val enumProvider: EnumSupportValuesProvider,
   ) : PsiPropertiesModel() {
       override fun getProperties(): PropertiesTable<PsiPropertyItem> {
           val table = HashBasedTable.create<String, String, PsiPropertyItem>()
           items.forEach { table.put(it.namespace, it.name, it) }
           return PropertiesTable.create(table)
       }
       override fun getInspectorBuilder(): PsiPropertiesInspectorBuilder =
           PreviewPropertiesInspectorBuilder(enumProvider)
       override fun getTracker(): ComposePickerTracker = GalleryPickerTracker {}
   }
   ```
5. `PsiPickerManager.show(at.screenPoint, entry.indexed.displayName, model, Balloon.Position.below)` on the EDT,
   after the model is built off the EDT — mirroring `PreviewPickerBridge`'s `buildModelAndShow`/`showPopup` split
   and its `at.component.isShowing` re-check.

Every AS-internal call stays inside the existing guard shape (re-throw `ProcessCanceledException`, then degrade
on `Exception` and `LinkageError`, logging once).

- [ ] **Step 2: Apply the override in `RenderModelResolver`**

Replace the `applyAxis` three-axis block with a derived-element path. After the config-aware element is resolved
(`configAware ?: buildDefaultPreviewElement(entry)`), when the override is non-default:

```kotlin
val base = /* the resolved element */
val derived = runCatching {
    val merged = mergeConfiguration(base.configuration, override)          // pure helper, Step 3
    val display = base.displaySettings.copy(
        showDecoration = override.values["showSystemUi"]?.toBooleanStrictOrNull()
            ?: base.displaySettings.showDecoration,
    )
    base.createDerivedInstance(display, merged)
}.getOrNull() ?: base
```

then run the existing `applyTo(configuration)` path against `derived` instead of `base`. Guard exactly as the
surrounding code does (PCE re-thrown, `Exception`/`LinkageError` degrade to `base`). **Spec V4:** never pass
`null` into `PreviewConfiguration.Companion.cleanAndGet(...)` for an axis the user did not edit — pass the base
configuration's current value through.

- [ ] **Step 3: The merge helper is pure and unit-tested**

Put the name→field merge in a plugin-owned object so V4's trap is covered by tests rather than by a gate:

`src/main/kotlin/com/devomer/previewgallery/render/OverrideMerge.kt`

```kotlin
package com.devomer.previewgallery.render

import com.devomer.previewgallery.model.ViewOverride

/** The base-preserving merge behind the render override (spec V4): for every axis the user did not edit, the
 *  base value must be passed through explicitly — Android Studio's `cleanAndGet` treats a null as "reset to the
 *  layoutlib sentinel", not "keep". Pure and unit-tested; the AS types are assembled by the caller. */
data class MergedConfig(
    val apiLevel: Int, val width: Int, val height: Int, val locale: String,
    val fontScale: Float, val uiMode: Int, val deviceSpec: String, val wallpaper: Int,
)

object OverrideMerge {
    fun merge(base: MergedConfig, override: ViewOverride): MergedConfig = MergedConfig(
        apiLevel = override.values["apiLevel"]?.toIntOrNull() ?: base.apiLevel,
        width = override.values["widthDp"]?.toIntOrNull() ?: base.width,
        height = override.values["heightDp"]?.toIntOrNull() ?: base.height,
        locale = override.values["locale"] ?: base.locale,
        fontScale = override.values["fontScale"]?.toFloatOrNull() ?: base.fontScale,
        uiMode = override.values["uiMode"]?.toIntOrNull() ?: base.uiMode,
        deviceSpec = override.values["device"] ?: base.deviceSpec,
        wallpaper = override.values["wallpaper"]?.toIntOrNull() ?: base.wallpaper,
    )
}
```

with `src/test/kotlin/com/devomer/previewgallery/render/OverrideMergeTest.kt` asserting: an empty override
returns the base unchanged; each axis overrides only itself; an unparseable value falls back to the base value
(never to a sentinel).

- [ ] **Step 4: Probe + wiring**

Extend `RenderApiProbe.isViewOverrideAvailable()` to also require `createDerivedInstance` and the picker-model
members. In `PreviewRenderPanel`, the copy branch of `PropertiesAction` calls a new
`var onEphemeralProperties: (PreviewEntry, ViewOverride, RelativePoint, (String, String) -> Unit) -> Unit`
(wired in `PreviewGalleryPanel` to `EphemeralPickerBridge.showEphemeralPicker`), and each `onEdit` does
`comparisonViews.setOverride(view.id, view.override.with(name, value))`, refreshes the tab titles, and
re-renders that view.

- [ ] **Step 5: Compile and run the suite**

Run: `./gradlew compileKotlin --no-configuration-cache` — Expected: BUILD SUCCESSFUL.
Run: `./gradlew test --no-configuration-cache` — Expected: the Task 9 total plus `OverrideMergeTest`. Report it.

- [ ] **Step 6: runIde gate (needs the user)**

Do NOT commit before this passes. Fresh `runIde`, open a Compose project, select a preview, then:
1. **Same dialog (AC2):** ＋ Add view, then Properties on the copy → **Android Studio's own picker**, with the
   same properties Original shows, seeded from the copy's values.
2. Change device, apiLevel, uiMode, fontScale, showBackground/backgroundColor, widthDp/heightDp → **only that
   tab** re-renders; Original and the other tabs are untouched; the `@Preview` **source file is unmodified**
   (check the editor / VCS diff).
3. Each copy keeps its own values; switching tabs switches between the configured renders (AC2's memory clause).
4. Properties on **Original** still opens the picker that edits source (AC3).
5. Titles track the overrides (AC4); ephemeral clearing on preview switch still holds (AC6).
6. Record which properties visibly take effect (V1) and whether the dialog's layout matches Original's (V2).

- [ ] **Step 7: Commit (after the gate passes)**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render src/main/kotlin/com/devomer/previewgallery/ui src/test/kotlin/com/devomer/previewgallery/render
git commit -m "[PG6-10] - Ephemeral Android Studio picker and full override rendering for comparison copies"
```

---

### Task 11: Changelog & final verification

**Files:** Modify `CHANGELOG.md`

- [ ] **Step 1:** `./gradlew test --no-configuration-cache` — report the real total, no skips.
- [ ] **Step 2:** Confirm AC1–AC8 from the spec in `runIde` (needs the user).
- [ ] **Step 3:** Add an "Added — comparison views: open extra copies of a preview in tabs and configure each one
  with Android Studio's own `@Preview` picker, in memory, without touching the source" entry under `### Added`,
  then:

```bash
git add CHANGELOG.md
git commit -m "[PG6-11] - Changelog for comparison views"
```
