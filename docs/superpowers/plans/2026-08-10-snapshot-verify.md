# Snapshot Verify Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the project's own `validate<Variant>ScreenshotTest` task from the gallery and show, per snapshot, whether it still matches its committed golden.

**Architecture:** A runner mirrors `BuildService` (IDE external-system, never a second daemon, generation-guarded, cancellable) and invokes the derived task with the gate flag. A pure reader parses the JUnit XML the task writes, which already carries the function name, variant, golden path and rendered path. A per-module store keeps the last run and marks it stale on edit. The tree badges failing rows; the render pane shows golden / rendered / diff through the existing `ReferenceStripView`.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install at `platformLocalPath`) · IDE external-system Gradle integration · `javax.xml.parsers` · JUnit 4 · `BasePlatformTestCase`

**Spec:** [2026-08-10-snapshot-verify-design.md](../specs/2026-08-10-snapshot-verify-design.md)

## Global Constraints

- **Never use the Kotlin `!!` operator.** Use `?:`, `?.let`, `requireNotNull` or an explicit null check.
- **All source, comments, docs and test names in English.**
- **Do not add explanatory line comments inside function bodies**, except for a decision a reader would otherwise undo. KDoc on new public declarations is expected — this codebase's KDoc documents *why*, not *what*.
- **Tests come last.** Tasks 1–5 are implementation only. Every test is written in the **Test & Review** phase at the end, then the code review runs. Do not write a test earlier, and do not skip the phase.
- **`SnapshotVerifyResults` and its data types must carry no `com.intellij` import** (spec D10) — they are the natural thing to serve over MCP next, and `mcp/` may not import platform classes.
- **Never spawn a Gradle daemon directly** (plugin spec goal G5 / `BuildService` rule B1): everything goes through `ExternalSystemUtil.runTask` with `GradleConstants.SYSTEM_ID`.
- **The plugin never runs `update`.** Only `validate`. Regenerating a golden stays the human's act.
- **`validate` fails the Gradle build when a snapshot differs.** A failed Gradle run is therefore the *normal* failing case, not an error — never map it straight to "could not run" (spec D8).
- **Every external-system and AS-facing call is guarded** against `Exception` and `LinkageError`, degrading to "no result" rather than throwing — the posture every call in `render/` already takes.
- `BuildService`, `ReferenceRoots`, `ReferenceImageLocator`, `ReferenceStripView`, `ReferenceStripLoader`, `RenderPipeline`, `ModuleDirectoryResolver` are **not** modified.
- Commit message pattern: `[PG20-N] - Task name` (`PG20-0` is the design spec, tasks are `PG20-1` … `PG20-5`, and the final phase is `PG20-6`).
- Commit trailer on every commit: `Co-Authored-By: Claude MODEL <noreply@anthropic.com>`, where `MODEL` is replaced by the model named in **your own** system prompt, with no brackets.
- **Verified against this codebase and SDK** (do not substitute unverified values):
  - `ExternalSystemTaskExecutionSettings` has `externalProjectPath`, `taskNames`, `externalSystemIdString` and **`scriptParameters`** (javap-confirmed) — the gate flag goes in `scriptParameters`.
  - `GradleUtil.findGradleModuleData(module)` → `GradleModuleData(dataNode)` → `gradleIdentityPathOrNull`, `directoryToRunTask`. `BuildService.derivedCompileTarget` shows the exact task-path construction, including that a root project (`""` or `":"`) must not double its colon.
  - `ReferenceRoots.Root(sourceSetName: String, buildVariant: String?, directory: VirtualFile)`; `ReferenceRoots.of(moduleDirectory): List<Root>`; `ReferenceRoots.updateTask(buildVariant) = "update${it}ScreenshotTest"`.
  - `ReferenceStripView.LabelledImage(variant: String, image: BufferedImage)`.
  - `PreviewTreeCellRenderer` renders badges as appended text fragments via `append(text, SimpleTextAttributes)`.
  - The JUnit XML lives at `<module>/build/test-results/<taskName>/TEST-<facade>.xml`, and each `<testcase>` carries `PreviewScreenshot.previewName`, `PreviewScreenshot.methodName`, `PreviewScreenshot.refImagePath`, `PreviewScreenshot.newImagePath`.
