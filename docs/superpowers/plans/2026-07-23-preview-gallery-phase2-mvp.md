# Preview Gallery — Phase 2 MVP (Live Rendering) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the selected Compose `@Preview` in the tool window's lower panel, through Android Studio's own layoutlib pipeline, with build-on-demand.

**Architecture:** A `RenderPipeline` orchestrates selection → render; all Android-Studio-internal API coupling is isolated in `LiveRenderer` + `RenderModelResolver`; `BuildService` compiles the target module on demand via the IDE's Gradle integration; `PreviewRenderPanel` replaces Phase 1's `PreviewRenderPlaceholder`. Everything that can be plain Kotlin (freshness heuristic, pipeline classification, state, config) stays outside the internal-API and Swing layers so it is unit-testable.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · `org.jetbrains.android` bundled plugin (layoutlib / `StudioRenderService`) · Gradle 9.5.0 · JUnit 4 · `BasePlatformTestCase`

**Spec:** [2026-07-23-preview-gallery-phase2-mvp-design.md](../specs/2026-07-23-preview-gallery-phase2-mvp-design.md)

## Global Constraints

- Package and Gradle group: `com.devomer.previewgallery`.
- **Never use the Kotlin `!!` operator.** Use `?:`, `requireNotNull`, `checkNotNull`, or an early return.
- **All Android-Studio-internal API coupling lives in `LiveRenderer` and `RenderModelResolver`, nowhere else.** Every other component is plain platform/PSI/Swing code.
- **Every AS-internal call site is guarded** against both `Exception` and `LinkageError` (`NoSuchMethodError`, `NoClassDefFoundError`); a capability probe runs before first use (spec §5.3).
- **`RenderTask.dispose()` in a `finally` block, always.** Never render on the EDT. PSI/project-model access under a read action.
- **`BuildService` never spawns a Gradle daemon directly** — it uses `ExternalSystemUtil.runTask` with `GradleConstants.SYSTEM_ID` (spec B1). Selection never triggers a build (B3); only the explicit Render button does.
- **Pure-logic tests are written in the final test task (Task 9), not per task** — carrying the user's standing preference from Phase 1. The AS-internal render/build path cannot be unit-tested; Tasks that touch `LiveRenderer`/`BuildService` include an in-task `runIde` verification, which is behaviour confirmation (the only way to validate internal-API code), not a unit test.
- Commit message format: `[PG2-N] - Task name`, where N is the task number in this plan.
- All documentation, code comments, and commit messages in English.
- Phase 1 code (index, service, tree, search, tool window, Search Everywhere) is complete and must not regress. `PreviewRenderPanel` replaces only `PreviewRenderPlaceholder`.

## A note on the verification style of this plan

Phase 1 could be written as fully-specified code because it used stable platform API. This phase's render and
build paths call `com.android.tools.*` internal API whose exact call chain (spec §2.3, unknowns U1–U6) cannot be
written correctly without running it against a real IDE. Those tasks (1, 2, 6) are therefore written as
**discovery-with-a-verification-gate**: a concrete goal, the exact API surface already probed (spec §2.1), the
specific unknowns to resolve, and a `runIde` acceptance check — not line-final code. The pure tasks (3, 4, 5, 7,
8) are fully specified. This is deliberate and matches the spec's Phase-0-was-a-desk-check framing.

---

### Task 1: Compile classpath + capability probe

**Goal:** Prove `com.android.tools.*` render classes are reachable at compile time from this plugin, and stand up
`LiveRenderer` as a guarded skeleton whose `isAvailable()` reports whether the render API is present. Resolves U6
and builds the §5.3 probe. No actual rendering yet.

**Files:**
- Modify: `build.gradle.kts` (only if U6 needs an explicit dependency)
- Create: `src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/render/RenderApiProbe.kt`

**Interfaces:**
- Produces: `RenderApiProbe.isAvailable(): Boolean` — reflective check that the §2.1 classes/methods exist;
  `LiveRenderer` (skeleton) with `fun isAvailable(): Boolean` delegating to the probe.

- [ ] **Step 1: Confirm the render classes resolve at compile time**

Write a one-off Kotlin file that references the probed types and try to compile it:

```kotlin
// scratch, do not keep: verifies U6
import com.android.tools.idea.rendering.StudioRenderService
import com.android.tools.rendering.RenderService
import com.android.tools.rendering.RenderTask
import com.android.tools.rendering.RenderResult
import com.android.tools.idea.rendering.AndroidFacetRenderModelModule
import com.android.tools.idea.configurations.ConfigurationManager
import com.android.tools.preview.SingleComposePreviewElementInstance
```

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. If it fails with `unresolved reference`, the bundled `org.jetbrains.android` does not
put these on the compile classpath. In that case, and only then, add the Android plugin's render module
explicitly in `build.gradle.kts`'s `intellijPlatform { }` block (try `bundledModule("intellij.android.rendering")`
or the module the probe reports), re-run, and record exactly what was needed. Delete the scratch file once it
compiles.

- [ ] **Step 2: Write the reflective capability probe**

`RenderApiProbe.kt` — does NOT import the AS types directly (so a missing class cannot fail class-loading of the
probe itself); it looks them up by name and verifies the key method signatures:

```kotlin
package com.devomer.previewgallery.render

import com.intellij.openapi.diagnostic.thisLogger

/**
 * Reflectively verifies the Android Studio render API this plugin depends on is present with the expected
 * signatures on the running IDE build. Everything here is internal AS API, so a newer build can change it; the
 * probe turns that from a crash into a clean "renderer unavailable" (spec §5.3).
 */
object RenderApiProbe {

    private val required = listOf(
        "com.android.tools.idea.rendering.StudioRenderService" to listOf("getInstance"),
        "com.android.tools.rendering.RenderService" to listOf("taskBuilder"),
        "com.android.tools.rendering.RenderTask" to listOf("inflate", "render", "dispose"),
        "com.android.tools.rendering.RenderResult" to listOf("processImageIfNotDisposed"),
        "com.android.tools.idea.rendering.AndroidFacetRenderModelModule" to emptyList(),
        "com.android.tools.preview.SingleComposePreviewElementInstance" to emptyList(),
    )

    fun isAvailable(): Boolean = runCatching {
        required.all { (className, methods) ->
            val clazz = Class.forName(className, false, javaClass.classLoader)
            methods.all { name -> clazz.methods.any { it.name == name } }
        }
    }.onFailure { thisLogger().info("Render API unavailable on this IDE build: ${it.message}") }
        .getOrDefault(false)
}
```

- [ ] **Step 3: Write the `LiveRenderer` skeleton**

```kotlin
package com.devomer.previewgallery.render

import com.intellij.openapi.project.Project

/**
 * Renders a composable by FQN through Android Studio's layoutlib pipeline. The ONLY home (with
 * [RenderModelResolver]) for AS-internal render API. Real rendering arrives in Task 2.
 */
class LiveRenderer(private val project: Project) {

    fun isAvailable(): Boolean = RenderApiProbe.isAvailable()
}
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: runIde verification of the probe**

Run: `./gradlew runIde`. In the sandbox IDE, confirm `idea.log` shows no error from `RenderApiProbe`, and add a
temporary log line (removed before commit) at plugin startup printing `RenderApiProbe.isAvailable()`. Expected:
`true` on Android Studio 253. If `false`, the probe's class/method names are wrong for this build — fix them
against the real jars before continuing. Record the observed value in the report.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/main/kotlin/com/devomer/previewgallery/render
git commit -m "[PG2-1] - Render API compile access and capability probe"
```

---

### Task 2: `RenderModelResolver` + `LiveRenderer` — the real render (vertical slice · GATE)

