# screenshotTest Render Calibration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** measure how far the IDE's own render of a `@PreviewTest` composable is from the golden Gradle committed
for it, so F5's diff half is designed against a proven premise instead of an assumed one.

**Architecture:** four small pieces and one button. A pure locator decides whether the module's compiled
`screenshotTest` classes exist and are current. An Android-Studio-internal composition (the one the classloader
spike proved reachable) puts that classes directory on the render classpath by wrapping the `RenderModelModule`
the resolver already builds. The existing `LiveRenderer` then renders the snapshot function unchanged. A pure
`ImageDiff` measures the result against the committed golden and against the `rendered` PNG the last `validate`
run wrote, and the numbers ride the reference strip the pane already shows.

**Tech Stack:** Kotlin, IntelliJ Platform, Android Studio internal render API (`StudioModuleRenderContext`,
`StudioModuleClassLoaderManager`, `ProjectSystemClassLoader`), JUnit via `BasePlatformTestCase`.

## Global Constraints

- **Commit prefix `PG22-N`**, message form `[PG22-N] - Task name`, blank line, then a `Co-Authored-By:` trailer
  naming the model that actually wrote the commit (this repo's convention — PG20 and PG21 both carry per-commit
  authors).
- **No `!!`.** No inline code comments — this codebase records *why* in KDoc only.
- **Degrade, never break** (spec D8): every Android-Studio-internal call is guarded against `Exception` **and**
  `LinkageError` and falls back to the pane the row shows today. `LiveRenderer`, `RenderModelResolver` and
  `ModuleFreshness.gradleBuildOutputDir` are the established examples; copy their shape.
- **Pure stays pure** (spec D9): `ImageDiff` and `ScreenshotTestClasses` import no `com.intellij` and no
  `com.android` types. They sit beside `RenderedImageInspector`, which is pure for the same reason.
- **Never run `./gradlew` while a `runIde` sandbox is live.** Check with exactly
  `pgrep -f "idea.plugin.in.sandbox.mode=true"` and `pgrep -f "gradlew.*runIde"` before every Gradle invocation; if
  either prints a pid, stop and report. Do **not** run `./gradlew runIde` — the human runs the gate.