- **Build/test command:** `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`. During tasks 1–5 use `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
- **Never run `./gradlew` while a `runIde` sandbox is live.** Check with exactly `pgrep -f "idea.plugin.in.sandbox.mode=true"` and `pgrep -f "gradlew.*runIde"` before every Gradle invocation; if either prints a pid, stop and report. Do **not** run `./gradlew runIde` — the human runs that gate.
- Baseline before Task 1: **496 tests / 68 classes**, 0 failures.

---

## File Structure

**Create**

| File | Responsibility |
|---|---|
| `src/main/kotlin/com/devomer/previewgallery/service/SnapshotVerifyResults.kt` | Parse the JUnit XML into pure data; no platform imports |
| `src/main/kotlin/com/devomer/previewgallery/render/SnapshotVerifyRunner.kt` | Run the derived validate task through the IDE's Gradle integration |
| `src/main/kotlin/com/devomer/previewgallery/service/SnapshotVerifyStore.kt` | The last run per module, and its staleness |
| `src/main/kotlin/com/devomer/previewgallery/ui/VerifySnapshotsAction.kt` | The toolbar action |
| `src/test/kotlin/com/devomer/previewgallery/service/SnapshotVerifyResultsTest.kt` | The reader, against real XML |
| `src/test/kotlin/com/devomer/previewgallery/service/SnapshotVerifyStoreTest.kt` | Staleness and replacement |

**Modify**

| File | Change |
|---|---|
| `ui/PreviewGalleryPanel.kt` | Debounced auto-verify on snapshot selection; publish results |
| `ui/PreviewTreeCellRenderer.kt` | The failing / stale badge on a snapshot row |
| `ui/PreviewRenderPanel.kt` | Show golden / rendered / diff for the selected row |
| `src/main/resources/messages/PreviewGalleryBundle.properties` | Action label and status strings |

---

### Task 1 (`PG20-1`): Read the run's results

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/service/SnapshotVerifyResults.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks. JDK only.
- Produces: `SnapshotVerifyResults.Status` (`PASSED`, `FAILED`); `SnapshotVerifyResults.SnapshotResult(methodName: String, variant: String, status: Status, goldenPath: String?, renderedPath: String?, diffPath: String?)`; `SnapshotVerifyResults.read(resultsDirectory: Path, startedAtMillis: Long): List<SnapshotResult>`.

- [ ] **Step 1: Create the reader**

Create `src/main/kotlin/com/devomer/previewgallery/service/SnapshotVerifyResults.kt`:

```kotlin
package com.devomer.previewgallery.service

import org.w3c.dom.Element
import java.io.File
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads what `validate<Variant>ScreenshotTest` wrote about one module's snapshots.
 *
 * The task's JUnit XML already carries everything this feature needs — the function name, the variant, and the
 * paths of both images — so nothing here parses a file name or recomputes a configuration hash. That is what
 * keeps `ReferenceImageLocator`'s "a prefix is not an identity" limitation out of this path entirely (spec D2's
 * reasoning, one level down).
 *
 * Deliberately free of `com.intellij` imports (spec D10): these results are the natural thing to serve over MCP
 * next, and nothing under `mcp/` may import platform classes.
 */
object SnapshotVerifyResults {

    enum class Status { PASSED, FAILED }

    /** [diffPath] is null for a snapshot that passed — there is no difference image to point at. */
    data class SnapshotResult(
        val methodName: String,
        val variant: String,
        val status: Status,
        val goldenPath: String?,
        val renderedPath: String?,
        val diffPath: String?,
    )

    private const val PREVIEW_NAME = "PreviewScreenshot.previewName"
    private const val METHOD_NAME = "PreviewScreenshot.methodName"
    private const val REF_IMAGE = "PreviewScreenshot.refImagePath"
    private const val NEW_IMAGE = "PreviewScreenshot.newImagePath"
    private const val DIFF_IMAGE = "PreviewScreenshot.diffImagePath"

    /**
     * Every snapshot result in [resultsDirectory], ignoring files last modified before [startedAtMillis].
     *
     * The timestamp guard is not defensive tidiness. The same directory can hold results from an `update` the
     * human ran by hand at a terminal, and reading those would present someone else's older run as this verify's
     * answer — stale data shown as fresh, which is the failure this project keeps designing against (spec D7).
     *
     * Returns an empty list when the directory is absent or holds nothing new enough. The caller distinguishes
     * "nothing to read" from "ran and found nothing" — this object cannot, and must not guess.
     */
    fun read(resultsDirectory: Path, startedAtMillis: Long): List<SnapshotResult> {
        val files = resultsDirectory.toFile()
            .listFiles { file -> file.isFile && file.name.startsWith("TEST-") && file.name.endsWith(".xml") }
            ?: return emptyList()
        return files
            .filter { it.lastModified() >= startedAtMillis }
            .sortedBy { it.name }
            .flatMap { readFile(it) }
    }

    /** A file that will not parse is skipped rather than failing the whole read: one malformed result must not
     *  hide the other nine facade classes' answers. */
    private fun readFile(file: File): List<SnapshotResult> =
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().parse(file)
            val cases = document.getElementsByTagName("testcase")
            (0 until cases.length).mapNotNull { index -> (cases.item(index) as? Element)?.let(::readCase) }
        } catch (e: Exception) {
            emptyList()
        }

    private fun readCase(case: Element): SnapshotResult? {
        val properties = propertiesOf(case)
        val methodName = properties[METHOD_NAME] ?: return null
        val variant = properties[PREVIEW_NAME] ?: return null
        // A <failure> child is what marks a mismatch; its absence is a pass. Checked by presence rather than by
        // the testsuite's failures= count, because that count is per facade class and this is per snapshot.
        val failed = case.getElementsByTagName("failure").length > 0
        return SnapshotResult(
            methodName = methodName,
            variant = variant,
            status = if (failed) Status.FAILED else Status.PASSED,
            goldenPath = properties[REF_IMAGE],
            renderedPath = properties[NEW_IMAGE],
            diffPath = properties[DIFF_IMAGE],
        )
    }

    private fun propertiesOf(case: Element): Map<String, String> {
        val nodes = case.getElementsByTagName("property")
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it) as? Element }
            .mapNotNull { element ->
                val name = element.getAttribute("name").ifEmpty { return@mapNotNull null }
                val value = element.getAttribute("value")
                name to value
            }
            .toMap()
    }
}
```

**The one assumption in this file.** `DIFF_IMAGE`'s property name and the `<failure>` element are taken from the
JUnit convention and the passing run's shape — a *failing* `validate` run was not available while this was
written. The manual gate's first step corrects both. Write it as above; do not invent a different shape to hedge.

- [ ] **Step 2: Compile**

Sandbox check first (both `pgrep` patterns), then:

Run: `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