**Goal:** Render one real androidx preview from a built module to a `BufferedImage`, verified by an image
appearing in `runIde`. Resolves U1–U5. **This is the riskiest task and the gate for the whole phase** — if the
call chain proves intractable, stop and escalate to the controller (fallback is the spec's §11.1 CI-PNG path).

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/render/RenderModelResolver.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/model/RenderOutcome.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt`

**Interfaces:**
- Consumes: `PreviewEntry` (Phase 1: `composableFqn`, `moduleName`, `file`), `RenderApiProbe`.
- Produces:
  - `sealed interface RenderOutcome { data class Success(val image: BufferedImage); data class Failure(val message: String, val detail: String?); data class Unsupported(val reason: String) }`
  - `LiveRenderer.render(entry: PreviewEntry): RenderOutcome` — must be called off the EDT.
  - `RenderModelResolver` — turns a module + file into the `(RenderModelModule, Configuration, RenderLogger)` triple `taskBuilder` needs.

- [ ] **Step 1: Write the `RenderOutcome` model**

`src/main/kotlin/com/devomer/previewgallery/model/RenderOutcome.kt`:

```kotlin
package com.devomer.previewgallery.model

import java.awt.image.BufferedImage

sealed interface RenderOutcome {
    data class Success(val image: BufferedImage) : RenderOutcome
    data class Failure(val message: String, val detail: String?) : RenderOutcome
    data class Unsupported(val reason: String) : RenderOutcome
}
```

- [ ] **Step 2: Resolve U1 — `AndroidBuildTargetReference` from the module**

In `RenderModelResolver.kt`, find how a `RenderModelModule` is built for a module. Start from the probed
`AndroidFacetRenderModelModule(AndroidBuildTargetReference)`. Determine how AS constructs an
`AndroidBuildTargetReference` (look for `AndroidBuildTargetReference.from(...)` / a companion factory taking an
`AndroidFacet` or `Module`, via `javap` on the jars). Implement:

```kotlin
fun resolveModule(entry: PreviewEntry, project: Project): RenderModelModule?
```

returning null (→ caller yields `Unsupported`) when the module has no Android facet. Guard every AS-internal call
with try/catch for `Exception`/`LinkageError`.

Run: `./gradlew compileKotlin` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Resolve U5 — default `PreviewConfiguration` + `PreviewDisplaySettings`**

Determine the factory/defaults for `PreviewConfiguration` and `PreviewDisplaySettings` (via `javap`; look for a
`cleanAndGet`, a no-arg default, or a companion). Build a `SingleComposePreviewElementInstance` from
`entry.composableFqn` + these defaults. Keep it in `RenderModelResolver`.

Run: `./gradlew compileKotlin` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Resolve U2/U3/U4 — the render call and the image**

In `LiveRenderer.render(entry)`, assemble the call sequence from spec §5.2 using Steps 2–3, resolving:
- **U2:** how the `ComposeViewAdapter` bridge is fed. Investigate whether feeding the
  `SingleComposePreviewElementInstance` through the compose preview path drives it, or whether an explicit
  `ILayoutPullParserFactory` producing the `tools:composableName` XML is needed via `.withParserFactory(...)`.
- **U3:** the `IImageFactory` for `render(...)` — look for a default/pooled factory, or pass one that allocates a
  `BufferedImage` of the requested size.
- **U4:** inside `processImageIfNotDisposed { pooled -> ... }`, copy the pixels into a standalone `BufferedImage`
  **before** `dispose()`, since the pooled image is invalid afterward.

Enforce: render off the EDT, PSI under a read action, a 30 s timeout on each future, and `task.dispose()` in a
`finally`. Map results to `RenderOutcome`. Return `Unsupported` when `isAvailable()` is false or the module has
no facet; `Failure` on timeout/exception/`LinkageError`.

Run: `./gradlew compileKotlin` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: runIde vertical-slice verification (the gate)**

This step needs the controller/user: it runs the real IDE against a real project.

Add a temporary action (removed before commit) — "Render selected preview to /tmp" — that calls
`LiveRenderer(project).render(entry)` off the EDT for the currently-selected gallery entry and writes
`Success.image` to a PNG via `ImageIO`.

Run: `./gradlew runIde`. Open a real multi-module Compose project, build it once, select a **built** androidx
preview, trigger the action. Expected: a non-blank PNG of the composable at `/tmp`. Record: did it render? the
outcome type, and for any `Failure`, the exact `detail`.

**If it renders:** proceed. **If it does not after genuine effort on U2–U4:** stop, report BLOCKED with the exact
failure and what was tried — do not fake a success. The controller decides between more investigation and the
§11.1 fallback.

- [ ] **Step 6: Memory-leak check (spec AC7 / S4)**

With the temporary action, render 20 times in a row (same or different previews). Watch the IDE's memory
indicator / a heap dump before and after. Expected: no monotonic growth of hundreds of MB — confirms
`dispose()` releases layoutlib contexts. Record the before/after figures.

- [ ] **Step 7: Remove the temporary action and commit**

Delete the scratch action and any temporary logging. Run `./gradlew compileKotlin` (Expected: BUILD SUCCESSFUL),
then:

```bash
git add src/main/kotlin/com/devomer/previewgallery/render src/main/kotlin/com/devomer/previewgallery/model/RenderOutcome.kt
git commit -m "[PG2-2] - Render a preview to an image via layoutlib"
```

---

### Task 3: Render config and state model

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/model/RenderConfig.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/render/RenderState.kt`

**Interfaces:**
- Produces: `RenderConfig` (data class with a `DEFAULT`); `enum class RenderState { RENDERING, LIVE, NEEDS_BUILD, FAILED, UNSUPPORTED }`.

- [ ] **Step 1: Write `RenderConfig`**

```kotlin
package com.devomer.previewgallery.model

/** A fixed default in the MVP; the seam the later device/theme slice widens. */
data class RenderConfig(
    val deviceSpec: String = "spec:width=411dp,height=891dp",
    val themeName: String? = null,
    val uiMode: Int = 0,
    val apiLevel: Int = -1,
    val fontScale: Float = 1.0f,
) {
    companion object { val DEFAULT = RenderConfig() }
}
```

- [ ] **Step 2: Write `RenderState`**

```kotlin
package com.devomer.previewgallery.render

enum class RenderState { RENDERING, LIVE, NEEDS_BUILD, FAILED, UNSUPPORTED }
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/model/RenderConfig.kt src/main/kotlin/com/devomer/previewgallery/render/RenderState.kt
git commit -m "[PG2-3] - Render config and state model"
```

---

### Task 4: `ModuleFreshness`

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/render/ModuleFreshness.kt`

**Interfaces:**
- Produces:
  - `ModuleFreshness.isFresh(newestSourceMtime: Long, newestClassMtime: Long): Boolean` — pure, testable.
  - `ModuleFreshness.isModuleFresh(module: Module): Boolean` — resolves the two mtimes from the module's source roots and compile output, then delegates to the pure function.

- [ ] **Step 1: Write the pure freshness rule and the module bridge**

```kotlin
package com.devomer.previewgallery.render

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.CompilerModuleExtension
import java.io.File

/**
 * A module's compiled output is "fresh" when no source file is newer than the newest class file. Cheap and
 * adequate (spec §6): a wrong answer costs at most one redundant build or one NEEDS_BUILD prompt.
 */
object ModuleFreshness {

    /** @return false (stale) when there is no class output at all, i.e. newestClassMtime <= 0. */
    fun isFresh(newestSourceMtime: Long, newestClassMtime: Long): Boolean =
        newestClassMtime > 0 && newestSourceMtime <= newestClassMtime

    fun isModuleFresh(module: Module): Boolean {
        val sourceRoots = ModuleRootManager.getInstance(module).sourceRoots
        val output = CompilerModuleExtension.getInstance(module)?.compilerOutputPath
        val newestSource = sourceRoots.maxOfOrNull { newestMtime(File(it.path)) } ?: 0L
        val newestClass = output?.let { newestMtime(File(it.path)) } ?: 0L
        return isFresh(newestSource, newestClass)
    }

    private fun newestMtime(root: File): Long {
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: 0L
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render/ModuleFreshness.kt
git commit -m "[PG2-4] - Module freshness heuristic"
```

---

### Task 5: `BuildService`

**Goal:** Compile one module on demand through the IDE's Gradle integration, single-flight, cancellable, never a
second daemon. Verified in `runIde`.

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/render/BuildService.kt`

**Interfaces:**
- Produces:
  - `BuildService.getInstance(project): BuildService` (project `@Service`).
  - `fun build(module: Module, onDone: (Boolean) -> Unit)` — runs `:module:compileDebugKotlin` via
    `ExternalSystemUtil.runTask`; cancels any in-flight build first; calls `onDone(success)` on completion; a
    no-op returning `onDone(false)` while `DumbService.isDumb`.

- [ ] **Step 1: Write `BuildService`**

Use `ExternalSystemUtil.runTask` with `GradleConstants.SYSTEM_ID` and a `TaskCallback`. Derive the Gradle task
path from the module (the `:a:b:module` path + `:compileDebugKotlin`). Hold the current `ExternalSystemTaskId`
and cancel it via `ExternalSystemUtil.cancelTask` (or the task-manager) when a new build starts. Gate on
`DumbService.isDumb`. Because the exact `ExternalSystemUtil.runTask` overload and the module→gradle-path
derivation are platform-version-sensitive, verify both against the SDK jars (`javap`) before writing, and guard
the AS-internal calls.

Run: `./gradlew compileKotlin` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: runIde verification — builds, cancels, one daemon**

Run: `./gradlew runIde`. From a temporary action (removed before commit), call `build(module) { ... }` for an
un-built module. Confirm: (a) the standard IDE Gradle progress UI appears and is cancellable; (b) after it
finishes, the module's classes exist; (c) triggering two builds in quick succession cancels the first; (d) via
`jps`/Activity Monitor, **no second Gradle daemon process** appears beyond the IDE's own. Record all four.

- [ ] **Step 3: Remove the temporary action and commit**

Run `./gradlew compileKotlin` (Expected: BUILD SUCCESSFUL), then:

```bash
git add src/main/kotlin/com/devomer/previewgallery/render/BuildService.kt
git commit -m "[PG2-5] - Build-on-demand via the IDE Gradle integration"
```

---

### Task 6: `RenderPipeline`

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/render/RenderPipeline.kt`

**Interfaces:**
- Consumes: `LiveRenderer`, `ModuleFreshness`, `BuildService`, `PreviewEntry`, `RenderOutcome`, `RenderState`.
- Produces:
  - `RenderResultView` — `data class RenderResultView(val state: RenderState, val outcome: RenderOutcome?, val moduleName: String?)`.
  - `RenderPipeline.select(entry: PreviewEntry?)` — debounced 400 ms, cancels in-flight, classifies, renders when fresh.
  - `RenderPipeline.requestBuildAndRender(entry: PreviewEntry)` — the Render button path.
  - A listener seam `onStateChange: (RenderResultView) -> Unit` the panel subscribes to.
  - The classification is factored into a pure function `classify(entry, isFresh): RenderState` for testing.

- [ ] **Step 1: Write the pure classifier and the pipeline**

```kotlin
package com.devomer.previewgallery.render

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.RenderOutcome
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil

data class RenderResultView(val state: RenderState, val outcome: RenderOutcome?, val moduleName: String?)

/**
 * Orchestrates selection -> render. Selection never builds (spec B3); the Render button does. Debounced with
 * in-flight cancellation so arrow-keying through previews queues no work.
 */
class RenderPipeline(
    private val project: Project,
    private val renderer: LiveRenderer,
    private val build: BuildService,
    parentDisposable: Disposable,
    private val onStateChange: (RenderResultView) -> Unit,
) {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
    @Volatile private var generation = 0

    companion object {
        const val DEBOUNCE_MS = 400

        /** Pure: the state a selection lands in before any async render runs. Booleans, not a PreviewEntry, so
         *  it is unit-testable without a VirtualFile fixture. */
        fun classify(unsupported: Boolean, hasPreviewParameter: Boolean, isFresh: Boolean): RenderState = when {
            unsupported -> RenderState.UNSUPPORTED
            hasPreviewParameter -> RenderState.UNSUPPORTED
            isFresh -> RenderState.RENDERING
            else -> RenderState.NEEDS_BUILD
        }
    }

    fun select(entry: PreviewEntry?) {
        alarm.cancelAllRequests()
        val gen = ++generation
        if (entry == null) {
            onStateChange(RenderResultView(RenderState.UNSUPPORTED, null, null))
            return
        }
        alarm.addRequest({ dispatch(entry, gen) }, DEBOUNCE_MS)
    }

    private fun dispatch(entry: PreviewEntry, gen: Int) {
        val fresh = ReadAction.compute<Boolean, RuntimeException> {
            val module = ModuleManager.getInstance(project).findModuleByName(entry.moduleName)
            module != null && ModuleFreshness.isModuleFresh(module)
        }
        val state = classify(entry.indexed.unsupportedReason != null, entry.indexed.hasPreviewParameter, fresh)
        when (state) {
            RenderState.UNSUPPORTED ->
                onStateChange(RenderResultView(RenderState.UNSUPPORTED, unsupportedOutcome(entry), entry.moduleName))
            RenderState.NEEDS_BUILD ->
                onStateChange(RenderResultView(RenderState.NEEDS_BUILD, null, entry.moduleName))
            RenderState.RENDERING -> render(entry, gen)
            else -> {}
        }
    }

    fun requestBuildAndRender(entry: PreviewEntry) {
        val gen = ++generation
        onStateChange(RenderResultView(RenderState.RENDERING, null, entry.moduleName))
        if (DumbService.isDumb(project)) {
            onStateChange(RenderResultView(RenderState.FAILED, RenderOutcome.Failure("Indexing", null), entry.moduleName))
            return
        }
        val module = ModuleManager.getInstance(project).findModuleByName(entry.moduleName) ?: run {
            onStateChange(RenderResultView(RenderState.FAILED, RenderOutcome.Failure("Module not found", null), entry.moduleName))
            return
        }
        build.build(module) { success ->
            if (gen != generation) return@build
            if (success) render(entry, gen)
            else onStateChange(RenderResultView(RenderState.FAILED, RenderOutcome.Failure("Build failed", null), entry.moduleName))
        }
    }

    private fun render(entry: PreviewEntry, gen: Int) {
        onStateChange(RenderResultView(RenderState.RENDERING, null, entry.moduleName))
        ReadAction.nonBlocking<RenderOutcome> { renderer.render(entry) }
            .finishOnUiThread(ModalityState.defaultModalityState()) { outcome ->
                if (gen != generation) return@finishOnUiThread
                val state = when (outcome) {
                    is RenderOutcome.Success -> RenderState.LIVE
                    is RenderOutcome.Failure -> RenderState.FAILED
                    is RenderOutcome.Unsupported -> RenderState.UNSUPPORTED
                }
                onStateChange(RenderResultView(state, outcome, entry.moduleName))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun unsupportedOutcome(entry: PreviewEntry): RenderOutcome.Unsupported = RenderOutcome.Unsupported(
        entry.indexed.unsupportedReason ?: if (entry.indexed.hasPreviewParameter) "@PreviewParameter is not supported" else "Unsupported",
    )
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render/RenderPipeline.kt
git commit -m "[PG2-6] - Render pipeline orchestration"
```

---

### Task 7: `PreviewRenderPanel`

**Goal:** Replace `PreviewRenderPlaceholder` with a panel that renders the five states.

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt`
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties`

**Interfaces:**
- Consumes: `RenderResultView`, `RenderState`, `RenderOutcome`, `PreviewEntry`, `PreviewGalleryBundle`.
- Produces: `PreviewRenderPanel(project)` with `fun show(view: RenderResultView, entry: PreviewEntry?)`, and callbacks `onRender: (PreviewEntry) -> Unit`, `onOpenFile: (PreviewEntry) -> Unit`.

- [ ] **Step 1: Add message keys**

Append to `src/main/resources/messages/PreviewGalleryBundle.properties`:

```properties
render.rendering=Rendering…
render.building=Building module…
render.needsBuild=Module not built
render.render=Render
render.failed=Render failed
render.unsupported=Cannot render this preview
render.showLog=Details
```

- [ ] **Step 2: Write the panel**

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.RenderOutcome
import com.devomer.previewgallery.render.RenderResultView
import com.devomer.previewgallery.render.RenderState
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Image
import java.awt.image.BufferedImage
import javax.swing.ImageIcon
import javax.swing.SwingConstants

/** The lower splitter half. Replaces PreviewRenderPlaceholder. Shows the five render states. */
class PreviewRenderPanel(private val project: Project) : JBPanel<PreviewRenderPanel>(BorderLayout()) {

    var onRender: (PreviewEntry) -> Unit = {}
    var onOpenFile: (PreviewEntry) -> Unit = {}

    private val imageLabel = JBLabel("", SwingConstants.CENTER)
    private var currentImage: BufferedImage? = null

    init {
        border = JBUI.Borders.empty(8)
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = rescale()
        })
    }

    fun show(view: RenderResultView, entry: PreviewEntry?) {
        removeAll()
        currentImage = null
        when (view.state) {
            RenderState.RENDERING -> center(JBLabel(PreviewGalleryBundle.message("render.rendering")))
            RenderState.LIVE -> showImage((view.outcome as? RenderOutcome.Success)?.image)
            RenderState.NEEDS_BUILD -> center(needsBuild(entry))
            RenderState.FAILED -> center(failed(view.outcome as? RenderOutcome.Failure, entry))
            RenderState.UNSUPPORTED -> center(unsupported(view.outcome as? RenderOutcome.Unsupported, entry))
        }
        revalidate(); repaint()
    }

    private fun showImage(image: BufferedImage?) {
        if (image == null) { center(JBLabel(PreviewGalleryBundle.message("render.failed"))); return }
        currentImage = image
        add(imageLabel, BorderLayout.CENTER)
        rescale()
    }

    private fun rescale() {
        val img = currentImage ?: return
        val w = (width - 16).coerceAtLeast(1); val h = (height - 16).coerceAtLeast(1)
        val scale = minOf(w.toDouble() / img.width, h.toDouble() / img.height, 1.0)
        val sw = (img.width * scale).toInt().coerceAtLeast(1); val sh = (img.height * scale).toInt().coerceAtLeast(1)
        imageLabel.icon = ImageIcon(img.getScaledInstance(sw, sh, Image.SCALE_SMOOTH))
    }

    private fun needsBuild(entry: PreviewEntry?): JBPanel<*> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(JBLabel(PreviewGalleryBundle.message("render.needsBuild")), BorderLayout.NORTH)
        if (entry != null) add(ActionLink(PreviewGalleryBundle.message("render.render")) { onRender(entry) }, BorderLayout.CENTER)
    }

    private fun failed(outcome: RenderOutcome.Failure?, entry: PreviewEntry?): JBPanel<*> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(JBLabel("${PreviewGalleryBundle.message("render.failed")}: ${outcome?.message ?: ""}"), BorderLayout.NORTH)
        if (entry != null) add(ActionLink(PreviewGalleryBundle.message("detail.openFile")) { onOpenFile(entry) }, BorderLayout.SOUTH)
    }

    private fun unsupported(outcome: RenderOutcome.Unsupported?, entry: PreviewEntry?): JBPanel<*> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(JBLabel(outcome?.reason ?: PreviewGalleryBundle.message("render.unsupported")), BorderLayout.NORTH)
        if (entry != null) add(ActionLink(PreviewGalleryBundle.message("detail.openFile")) { onOpenFile(entry) }, BorderLayout.SOUTH)
    }

    private fun center(component: javax.swing.JComponent) {
        add(JBPanel<JBPanel<*>>(BorderLayout()).apply { add(component, BorderLayout.CENTER) }, BorderLayout.CENTER)
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt src/main/resources/messages/PreviewGalleryBundle.properties
git commit -m "[PG2-7] - Preview render panel with the five states"
```

---

### Task 8: Wire the pipeline into `PreviewGalleryPanel`

**Goal:** Selecting a leaf drives the render pipeline; the render panel replaces the placeholder in the splitter.

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`
- Delete: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPlaceholder.kt`

**Interfaces:**
- Consumes: `RenderPipeline`, `PreviewRenderPanel`, `LiveRenderer`, `BuildService`.

- [ ] **Step 1: Replace the placeholder with the render panel and pipeline**

In `PreviewGalleryPanel`:
- Construct `LiveRenderer(project)`, `BuildService.getInstance(project)`, a `PreviewRenderPanel(project)`, and a
  `RenderPipeline(project, renderer, build, parentDisposable) { view -> renderPanel.show(view, lastSelectedEntry) }`.
- Set the outer splitter's `secondComponent` to the `PreviewRenderPanel` instead of `PreviewRenderPlaceholder`.
- Wire `renderPanel.onRender = { pipeline.requestBuildAndRender(it) }` and
  `renderPanel.onOpenFile = { OpenFileDescriptor(project, it.file, it.indexed.offset).navigate(true) }`.
- In the existing tree-selection listener, after `detailPanel.show(selectedEntry())`, add
  `pipeline.select(selectedEntry())` and remember `lastSelectedEntry = selectedEntry()`.

Show the exact edits against the current file (read it first). Keep Task 9's `Disposer.isDisposed` guard and all
Phase 1 behaviour intact.

- [ ] **Step 2: Delete the placeholder**

```bash
git rm src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPlaceholder.kt
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. Fix any remaining reference to `PreviewRenderPlaceholder`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt
git commit -m "[PG2-8] - Wire the render pipeline into the tool window"
```

---

### Task 9: Test suite (pure components)

**Goal:** Automated tests for every component that is not AS-internal. The render/build paths are covered by the
manual verification in Task 10, per the plan's verification-style note.

**Files:**
- Create: `src/test/kotlin/com/devomer/previewgallery/render/ModuleFreshnessTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/render/RenderPipelineClassifyTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/model/RenderConfigTest.kt`

- [ ] **Step 1: `ModuleFreshnessTest` (plain JUnit)**

```kotlin
package com.devomer.previewgallery.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleFreshnessTest {
    @Test fun `class newer than source is fresh`() { assertTrue(ModuleFreshness.isFresh(100L, 200L)) }
    @Test fun `source newer than class is stale`() { assertFalse(ModuleFreshness.isFresh(200L, 100L)) }
    @Test fun `equal mtimes count as fresh`() { assertTrue(ModuleFreshness.isFresh(100L, 100L)) }
    @Test fun `no class output is stale`() { assertFalse(ModuleFreshness.isFresh(100L, 0L)) }
    @Test fun `empty module (no source, no class) is stale`() { assertFalse(ModuleFreshness.isFresh(0L, 0L)) }
}
```

- [ ] **Step 2: `RenderPipelineClassifyTest` (plain JUnit, reusing Phase 1's `testRow`/fake)**

`classify` already takes three booleans (Task 6), so this needs no fixture:

```kotlin
package com.devomer.previewgallery.render

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderPipelineClassifyTest {
    private fun classify(unsupported: Boolean, hasParam: Boolean, fresh: Boolean) =
        RenderPipeline.classify(unsupported, hasParam, fresh)   // three-boolean signature, no fixture needed

    @Test fun `unsupported reason wins`() {
        assertEquals(RenderState.UNSUPPORTED, classify(unsupported = true, hasParam = false, fresh = true))
    }
    @Test fun `preview parameter is unsupported`() {
        assertEquals(RenderState.UNSUPPORTED, classify(unsupported = false, hasParam = true, fresh = true))
    }
    @Test fun `fresh supported preview renders`() {
        assertEquals(RenderState.RENDERING, classify(unsupported = false, hasParam = false, fresh = true))
    }
    @Test fun `stale supported preview needs build`() {
        assertEquals(RenderState.NEEDS_BUILD, classify(unsupported = false, hasParam = false, fresh = false))
    }
}
```

- [ ] **Step 3: `RenderConfigTest` (plain JUnit)**

```kotlin
package com.devomer.previewgallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenderConfigTest {
    @Test fun `default values`() {
        val c = RenderConfig.DEFAULT
        assertEquals("spec:width=411dp,height=891dp", c.deviceSpec)
        assertNull(c.themeName)
        assertEquals(1.0f, c.fontScale, 0.0f)
    }
}
```

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew test`
Expected: PASS — the Phase 1 tests (77) plus the new ones, all green, no skips.

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/com/devomer/previewgallery
git commit -m "[PG2-9] - Tests for the pure render components"
```

---

### Task 10: Manual verification (AC1–AC9)

**Files:**
- Modify: `CHANGELOG.md`

This task covers the acceptance criteria the pure tests cannot: the real render path, build-on-demand, cancel,
leak, and the no-second-daemon guarantee (spec §10, §12).

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests passing.

- [ ] **Step 2: Launch and open a real project**

Run: `./gradlew runIde`. Open a real multi-module Compose project.

- [ ] **Step 3: AC1 — a built preview renders**

Build the project once. Open **Compose Gallery**, select a built androidx preview. Expected: its image appears in
the lower panel.

- [ ] **Step 4: AC2 — build-on-demand**

Select a preview in a module that has not been built (e.g. after a clean). Expected: `NEEDS_BUILD` + a **Render**
button; clicking it shows "Building…", then the image.

- [ ] **Step 5: AC3 — debounce + cancel**

Arrow-key quickly through several previews. Expected: no pile-up of renders/builds; only the last selection
renders.

- [ ] **Step 6: AC4/AC5 — unsupported and failure**

Select a `@PreviewParameter` preview → `UNSUPPORTED`. Select a class-nested preview → `UNSUPPORTED`. Force a
failure (e.g. a preview whose module fails to build) → `FAILED` with a log and **Open file**; the plugin stays
usable.

- [ ] **Step 7: AC6/AC7 — no second daemon, no leak**

During a build, check `jps` / Activity Monitor: no second Gradle daemon beyond the IDE's. Render 20 previews in a
row; confirm no monotonic hundreds-of-MB growth.

- [ ] **Step 8: AC8 — graceful degradation**

(If feasible) simulate the probe failing by temporarily pointing a probe class name at a nonexistent class in a
scratch build; confirm previews show `UNSUPPORTED` and Phase 1 discovery still works, then revert. Otherwise note
this is covered by the probe's design.

- [ ] **Step 9: AC9 — clean log**

`grep -i "com.devomer.previewgallery" build/idea-sandbox/*/log/idea.log` — no ERROR/WARN from the plugin.

- [ ] **Step 10: Update the changelog and commit**

Add a `## [Unreleased]` "Live preview rendering" section to `CHANGELOG.md` listing: render on select, build-on-
demand, the five states, and the known gaps (no cache, fixed config, multiplatform unproven). Commit:

```bash
git add CHANGELOG.md
git commit -m "[PG2-10] - Verify Phase 2 MVP against a real project"
```