- **Implementation first, tests last** (the user's `CLAUDE.md`): tasks 1–4 ship production code with no test cycle;
  task 5 is the single test phase; task 6 is review. The gate that follows *is* the calibration.
- Suite is 556 tests at the branch point, all passing.
- Do not push. Commit on `main`, this repo's convention.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `src/main/kotlin/com/devomer/previewgallery/render/ImageDiff.kt` | Create | Two images → size mismatch, or a differing-pixel count and percentage |
| `src/main/kotlin/com/devomer/previewgallery/render/ScreenshotTestClasses.kt` | Create | Where the compiled screenshotTest classes are, and whether they are current |
| `src/main/kotlin/com/devomer/previewgallery/render/ScreenshotTestClassLoader.kt` | Create | The AS-internal composition that puts that directory on the render classpath |
| `src/main/kotlin/com/devomer/previewgallery/render/RenderModelResolver.kt` | Modify (`resolve`, `resolveUnderReadAction`) | Applies an optional module wrapper to the `AndroidFacetRenderModelModule` it builds |
| `src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt` | Modify (`render`) | Forwards that wrapper |
| `src/main/kotlin/com/devomer/previewgallery/ui/CompareLiveRenderAction.kt` | Create | Toolbar action, visible only on a snapshot row |
| `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` | Modify | Orchestrates the flow and publishes through the existing strip |
| `src/main/resources/messages/PreviewGalleryBundle.properties` | Modify | Five keys |
| `src/test/kotlin/com/devomer/previewgallery/render/ImageDiffTest.kt` | Create | Task 5 |
| `src/test/kotlin/com/devomer/previewgallery/render/ScreenshotTestClassesTest.kt` | Create | Task 5 |

---

### Task 1: `ImageDiff` — the measurement

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/render/ImageDiff.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `ImageDiff.compare(left: BufferedImage, right: BufferedImage): ImageDiff.Result`, with
  `Result.SizeMismatch(left: Size, right: Size)` and `Result.Measured(differingPixels: Long, totalPixels: Long)`
  carrying `percent: Double`. `Size(width: Int, height: Int)` renders as `1080x2340`.

- [ ] **Step 1: Write the file**

```kotlin
package com.devomer.previewgallery.render

import java.awt.image.BufferedImage

/**
 * How far two renders of the same composable are apart.
 *
 * The metric is the engine's own (spec D6): the share of differing pixels, so a number this produces and a number
 * from the Android screenshot engine's report (`0.111% different`) mean the same thing and can be read side by
 * side. A perceptual measure with its own antialiasing tolerance would answer a different question and could not
 * be checked against the engine at all.
 *
 * A size mismatch is a separate result rather than 100%: two images of different sizes have no per-pixel
 * relationship to average, and in this feature it is also the signal that the *wrong variant* was rendered — the
 * project's `@SnapshotPreviews` multipreview draws `phone` and a 320dp `small`, and comparing one against the
 * other's golden would otherwise read as an engine disagreement.
 *
 * Pure, beside [RenderedImageInspector] and for the same reason: pixel logic with no platform coupling is the part
 * of the render path that can be tested headlessly.
 *
 * `getRGB` per pixel over a device-resolution image is a few tens of milliseconds — fine off the EDT, which is
 * where the only caller runs it, and not worth a raster fast path until something measures it as a problem.
 */
internal object ImageDiff {

    data class Size(val width: Int, val height: Int) {
        override fun toString(): String = "${width}x$height"
    }

    sealed interface Result {

        data class SizeMismatch(val left: Size, val right: Size) : Result

        /** [percent] is on the same scale the screenshot engine prints: 0.5 means half a percent of pixels differ. */
        data class Measured(val differingPixels: Long, val totalPixels: Long) : Result {
            val percent: Double get() = if (totalPixels == 0L) 0.0 else differingPixels * 100.0 / totalPixels
        }
    }

    fun compare(left: BufferedImage, right: BufferedImage): Result {
        if (left.width != right.width || left.height != right.height) {
            return Result.SizeMismatch(Size(left.width, left.height), Size(right.width, right.height))
        }
        var differing = 0L
        for (y in 0 until left.height) {
            for (x in 0 until left.width) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) differing++
            }
        }
        return Result.Measured(differing, left.width.toLong() * left.height)
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render/ImageDiff.kt
git commit -m "$(cat <<'EOF'
[PG22-1] - Measure the distance between two renders

Co-Authored-By: <the model that wrote this> <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `ScreenshotTestClasses` — where the classes are, and whether they are current

The directory layout is confirmed on disk in the reference project:

```
features/favorites/ui/build/intermediates/built_in_kotlinc/debugScreenshotTest/compileDebugScreenshotTestKotlin/classes/
  com/hepsiburada/ui/feature/favorites/util/UtilSnapshotsKt.class
  …
```

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/render/ScreenshotTestClasses.kt`

**Interfaces:**
- Consumes: nothing (the caller supplies the module directory, the build variant and a source-clock value).
- Produces: `ScreenshotTestClasses.directoryFor(moduleDirectory: File, buildVariant: String): File`,
  `ScreenshotTestClasses.stateOf(directory: File, newestSourceMillis: Long?): State` with `State.Ready(directory)`,
  `State.Missing`, `State.Stale(directory)`, and `ScreenshotTestClasses.newestClassMtime(directory: File): Long`.

- [ ] **Step 1: Write the file**

```kotlin
package com.devomer.previewgallery.render

import java.io.File

/**
 * The compiled `screenshotTest` classes of one module and build variant, and whether they still describe the
 * source on disk.
 *
 * The path is AGP's, confirmed against the reference project:
 * `build/intermediates/built_in_kotlinc/<variant>ScreenshotTest/compile<Variant>ScreenshotTestKotlin/classes`.
 * The variant appears twice in two different casings — lower-camel in the source-set directory, upper-camel inside
 * the task name — which is why [directoryFor] takes the upper-camel form the rest of this plugin already carries
 * ([com.devomer.previewgallery.service.ReferenceRoots.Root.buildVariant], the same value
 * [com.devomer.previewgallery.render.SnapshotVerifyRunner.validateTask] builds its task name from) and lowers the
 * first character itself.
 *
 * **Newest `.class` file, not the directory's own mtime** ([newestClassMtime]). A directory's timestamp moves when
 * an entry is added or removed and stays put when a file is overwritten in place, which is precisely what an
 * incremental recompile does — the same trap [ModuleFreshness.newestMtimeBounded] documents from the other side.
 *
 * A null source clock reads [State.Stale], not [State.Ready]: "nothing could be read" and "nothing has changed"
 * are different facts, and this feature exists to produce a trustworthy number (spec D5). The same direction
 * [com.devomer.previewgallery.service.SnapshotVerifyStore.isStale] already takes for the same reason.
 */
internal object ScreenshotTestClasses {

    sealed interface State {
        data class Ready(val directory: File) : State
        object Missing : State
        data class Stale(val directory: File) : State
    }

    fun directoryFor(moduleDirectory: File, buildVariant: String): File {
        val sourceSet = buildVariant.replaceFirstChar { it.lowercaseChar() } + "ScreenshotTest"
        val task = "compile${buildVariant}ScreenshotTestKotlin"
        return File(moduleDirectory, "build/intermediates/built_in_kotlinc/$sourceSet/$task/classes")
    }

    fun stateOf(directory: File, newestSourceMillis: Long?): State {
        val newestClass = newestClassMtime(directory)
        if (newestClass <= 0L) return State.Missing
        if (newestSourceMillis == null || newestSourceMillis > newestClass) return State.Stale(directory)
        return State.Ready(directory)
    }

    /** 0 when the directory holds no class file at all, which callers must read as "not compiled" rather than as
     *  "compiled long ago". */
    fun newestClassMtime(directory: File): Long {
        if (!directory.isDirectory) return 0L
        return directory.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .maxOfOrNull { it.lastModified() }
            ?: 0L
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render/ScreenshotTestClasses.kt
git commit -m "$(cat <<'EOF'
[PG22-2] - Find the compiled screenshotTest classes

Co-Authored-By: <the model that wrote this> <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Put that directory on the render classpath

This is the task the two spikes exist for. Read
`docs/superpowers/specs/2026-08-10-render-classloader-spike.md` **before writing anything** — it carries the
composition, the javap-confirmed API table, and the three things it could not prove.

**The seam:** `RenderModelResolver.resolveUnderReadAction` builds `AndroidFacetRenderModelModule(buildTarget)` at
line 116 and puts it in `Resolved`. The spike confirmed at runtime that the render asks *that* object for its class
loader. So the injection point is a wrapper around that one value, and nothing deeper has to be reimplemented.

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/render/ScreenshotTestClassLoader.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderModelResolver.kt` (`resolve` signature at :78,
  `resolveUnderReadAction` signature and its `renderModule` line at :116)
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt` (`render` at :77)

**Interfaces:**
- Consumes: `ScreenshotTestClasses.State.Ready.directory` from Task 2.
- Produces:
  - `typealias RenderModuleWrapper = (RenderModelModule) -> RenderModelModule` (declared in
    `ScreenshotTestClassLoader.kt`)
  - `ScreenshotTestClassLoader.wrapperFor(classesDirectory: File): RenderModuleWrapper?` — null when the
    composition cannot be built on this IDE build, which the caller treats as "render normally".
  - `RenderModelResolver.resolve(entry, project, override, moduleWrapper: RenderModuleWrapper? = null)`
  - `LiveRenderer.render(entry, override, moduleWrapper: RenderModuleWrapper? = null)`

- [ ] **Step 1: Confirm the four symbols against this IDE build**

The spike's table was taken with `javap` and is the best available evidence, but package names are exactly the kind
of thing that moves between Android Studio releases. Confirm before writing, and record what you found in your
report:

```bash
AS="/Users/odurmaz/Applications/Android Studio.app/Contents/plugins/android/lib"
for c in StudioModuleRenderContext StudioModuleClassLoaderManager ProjectSystemClassLoader ClassContent; do
  echo "== $c"
  for j in "$AS"/*.jar; do unzip -l "$j" 2>/dev/null | grep -m1 "/$c.class" && echo "   in $j"; done
done
```

Then `javap -classpath <that jar> <fqcn>` for each, and check: `StudioModuleRenderContext` is open with
`createInjectableClassLoaderLoader()`; `StudioModuleClassLoaderManager.getPrivate(parent, context, transform,
transform)` exists and returns a `Reference`; `ProjectSystemClassLoader` takes a `Function1<String, ClassContent>`;
`ClassContent.loadFromFile(File)` is public and static.

**If a signature differs from the spike's table, that is the finding — report it and stop.** Do not guess an
alternative API: the whole point of this phase is a trustworthy answer, and a chain assembled from guesses cannot
give one.

- [ ] **Step 2: Write `ScreenshotTestClassLoader.kt`**

Using the FQNs confirmed in step 1. The shape, with the spike's composition:

```kotlin
package com.devomer.previewgallery.render

import com.android.tools.rendering.api.RenderModelModule
import com.intellij.openapi.diagnostic.thisLogger
import java.io.File

typealias RenderModuleWrapper = (RenderModelModule) -> RenderModelModule

/**
 * Puts a module's compiled `screenshotTest` classes on the render classpath, so a `@PreviewTest` composable can be
 * rendered inside the IDE at all.
 *
 * Two spikes stand behind this. The first
 * (`docs/superpowers/specs/2026-08-10-screenshottest-render-spike.md`) found that everything up to the class load
 * succeeds and only the classpath is missing a directory. The second
 * (`docs/superpowers/specs/2026-08-10-render-classloader-spike.md`) confirmed at runtime that the render asks the
 * plugin's own [RenderModelModule] for its class loader, and by `javap` that every piece below it is public — so
 * this composes rather than reimplements. `ModuleClassLoader` is abstract with about ten abstract members and none
 * of them is implemented here; the manager builds the real loader.
 *
 * A **private** loader per render, released when the render ends: a shared loader carrying `screenshotTest`
 * classes would leak test classes into the class cache every ordinary `@Preview` render then reuses.
 *
 * Returns null rather than throwing when the composition cannot be built on this IDE build. Every caller treats
 * null as "render the ordinary way", which is the degrade-don't-break posture the rest of `render/` holds.
 */
internal object ScreenshotTestClassLoader {

    fun wrapperFor(classesDirectory: File): RenderModuleWrapper? = try {
        buildWrapper(classesDirectory)
    } catch (e: Exception) {
        thisLogger().warn("Could not compose a screenshotTest class loader for $classesDirectory", e)
        null
    } catch (e: LinkageError) {
        thisLogger().warn("The render class-loader API is incompatible with this IDE build", e)
        null
    }

    private fun buildWrapper(classesDirectory: File): RenderModuleWrapper = { module ->
        object : RenderModelModule by module {
            override fun getClassLoaderProvider(privateClassLoader: Boolean) =
                StudioModuleClassLoaderManager.get().getPrivate(
                    module.javaClass.classLoader,
                    injectingContext(module, classesDirectory),
                    ClassTransform.identity,
                    ClassTransform.identity,
                )
        }
    }

    private fun injectingContext(module: RenderModelModule, classesDirectory: File): StudioModuleRenderContext =
        object : StudioModuleRenderContext(module.buildTargetReference) {
            override fun createInjectableClassLoaderLoader(): ProjectSystemClassLoader =
                ProjectSystemClassLoader { fqcn -> classFileFor(fqcn, classesDirectory) }
        }

    private fun classFileFor(fqcn: String, classesDirectory: File): ClassContent? {
        val file = File(classesDirectory, fqcn.replace('.', '/') + ".class")
        return if (file.isFile) ClassContent.loadFromFile(file) else null
    }
}
```

**Three lines above are the ones step 1 may correct**, and nothing else: how the manager instance is obtained
(`StudioModuleClassLoaderManager.get()` versus a static call), the two `ClassTransform` arguments (pass through
whatever the provider already receives if the real signature hands them over rather than letting you choose), and
the `buildTargetReference` accessor on `RenderModelModule`. Adjust those to what `javap` printed; do not redesign
around them.

Two behaviours to keep exactly as written. **Delegation by `by module`**: every member other than
`getClassLoaderProvider` forwards unchanged, which is what keeps this a wrapper rather than a second render model.
**Null from the lambda**: a fully qualified name that is not in this directory must return null so the real loader
answers — the injected directory holds only the `screenshotTest` classes, and every `main` class the composable
touches still comes from the ordinary classpath. Kotlin file facades (`UtilSnapshotsKt`) and
`ComposableSingletons$…` classes are ordinary entries there; no special casing.

If the render then fails because the lambda is never consulted — the first of the spike's three unproven
assumptions — try `ProjectSystemClassLoader.injectClassFile(fqcn, content)` instead, which the spike's table lists
as the second, more direct route. If neither is consulted, **that is the phase's finding: report it and stop.**

- [ ] **Step 3: Thread the wrapper through the resolver**

`RenderModelResolver.resolve` (line 78) gains a fourth parameter and passes it down:

```kotlin
    fun resolve(
        entry: PreviewEntry,
        project: Project,
        override: ViewOverride? = null,
        moduleWrapper: RenderModuleWrapper? = null,
    ): RenderModelResult =
```

`resolveUnderReadAction` takes it too, and line 116 becomes:

```kotlin
        val renderModule = moduleWrapper?.invoke(AndroidFacetRenderModelModule(buildTarget))
            ?: AndroidFacetRenderModelModule(buildTarget)
```

Add one KDoc paragraph on `resolve` naming what the wrapper is for and that a null wrapper reproduces today's path
byte for byte.

- [ ] **Step 4: Thread it through `LiveRenderer`**

`LiveRenderer.render` (line 77) gains the same optional parameter and forwards it to `resolver.resolve`. Nothing
else in that class changes: the wrapper is applied before the render begins, and `renderResolved` never sees it.

- [ ] **Step 5: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. Existing callers pass no wrapper and keep compiling unchanged.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render/
git commit -m "$(cat <<'EOF'
[PG22-3] - Put the screenshotTest classes on the render classpath

Co-Authored-By: <the model that wrote this> <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: The button and the two numbers

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/CompareLiveRenderAction.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` (register the action in the
  toolbar group at :245-257, add the orchestration next to `runVerify`)
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties`

**Interfaces:**
- Consumes: `ImageDiff.compare`, `ScreenshotTestClasses.directoryFor` / `stateOf`,
  `ScreenshotTestClassLoader.wrapperFor`, `LiveRenderer.render(entry, override, moduleWrapper)`.
- Existing pieces this reuses rather than re-deriving: `ModuleDirectoryResolver.resolve(project, file)` for the
  module directory, `ReferenceRoots.of(moduleDirectory)` for the build variant,
  `ReferenceImageLocator.locate(entry, roots)` returning `ReferenceImage(sourceSet, variant, file)` for the golden,
  `SnapshotVerifyStore.getInstance(project).resultFor(moduleName, methodName, variant)` for
  `SnapshotResult.renderedPath`, `ModuleFreshness.newestModuleSourceMtime(module)` for the source clock,
  `ReferenceStripView.LabelledImage(variant, image)` for the strip, and `renderPanel.showVerified(entry, images,
  message)` to publish.

- [ ] **Step 1: Bundle keys**

Append after the `verify.*` block:

```properties
action.compareLiveRender.text=Compare live render
compare.notCompiled=Not compiled — run {0} first
compare.stale=The compiled screenshot tests are older than the source — run {0} first
compare.noGolden=No golden committed for this function's phone variant
compare.result=live vs golden {0} · live vs Gradle {1}
compare.resultGoldenOnly=live vs golden {0}
compare.percent={0}% different
compare.sizeMismatch=sizes differ: {0} vs {1}
compare.renderFailed=Could not render live — see the log
```

- [ ] **Step 2: The action**

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Renders the selected snapshot's composable inside the IDE and reports how far it is from the committed golden
 * (spec D4: the measurement runs when a human asks, never on its own — a false alarm the user triggered is a data
 * point, one that arrives unbidden accumulates behind them).
 *
 * Hidden rather than disabled when the selection is not a snapshot row, matching this panel's convention and
 * [VerifySnapshotsAction] beside it. `AllIcons.Actions.Diff` because this is the one control in the toolbar that
 * actually compares two images.
 */
class CompareLiveRenderAction(
    private val onCompare: () -> Unit,
    private val isAvailable: () -> Boolean,
) : AnAction(
    PreviewGalleryBundle.message("action.compareLiveRender.text"),
    PreviewGalleryBundle.message("action.compareLiveRender.text"),
    AllIcons.Actions.Diff,
), DumbAware {

    override fun actionPerformed(event: AnActionEvent) = onCompare()

    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = isAvailable()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
```

Register it in the toolbar group immediately after `VerifySnapshotsAction`:

```kotlin
            CompareLiveRenderAction({ compareLiveRender() }, { selectedSnapshotEntry() != null }),
```

- [ ] **Step 3: The orchestration in `PreviewGalleryPanel`**

Add below `runVerify`'s helpers. The EDT half gathers what only the EDT can read (the selection, the project model,
the VFS — exactly what `verifyTarget` gathers and for the same reason), then all the work happens on the app
executor and publishes back on the EDT:

```kotlin
    /**
     * Renders the selected snapshot's composable through the IDE's own pipeline and publishes how far it is from
     * the committed golden, and from the image Gradle's last `validate` run drew (spec D2 — the second number is
     * what tells a bad first number apart from a stale golden).
     *
     * The `phone` variant on both sides (spec D3). The project's `@SnapshotPreviews` multipreview draws `phone`
     * and a 320dp `small`, each with its own golden, and a `phone` render measured against a `small` golden would
     * report a difference that means nothing. When the render turns out to be the other variant, the images differ
     * in size and [ImageDiff] says so rather than inventing a percentage — that is the detector, not an oversight.
     */
    private fun compareLiveRender() {
        val snapshot = selectedSnapshotEntry() ?: return
        val moduleDirectory = ModuleDirectoryResolver.resolve(project, snapshot.file) ?: return
        val roots = ReferenceRoots.of(moduleDirectory)
        val variant = roots.firstNotNullOfOrNull { it.buildVariant } ?: return
        val module = ModuleUtilCore.findModuleForFile(snapshot.file, project) ?: return
        val golden = ReferenceImageLocator.locate(snapshot, roots).firstOrNull { it.variant == PHONE_VARIANT }
        val modality = ModalityState.defaultModalityState()
        AppExecutorUtil.getAppExecutorService().execute {
            val message = compareOffEdt(snapshot, module, File(moduleDirectory.path), variant, golden)
            ApplicationManager.getApplication().invokeLater({
                if (disposalCheck.isDisposed) return@invokeLater
                if (selectedSnapshotEntry()?.id != snapshot.id) return@invokeLater
                renderPanel.showVerified(snapshot, message.images, message.text)
            }, modality)
        }
    }
```