Write the message to a file under the session scratchpad and use `git commit -F` — a heredoc broke on an
apostrophe in an earlier phase.

```bash
git add src/main/kotlin/com/devomer/previewgallery/service/SnapshotVerifyResults.kt
git commit -F <scratchpad>/pg20-1-msg
```

Message body:

```
[PG20-1] - Read the verify run's results

The task's own JUnit XML carries the function name, the variant and both image
paths, so nothing here parses a file name. Results older than the run that asked
for them are ignored: the same directory can hold an update the human ran by
hand, and showing that as this run's answer is the failure mode this project
keeps designing against.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 2 (`PG20-2`): Run the task

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/render/SnapshotVerifyRunner.kt`

**Interfaces:**
- Consumes: `ReferenceRoots.of(moduleDirectory)` and `ReferenceRoots.Root.buildVariant`.
- Produces: `SnapshotVerifyRunner.Outcome` (`RAN`, `BUILD_FAILED`, `NOT_RUN`); `SnapshotVerifyRunner.Started(taskName: String, resultsDirectory: Path, startedAtMillis: Long)`; `SnapshotVerifyRunner.getInstance(project): SnapshotVerifyRunner`; `SnapshotVerifyRunner.verify(module: Module, buildVariant: String, onDone: (Outcome, Started?) -> Unit)`; `SnapshotVerifyRunner.validateTask(buildVariant: String): String`.

- [ ] **Step 1: Read the class you are mirroring**

Read `src/main/kotlin/com/devomer/previewgallery/render/BuildService.kt` in full before writing. This task
reproduces its shape deliberately: `AtomicReference` for the in-flight task id, `AtomicLong` generation, the
`ExternalSystemTaskNotificationListener` whose deprecated `onStart(ExternalSystemTaskId)` override is the one
that always fires, `TaskCallback` for success/failure, `ExternalSystemProcessingManager` for cancellation, and
`derivedCompileTarget`'s exact task-path construction. Its class KDoc lists the rules (B1, B4, B5, B6) that bind
this one too.

- [ ] **Step 2: Create the runner**

Create `src/main/kotlin/com/devomer/previewgallery/render/SnapshotVerifyRunner.kt`:

```kotlin
package com.devomer.previewgallery.render

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.plugins.gradle.util.GradleModuleData
import org.jetbrains.plugins.gradle.util.GradleUtil
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs one module's `validate<Variant>ScreenshotTest` through the IDE's own Gradle integration.
 *
 * Deliberately a near-copy of [BuildService]'s shape rather than a shared abstraction: the two run different
 * tasks for different reasons and neither wants the other's failure semantics, and one shared runner would have
 * to carry both. Every rule in that class's doc binds here — never spawning a daemon (B1), single-flight (B4),
 * dumb-mode gated (B5), standard cancellable background progress (B6).
 *
 * **A failed Gradle run is the normal failing case.** `validate` fails the build when a snapshot differs, so
 * failure alone says nothing about whether the difference is a snapshot mismatch or a broken compile. That is
 * why [Outcome] is decided together with whether the task wrote any results, not from the exit status alone.
 *
 * The task name is derived rather than read from the IDE's model (spec D5): the project is synced without
 * `-Pandroid.experimental.enableScreenshotTest=true`, so AGP's screenshot plugin is not applied there and the
 * task is absent from the model — the flag passed here is what makes it exist for this invocation.
 */
@Service(Service.Level.PROJECT)
class SnapshotVerifyRunner(private val project: Project) : Disposable {

    /** [RAN] means the task produced results — some may be failures. [BUILD_FAILED] means it ran and produced
     *  none, so the difference is a broken build rather than a changed snapshot. [NOT_RUN] means it never
     *  started. Three states, because a green badge for a run that never happened is worse than no badge
     *  (spec D8). */
    enum class Outcome { RAN, BUILD_FAILED, NOT_RUN }

    /** Where to look for what the run wrote, and from when. [startedAtMillis] is captured before the task is
     *  launched so [com.devomer.previewgallery.service.SnapshotVerifyResults]' timestamp guard cannot be
     *  defeated by a result written moments earlier. */
    data class Started(val taskName: String, val resultsDirectory: Path, val startedAtMillis: Long)

    private val currentTaskId = AtomicReference<ExternalSystemTaskId?>(null)
    private val generation = AtomicLong(0)

    /**
     * Verifies [module]'s snapshots for [buildVariant], cancelling whatever run this service already has in
     * flight (single-flight, B4 — and spec D2: one question at a time).
     *
     * Calls [onDone] with [Outcome.NOT_RUN] and a null [Started] when the run could not be launched at all:
     * indexing, no linked Gradle project, or the external-system call itself failing.
     */
    fun verify(module: Module, buildVariant: String, onDone: (Outcome, Started?) -> Unit) {
        if (DumbService.isDumb(project)) {
            thisLogger().debug("Skipping verify for '${module.name}': the project is indexing")
            onDone(Outcome.NOT_RUN, null)
            return
        }
        val target = resolveTarget(module, buildVariant)
        if (target == null) {
            thisLogger().warn("Cannot verify module '${module.name}': it is not part of a linked Gradle project")
            onDone(Outcome.NOT_RUN, null)
            return
        }

        val myGeneration = generation.incrementAndGet()
        cancelCurrent()

        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = target.projectPath
            taskNames = listOf(target.taskPath)
            externalSystemIdString = GradleConstants.SYSTEM_ID.id
            scriptParameters = GATE_FLAG
        }
        val started = Started(target.taskName, target.resultsDirectory, System.currentTimeMillis())

        val notifications = ExternalSystemProgressNotificationManager.getInstance()
        @Suppress("OVERRIDE_DEPRECATION")
        val listener = object : ExternalSystemTaskNotificationListener {
            override fun onStart(id: ExternalSystemTaskId) = onTaskStarted(id, myGeneration)
        }
        val callback = object : TaskCallback {
            override fun onSuccess() = finish(myGeneration, listener, notifications, Outcome.RAN, started, onDone)
            override fun onFailure() = finish(myGeneration, listener, notifications, Outcome.RAN, started, onDone)
        }

        try {
            notifications.addNotificationListener(listener)
            ExternalSystemUtil.runTask(
                settings,
                DefaultRunExecutor.EXECUTOR_ID,
                project,
                GradleConstants.SYSTEM_ID,
                callback,
                ProgressExecutionMode.IN_BACKGROUND_ASYNC,
            )
        } catch (e: Exception) {
            thisLogger().warn("Failed to start a verify for module '${module.name}'", e)
            removeListener(notifications, listener)
            onDone(Outcome.NOT_RUN, null)
        } catch (e: LinkageError) {
            thisLogger().warn("The Gradle build API is incompatible with this IDE build", e)
            removeListener(notifications, listener)
            onDone(Outcome.NOT_RUN, null)
        }
    }

    override fun dispose() {
        cancelCurrent()
    }

    private fun onTaskStarted(id: ExternalSystemTaskId, myGeneration: Long) {
        try {
            if (id.type != ExternalSystemTaskType.EXECUTE_TASK) return
            if (id.projectSystemId != GradleConstants.SYSTEM_ID) return
            if (id.findProject() != project) return
            if (generation.get() == myGeneration) currentTaskId.set(id) else cancelTaskId(id)
        } catch (e: Exception) {
            thisLogger().warn("Failed to track the started verify task", e)
        } catch (e: LinkageError) {
            thisLogger().warn("The Gradle task-notification API is incompatible with this IDE build", e)
        }
    }

    private fun finish(
        myGeneration: Long,
        listener: ExternalSystemTaskNotificationListener,
        notifications: ExternalSystemProgressNotificationManager,
        outcome: Outcome,
        started: Started,
        onDone: (Outcome, Started?) -> Unit,
    ) {
        removeListener(notifications, listener)
        if (generation.get() != myGeneration) return
        currentTaskId.set(null)
        onDone(outcome, started)
    }

    private fun cancelCurrent() {
        currentTaskId.getAndSet(null)?.let { cancelTaskId(it) }
    }

    private fun cancelTaskId(id: ExternalSystemTaskId) {
        try {
            ExternalSystemProcessingManager.getInstance().findTask(id)?.cancel()
        } catch (e: Exception) {
            thisLogger().warn("Failed to cancel verify task $id", e)
        } catch (e: LinkageError) {
            thisLogger().warn("The Gradle task-cancellation API is incompatible with this IDE build", e)
        }
    }

    private fun removeListener(
        notifications: ExternalSystemProgressNotificationManager,
        listener: ExternalSystemTaskNotificationListener,
    ) {
        runCatching { notifications.removeNotificationListener(listener) }
            .onFailure { thisLogger().debug("Failed to remove the verify notification listener", it) }
    }

    /**
     * Where to invoke Gradle, which task, and where that task writes. Reproduces [BuildService]'s own path
     * construction, including that a root project never doubles its colon.
     */
    private fun resolveTarget(module: Module, buildVariant: String): Target? =
        ReadAction.compute<Target?, RuntimeException> {
            try {
                val dataNode = GradleUtil.findGradleModuleData(module) ?: return@compute null
                val data = GradleModuleData(dataNode)
                val identityPath = data.gradleIdentityPathOrNull ?: return@compute null
                val taskName = validateTask(buildVariant)
                val taskPath = if (identityPath.isEmpty() || identityPath == ":") {
                    ":$taskName"
                } else {
                    "$identityPath:$taskName"
                }
                Target(
                    projectPath = data.directoryToRunTask,
                    taskPath = taskPath,
                    taskName = taskName,
                    resultsDirectory = Path.of(data.directoryToRunTask, "build", "test-results", taskName),
                )
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Failed to resolve the Gradle project for module '${module.name}'", e)
                null
            } catch (e: LinkageError) {
                thisLogger().warn("The Gradle module-data API is incompatible with this IDE build", e)
                null
            }
        }

    private class Target(
        val projectPath: String,
        val taskPath: String,
        val taskName: String,
        val resultsDirectory: Path,
    )

    companion object {
        private const val GATE_FLAG = "-Pandroid.experimental.enableScreenshotTest=true"

        /** The sibling of [com.devomer.previewgallery.service.ReferenceRoots.updateTask], derived the same way
         *  and for the same reason. */
        fun validateTask(buildVariant: String): String = "validate${buildVariant}ScreenshotTest"

        fun getInstance(project: Project): SnapshotVerifyRunner = project.service()
    }
}
```

Note `resolveTarget` builds `resultsDirectory` from `data.directoryToRunTask`, which is the **module's own**
directory to invoke Gradle from — the same base `build/test-results/<task>/` hangs off.

- [ ] **Step 3: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL. No `plugin.xml` change is needed — `@Service(Service.Level.PROJECT)` registers the
service by itself on this platform, exactly as `BuildService` relies on.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render/SnapshotVerifyRunner.kt
git commit -F <scratchpad>/pg20-2-msg
```

Message body:

```
[PG20-2] - Run the validate task from the gallery

Mirrors BuildService rather than sharing an abstraction with it: the two run
different tasks with different failure semantics, and one runner carrying both
would have to explain which it meant every time.

The task name is derived because the IDE's model does not have it - the project
is synced without the experimental flag, and the flag passed here is what makes
the task exist for this invocation.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 3 (`PG20-3`): Keep the last run, and know when it went stale

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/service/SnapshotVerifyStore.kt`

**Interfaces:**
- Consumes: Task 1's `SnapshotVerifyResults.SnapshotResult`; Task 2's `SnapshotVerifyRunner.Outcome`.
- Produces: `SnapshotVerifyStore.Run(moduleName: String, outcome: SnapshotVerifyRunner.Outcome, results: List<SnapshotVerifyResults.SnapshotResult>, ranAtMillis: Long, stale: Boolean)`; `SnapshotVerifyStore.getInstance(project)`; `.put(run: Run)`; `.forModule(moduleName: String): Run?`; `.resultFor(moduleName: String, methodName: String, variant: String): SnapshotVerifyResults.SnapshotResult?`; `.markStale(moduleName: String)`; `.markAllStale()`.

- [ ] **Step 1: Create the store**

Create `src/main/kotlin/com/devomer/previewgallery/service/SnapshotVerifyStore.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.devomer.previewgallery.render.SnapshotVerifyRunner
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * The last verify run per module.
 *
 * Editing the module marks its run **stale** rather than dropping it (spec D4). "It was green" and "it is green"
 * are different facts, and this project has repeatedly chosen to keep that distinction visible instead of
 * silent; deleting also throws away minutes of Gradle over one keystroke.
 */
@Service(Service.Level.PROJECT)
class SnapshotVerifyStore {

    /** [stale] means the module's source changed after [ranAtMillis] — the results still describe a real run,
     *  just not the code on disk now. */
    data class Run(
        val moduleName: String,
        val outcome: SnapshotVerifyRunner.Outcome,
        val results: List<SnapshotVerifyResults.SnapshotResult>,
        val ranAtMillis: Long,
        val stale: Boolean = false,
    )

    private val runs = ConcurrentHashMap<String, Run>()

    fun put(run: Run) {
        runs[run.moduleName] = run
    }

    fun forModule(moduleName: String): Run? = runs[moduleName]

    /** The result for one snapshot function and variant, or null when this module has no run, or the run has no
     *  entry for it — a snapshot added since the run is exactly that case, and it must read as "unknown" rather
     *  than as "passed". */
    fun resultFor(
        moduleName: String,
        methodName: String,
        variant: String,
    ): SnapshotVerifyResults.SnapshotResult? =
        runs[moduleName]?.results?.firstOrNull { it.methodName == methodName && it.variant == variant }

    fun markStale(moduleName: String) {
        runs.computeIfPresent(moduleName) { _, run -> if (run.stale) run else run.copy(stale = true) }
    }

    /** Used when a change cannot be attributed to one module — a broad VFS event, or a project reload. Marking
     *  everything is the safe direction: a stale badge understates confidence, a fresh one overstates it. */
    fun markAllStale() {
        runs.replaceAll { _, run -> if (run.stale) run else run.copy(stale = true) }
    }

    companion object {
        fun getInstance(project: Project): SnapshotVerifyStore = project.service()
    }
}
```

- [ ] **Step 2: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/service/SnapshotVerifyStore.kt
git commit -F <scratchpad>/pg20-3-msg
```

Message body:

```
[PG20-3] - Keep the last verify run per module

An edit marks the run stale rather than dropping it: "it was green" and "it is
green" are different facts, and deleting throws away minutes of Gradle over one
keystroke.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 4 (`PG20-4`): Trigger it

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/VerifySnapshotsAction.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: `VerifySnapshotsAction(onVerify: () -> Unit, isAvailable: () -> Boolean)`.

- [ ] **Step 1: Add the bundle keys**

In `src/main/resources/messages/PreviewGalleryBundle.properties`, after the existing `action.*` keys:

```properties
action.verifySnapshots.text=Verify snapshots
```

- [ ] **Step 2: Create the action**

Create `src/main/kotlin/com/devomer/previewgallery/ui/VerifySnapshotsAction.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Starts a verify for the selected row's module, superseding whatever run is in flight (spec D2).
 *
 * Not `DumbAware` by accident — it is, because the runner itself refuses while indexing and reports why; a
 * disabled button during indexing would say less. Hidden rather than disabled when there is nothing to verify,
 * matching this panel's own convention.
 */