`compareOffEdt` returns a tiny holder (`private class ComparisonPublish(val images: List<ReferenceStripView.LabelledImage>, val text: String)`) and runs the steps in order:

1. `val state = ScreenshotTestClasses.stateOf(ScreenshotTestClasses.directoryFor(moduleDirectory, variant), ModuleFreshness.newestModuleSourceMtime(module))`
   — `Missing` → text `compare.notCompiled` with `SnapshotVerifyRunner.validateTask(variant)`, no images, return.
   `Stale` → `compare.stale` with the same task name, no images, return.
2. `golden == null` → text `compare.noGolden`, no images, return.
3. `val wrapper = ScreenshotTestClassLoader.wrapperFor(state.directory)` — null → `compare.renderFailed`, return.
4. `val outcome = LiveRenderer(project).render(snapshot, override = null, moduleWrapper = wrapper)` — anything that
   is not `RenderOutcome.Success` → `compare.renderFailed`, return. A fresh `LiveRenderer` is correct here: it
   holds no state, and the pipeline's own instance is busy with the ordinary preview path.
5. Decode the golden (`ImageIO.read(File(golden.file.path))`) and, when
   `SnapshotVerifyStore.getInstance(project).resultFor(snapshot.moduleName, snapshot.indexed.functionName, PHONE_VARIANT)?.renderedPath`
   is non-null, decode that too. A decode returning null drops that comparison, exactly as the reference path
   already treats an unreadable PNG.