class VerifySnapshotsAction(
    private val onVerify: () -> Unit,
    private val isAvailable: () -> Boolean,
) : AnAction(
    PreviewGalleryBundle.message("action.verifySnapshots.text"),
    PreviewGalleryBundle.message("action.verifySnapshots.text"),
    AllIcons.Actions.Refresh,
), DumbAware {

    override fun actionPerformed(event: AnActionEvent) = onVerify()

    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = isAvailable()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
```

- [ ] **Step 3: Wire the trigger into the panel**

Read `PreviewGalleryPanel.routeSelection`, `showReferenceImages` and the `DefaultActionGroup(...)` toolbar block
first — PG19 left `routeSelection` with three branches and its own debounce alarm, and this task adds to that
structure rather than replacing it.

Add the fields, near `referenceLoader`:

```kotlin
    private val verifyAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
```

Add to the toolbar group, beside the existing actions:

```kotlin
            VerifySnapshotsAction({ startVerify(selectedSnapshotEntry()) }, { verifyTarget() != null }),
```

And add the trigger itself:

```kotlin
    /**
     * The module and build variant the selected row's snapshots would be verified for, or null when there is
     * nothing to verify — no snapshot row selected, no module, or no reference root to name a variant.
     *
     * The variant comes from the reference roots rather than from a default (spec D6): the variant worth
     * checking is the one whose goldens are committed, and a module with none has nothing to compare against.
     */
    private fun verifyTarget(): VerifyTarget? {
        val snapshot = selectedSnapshotEntry() ?: return null
        val moduleDirectory = ModuleDirectoryResolver.resolve(project, snapshot.file) ?: return null
        val module = ModuleUtilCore.findModuleForFile(snapshot.file, project) ?: return null
        val variant = ReferenceRoots.of(moduleDirectory).firstNotNullOfOrNull { it.buildVariant } ?: return null
        return VerifyTarget(module, variant, snapshot.moduleName)
    }

    private class VerifyTarget(val module: Module, val buildVariant: String, val moduleName: String)

    /**
     * Starts a verify for [snapshot]'s module, debounced exactly as the reference lookup is.
     *
     * The debounce is what makes automatic verification survivable (spec D1): arrow-keying down a preview's
     * snapshot children fires one selection per row, and a Gradle run per row is not a cost the user asked for.
     * [RenderPipeline.DEBOUNCE_MS] is shared with the render and reference paths deliberately — all three mean
     * "the user settled on this row".
     */
    private fun startVerify(snapshot: PreviewEntry?) {
        verifyAlarm.cancelAllRequests()
        if (snapshot == null) return
        verifyAlarm.addRequest({ runVerify() }, RenderPipeline.DEBOUNCE_MS)
    }

    private fun runVerify() {
        val target = verifyTarget() ?: return
        val store = SnapshotVerifyStore.getInstance(project)
        SnapshotVerifyRunner.getInstance(project).verify(target.module, target.buildVariant) { outcome, started ->
            val results = if (started == null) {
                emptyList()
            } else {
                SnapshotVerifyResults.read(started.resultsDirectory, started.startedAtMillis)
            }
            // A run that produced no results did not measure anything, whatever Gradle's exit status said: a
            // compile failure and a clean pass are both "the task returned", and only the results tell them
            // apart (spec D8). A task name that does not exist in Gradle lands here too — the spec's error
            // table lists it separately, but telling it from a compile failure would mean parsing Gradle's
            // output, and both answer the user the same way: nothing was measured.
            val resolved = when {
                outcome == SnapshotVerifyRunner.Outcome.NOT_RUN -> SnapshotVerifyRunner.Outcome.NOT_RUN
                results.isEmpty() -> SnapshotVerifyRunner.Outcome.BUILD_FAILED
                else -> SnapshotVerifyRunner.Outcome.RAN
            }
            store.put(
                SnapshotVerifyStore.Run(
                    moduleName = target.moduleName,
                    outcome = resolved,
                    results = results,
                    ranAtMillis = started?.startedAtMillis ?: System.currentTimeMillis(),
                ),
            )
            ApplicationManager.getApplication().invokeLater({
                if (disposalCheck.isDisposed) return@invokeLater
                tree.repaint()
                routeSelection(deferReferenceLookup = true)
            }, ModalityState.defaultModalityState())
        }
    }
```

Call `startVerify` from `routeSelection`'s snapshot branch — the branch that already exists for showing
goldens — so selecting a snapshot row both shows its goldens and schedules the verify. Do **not** call it from
the preview branch (spec D1).

- [ ] **Step 4: Mark results stale on edit**

`PreviewGalleryPanel` already reloads on PSI change. Find that listener and add, alongside whatever it already
does:

```kotlin
        SnapshotVerifyStore.getInstance(project).markAllStale()
```

Read the existing listener before editing: if it can attribute the change to one module, call
`markStale(moduleName)` for that module instead, and say in your report which form the existing code supported.

- [ ] **Step 5: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL. Add imports as the compiler demands them (`ModuleUtilCore`, `Module`,
`ReferenceRoots`, `ModuleDirectoryResolver`, the three new types) — check each is not already present.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/VerifySnapshotsAction.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt src/main/resources/messages/PreviewGalleryBundle.properties
git commit -F <scratchpad>/pg20-4-msg
```

Message body:

```
[PG20-4] - Verify on selection, and on demand

Selecting a snapshot row schedules a verify behind the same debounce the render
and reference paths use: all three mean "the user settled on this row", and a
Gradle run per arrow key is not a cost anyone asked for.

A run that produced no results did not measure anything, whatever Gradle's exit
status said - validate fails the build when a snapshot differs, so the exit
status alone cannot tell a mismatch from a broken compile.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

### Task 5 (`PG20-5`): Show what it found

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRenderer.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces: nothing later tasks build on.

- [ ] **Step 1: Add the bundle keys**

```properties
verify.differs=differs
verify.stale=stale
verify.buildFailed=Verify did not complete - nothing was measured
verify.notRun=Not verified
verify.golden=golden
verify.rendered=rendered
verify.diff=diff
```

- [ ] **Step 2: Badge the failing rows**

`PreviewTreeCellRenderer` renders a snapshot leaf as `append(node.row.indexed.functionName, REGULAR_ATTRIBUTES)`.
Read that block, then append a badge after the name when the store has a failing result for that row:

```kotlin
                val verify = SnapshotVerifyStore.getInstance(project)
                    .forModule(node.row.moduleName)
                val failed = verify?.results.orEmpty()
                    .any { it.methodName == node.row.indexed.functionName && it.status == SnapshotVerifyResults.Status.FAILED }
                if (failed) {
                    val label = if (verify?.stale == true) {
                        "${PreviewGalleryBundle.message("verify.differs")} · ${PreviewGalleryBundle.message("verify.stale")}"
                    } else {
                        PreviewGalleryBundle.message("verify.differs")
                    }
                    append("  $label", SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
```

The renderer needs the project to reach the store; read its constructor and add it if it does not already have
one, updating the single construction site in `PreviewGalleryPanel`.

Matched on `methodName` alone, not on variant: a row is one function, and a function whose `phone` variant
differs is a row worth flagging regardless of what `small` did.

- [ ] **Step 3: Show golden, rendered and diff**

In `PreviewRenderPanel`, extend `showReference` with an optional third source. The panel already lays out N
labelled images at one shared scale, so a failing snapshot is three `LabelledImage`s rather than a new view
(spec D9):

```kotlin
    /**
     * The images for a verified snapshot: its golden, what the run rendered, and the difference between them.
     * A snapshot that passed carries no difference image, so it shows two — an empty third slot would read as a
     * difference of nothing rather than as no difference.
     */
    fun showVerified(entry: PreviewEntry, images: List<ReferenceStripView.LabelledImage>, message: String?) {
        showReference(entry, images, skipped = emptyList(), tasks = emptyList())
        if (message != null) {
            referenceStrip?.toolTipText = message
        }
    }
```

In `PreviewGalleryPanel`, add the publish path. It runs on the same background executor the reference lookup
uses — decoding two or three device-resolution PNGs must not happen on the EDT, for the reason
`ReferenceStripLoader` already documents:

```kotlin
    /**
     * Publishes the verify result for [snapshot] — its golden, what the run rendered, and the difference — or
     * returns false when there is no result to show, leaving the caller on the plain reference path.
     *
     * A named path that no longer exists is reported rather than dropped: a strip silently missing its diff
     * would read as "no difference", which is the one thing it must never say by accident.
     */
    private fun publishVerify(snapshot: PreviewEntry): Boolean {
        val result = SnapshotVerifyStore.getInstance(project).forModule(snapshot.moduleName)
            ?.results
            ?.firstOrNull { it.methodName == snapshot.indexed.functionName }
            ?: return false
        val sources = buildList {
            result.goldenPath?.let { add(PreviewGalleryBundle.message("verify.golden") to it) }
            result.renderedPath?.let { add(PreviewGalleryBundle.message("verify.rendered") to it) }
            result.diffPath?.let { add(PreviewGalleryBundle.message("verify.diff") to it) }
        }
        val modality = ModalityState.defaultModalityState()
        AppExecutorUtil.getAppExecutorService().execute {
            val images = mutableListOf<ReferenceStripView.LabelledImage>()
            val missing = mutableListOf<String>()
            for ((label, path) in sources) {
                val image = runCatching { ImageIO.read(File(path)) }.getOrNull()
                if (image == null) missing += label else images += ReferenceStripView.LabelledImage(label, image)
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposalCheck.isDisposed) return@invokeLater
                if (selectedSnapshotEntry()?.id != snapshot.id) return@invokeLater
                renderPanel.showVerified(snapshot, images, missing.takeIf { it.isNotEmpty() }?.joinToString(", "))
            }, modality)
        }
        return true
    }
```

Call it from `routeSelection`'s snapshot branch before the plain reference lookup: when it returns true the
verify images are on their way, when false the branch continues exactly as it does today.

- [ ] **Step 4: Compile**

Sandbox check, then `./gradlew compileKotlin --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/ src/main/resources/messages/PreviewGalleryBundle.properties
git commit -F <scratchpad>/pg20-5-msg
```

Message body:

```
[PG20-5] - Show what the verify found

Three images at one shared scale through the strip that already does exactly
that, rather than a fourth view. A snapshot that passed shows two: an empty
third slot would read as a difference of nothing rather than as no difference.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

---

## Test & Review phase (`PG20-6`)

Everything above shipped without a test. This phase is where the feature earns them, and it is not optional.

- [ ] **Step 1: The reader**

Create `src/test/kotlin/com/devomer/previewgallery/service/SnapshotVerifyResultsTest.kt`. Plain JUnit — the
reader takes a `Path` and touches no platform class. Write real XML into a `TemporaryFolder`, following the
shape in the spec.

Cover:

1. A passing `<testcase>` with all four properties yields `PASSED`, the method name, the variant and both paths.
2. A `<testcase>` carrying a `<failure>` child yields `FAILED`.
3. A file whose `lastModified` predates `startedAtMillis` is **not** read — the spec D7 guard. Set the file's
   timestamp explicitly with `File.setLastModified`.
4. A malformed XML file is skipped while its sibling still parses.
5. An absent directory yields an empty list rather than throwing.

- [ ] **Step 2: The store**

Create `src/test/kotlin/com/devomer/previewgallery/service/SnapshotVerifyStoreTest.kt`. Plain JUnit against a
directly constructed store.

Cover:

1. `put` then `forModule` round-trips.
2. `markStale` sets the flag and keeps the results.
3. `resultFor` returns null for a method the run did not include — the "added since the run" case, which must
   not read as passed.
4. A second `put` for the same module replaces the first, stale flag included.

- [ ] **Step 3: Task-name derivation**

Add to `SnapshotVerifyStoreTest` or a small companion test: `SnapshotVerifyRunner.validateTask("Debug")` is
`validateDebugScreenshotTest`, and it is the sibling of `ReferenceRoots.updateTask("Debug")`. Assert both in one
test so the pairing is visible.

- [ ] **Step 4: Run the whole suite**

Sandbox check first, then:

Run: `./gradlew clean test --no-build-cache --rerun-tasks --max-workers=1 --no-parallel`

Expected: PASS. Baseline was 496 tests / 68 classes. This phase adds 5 + 4 + 1 = **10 tests and 2 classes**, so
expect **506 tests / 70 classes**.

Iterate until green. **No existing test may be modified.**

- [ ] **Step 5: Commit the tests**

```bash
git add src/test/kotlin/com/devomer/previewgallery/service/
git commit -F <scratchpad>/pg20-6-msg
```

Message body:

```
[PG20-6] - Test snapshot verify

The timestamp guard gets its own test with an explicitly backdated file: it is
what stops an update the human ran by hand from being reported as this run's
answer, and nothing else would catch its removal.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
```

- [ ] **Step 6: Code review**

Run a review over the whole feature (`PG20-1..PG20-6`) with `superpowers:requesting-code-review`, or ask the
human to run `/code-review`. Fix what it reports before the gate. Pay attention to: a path that maps a failed
Gradle run straight to "clean", PNG decoding on the EDT, a verify started without the debounce, and any `!!`.

---

## Manual gate

Against `hepsi-android`, from a `runIde` sandbox, after the review.

**Step 1 comes first and may invalidate the reader.** The failing-XML shape is this feature's one unverified
assumption.

1. Pick a committed golden under `features/favorites/ui/src/screenshotTestDebug/reference/`, **back it up**, and
   overwrite it with a different image so the snapshot must fail.
2. In the sandbox, select that snapshot's row. The verify starts after the debounce.
3. When it finishes, read the XML it wrote:
   `features/favorites/ui/build/test-results/validateDebugScreenshotTest/TEST-*.xml`.
   Confirm the failing `<testcase>` really carries a `<failure>` child, and find the property that names the
   diff image. **If either differs from `SnapshotVerifyResults`' assumption, fix the reader before going on.**
4. The row shows a `differs` badge, and the pane shows golden, rendered and diff.
5. **Restore the backed-up golden.**
6. Select a snapshot row in a module whose snapshots all pass. It verifies and shows no badge.
7. Arrow quickly across several snapshot rows. Only the row you settle on starts a run — the debounce holds.
8. Press **Verify snapshots** while a run is in flight. The first is cancelled and the second starts.
9. Edit a file in the verified module. The badge stays but reads stale.
10. Select a snapshot row in a module with no `screenshotTest`. The action is hidden and nothing runs.

Report how long a settled verify took on this project — the number the spec deliberately did not guess.

## Roadmap

After the gate passes, update **F6** in `docs/snapshot-testing-roadmap.md`: it shipped, ahead of F5's diff half
and without depending on it. Record that the dependency this roadmap asserted turned out to be wrong, and why —
the task already writes the rendered images, the diff images and a machine-readable result. Move F5's diff half
to priority 1 with its scope unchanged. Commit as `[PG20-7] - Record snapshot verify in the roadmap`.