6. Measure with `ImageDiff.compare(live, golden)` and, when present, `ImageDiff.compare(live, gradleRendered)`.
   Format each result with a helper: `Measured` → `compare.percent` with `String.format("%.3f", percent)`,
   `SizeMismatch` → `compare.sizeMismatch` with both sizes. Join with `compare.result` when there are two,
   `compare.resultGoldenOnly` when there is one.
7. Images, in this order: golden, Gradle's render when it decoded, then the live render — labelled `golden`,
   `Gradle`, `live` (reuse the existing `verify.golden` key for the first; add no keys beyond the ones in step 1 —
   the other two labels are the literals `Gradle` and `live`, which are not prose).

`PHONE_VARIANT` is a `private const val PHONE_VARIANT = "phone"` on the panel's companion, with a KDoc line saying
it is the multipreview's own `@Preview(name = "phone")` and therefore also the `variant` in the results XML and in
the golden's file name — one word, three places.

- [ ] **Step 4: Log which element was rendered**

At debug level, right after step 4 of the orchestration, log the rendered image's dimensions and the resolved
element's identity. The gate needs to know *which* variant the resolver actually produced, and a size mismatch
alone does not say whether the render was `small` or something else entirely:

```kotlin
        thisLogger().debug("Compare: rendered ${image.width}x${image.height} for ${snapshot.indexed.composableFqn}")
```

- [ ] **Step 5: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/ src/main/resources/messages/PreviewGalleryBundle.properties
git commit -m "$(cat <<'EOF'
[PG22-4] - Compare a live render against its golden

Co-Authored-By: <the model that wrote this> <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Test phase

Only the pure halves are testable (spec: *the plan must not invent a mock that pretends otherwise*). The class
loader and the render are Android-Studio-internal, exactly like `SnapshotVerifyRunner`, and the gate is what
verifies them.

**Files:**
- Create: `src/test/kotlin/com/devomer/previewgallery/render/ImageDiffTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/render/ScreenshotTestClassesTest.kt`

- [ ] **Step 1: `ImageDiffTest`**

Plain JUnit, no fixture — `RenderedImageInspectorTest` is the neighbour to match.

```kotlin
package com.devomer.previewgallery.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage

class ImageDiffTest {

    private fun image(width: Int, height: Int, rgb: Int = 0xFF000000.toInt()): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until height) for (x in 0 until width) setRGB(x, y, rgb)
        }

    @Test
    fun `identical images measure zero`() {
        val result = ImageDiff.compare(image(4, 4), image(4, 4))
        assertEquals(ImageDiff.Result.Measured(0, 16), result)
        assertEquals(0.0, (result as ImageDiff.Result.Measured).percent, 0.0)
    }

    @Test
    fun `one changed pixel out of sixteen is 6_25 percent`() {
        val right = image(4, 4).apply { setRGB(2, 2, 0xFFFFFFFF.toInt()) }
        val result = ImageDiff.compare(image(4, 4), right) as ImageDiff.Result.Measured
        assertEquals(1L, result.differingPixels)
        assertEquals(6.25, result.percent, 0.0001)
    }

    @Test
    fun `different sizes report both sizes rather than a percentage`() {
        val result = ImageDiff.compare(image(4, 4), image(8, 4))
        assertTrue(result is ImageDiff.Result.SizeMismatch)
        assertEquals("4x4", (result as ImageDiff.Result.SizeMismatch).left.toString())
        assertEquals("8x4", result.right.toString())
    }
}
```

- [ ] **Step 2: `ScreenshotTestClassesTest`**

Real files on disk via `FileUtil.createTempDirectory`, deleted in `tearDown` — `ModuleFreshnessModuleTest`'s own
reason applies: a path with no real mtime makes every staleness assertion vacuous. Use far-future constants for the
same reason PG21 had to (`SnapshotVerifyStoreTest.RUN_LAUNCHED_AT`): a real filesystem stamp must never be able to
beat the synthetic one.

Cases:
1. `directoryFor(moduleDir, "Debug")` ends with
   `build/intermediates/built_in_kotlinc/debugScreenshotTest/compileDebugScreenshotTestKotlin/classes` — the
   lower-camel source set and the upper-camel task name in one assertion.
2. `directoryFor(moduleDir, "GoogleDebug")` gives `googleDebugScreenshotTest` / `compileGoogleDebugScreenshotTestKotlin`.
3. `stateOf` on a directory with no class file → `Missing`, even when the directory itself exists.
4. A class file newer than the source clock → `Ready`.
5. A class file older than the source clock → `Stale`.
6. A null source clock → `Stale`, not `Ready`.
7. `newestClassMtime` ignores a newer non-`.class` file sitting beside the class files, and finds a class nested
   several packages deep.

Write each with `assertEquals` on the sealed result, not `assertTrue(x is Y)` alone, so a wrong directory in a
`Ready` still fails the test.

- [ ] **Step 3: Run the touched suites**

```bash
./gradlew test --tests "com.devomer.previewgallery.render.ImageDiffTest" --tests "com.devomer.previewgallery.render.ScreenshotTestClassesTest"
```

Expected: PASS. Sandbox check first.

- [ ] **Step 4: Revert-check both new files**

For at least one test in each file, demonstrate revert-sensitivity rather than asserting it: break the production
line the test covers (e.g. make `stateOf` return `Ready` when the source clock is null), run the focused test,
capture the failure, restore, run it green. Paste both outputs into the report. PG21 shipped two tests that passed
with their own fix reverted, and only a revert check found them.

- [ ] **Step 5: Full suite**

Run: `./gradlew test`
Expected: PASS — 556 before this branch, plus the cases added here. Report the number.

- [ ] **Step 6: Commit**

```bash
git add src/test/kotlin/com/devomer/previewgallery/render/
git commit -m "$(cat <<'EOF'
[PG22-5] - Cover the measurement and the classes gate

Co-Authored-By: <the model that wrote this> <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Code review

- [ ] **Step 1: Review**

Run `/code-review` (or `superpowers:requesting-code-review`) over `PG22-1..PG22-5`. Pay attention to: a class
loader that is not released (a leaked `Reference` is how test classes reach the shared cache), an AS-internal call
missing its `LinkageError` guard, `ImageDiff` running on the EDT, a message that reports a percentage when the
comparison was actually a size mismatch, and any test that would pass with its production line reverted.

- [ ] **Step 2: Fix what it reports, then re-run `./gradlew test`**

- [ ] **Step 3: Commit the fixes as `[PG22-6] - <what was fixed>`**

---

## Manual gate — this is the calibration

Against `hepsi-android`, from a `runIde` sandbox, after the review. The human runs this. **The number this
produces is the deliverable of the whole phase.**

1. Run `./gradlew :features:favorites:ui:validateDebugScreenshotTest -Pandroid.experimental.enableScreenshotTest=true`
   once at a terminal so the classes are compiled and current. (Or press **Verify snapshots** in the sandbox, which
   runs the same task.)
2. Select a snapshot row in `features/favorites/ui` and press **Compare live render**.
3. Record, verbatim: the message line, both percentages, and the three images' sizes.
4. Repeat on **at least five** rows across different files — one number from one composable is an anecdote. Include
   at least one row whose golden is a simple component and one that is a full screen.
5. Edit any file in that module (add a blank line, save) and press the button again. Expect the stale message
   naming `validateDebugScreenshotTest`, and **no** number.
6. Delete the classes directory and press again. Expect the not-compiled message.

Then apply the decision table from the spec — it is fixed in advance on purpose:

| Result | What happens next |
|---|---|
| ≤ 0.1% on both comparisons, across the rows | Green. The diff surface gets its own spec. |
| 0.1% – 1% | Amber. A thresholded diff becomes its own decision. |
| > 1%, or a size mismatch that is not explained by the variant | Red. F5's diff half closes. |

If the result is red, **capture the images before writing the conclusion** (the render pane's export action writes
PNGs): the spec's own risk note says a first red number may be dominated by something trivial and fixable — a
device configuration, a density, a background — and that is visible in the pictures, not in the percentage.

## Roadmap

After the gate, update **F5** in `docs/snapshot-testing-roadmap.md` with the numbers and the decision, whichever way
it went, and record what the composition turned out to need beyond the spike's three unknowns. Commit as
`[PG22-7] - Record the render calibration in the roadmap`.
