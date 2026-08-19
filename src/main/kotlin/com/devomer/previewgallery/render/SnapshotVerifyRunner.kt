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
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemExecuteTaskTask
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
 * [verify]'s `buildSucceeded` flag is a narrower fact than [Outcome]: it is only the build's exit status, carried
 * out so [com.devomer.previewgallery.service.SnapshotVerifyResults.readForRun] can tell an up-to-date success
 * (the answer on disk is this run's own) from an up-to-date failure (it proves nothing). It must never be read
 * as an answer to the question [Outcome] already exists to keep separate — a failed build still says nothing
 * about whether the difference was a snapshot mismatch or a broken compile, and this flag is not that signal.
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

    /**
     * Where to look for what the run wrote, and from when. [startedAtMillis] is captured before the task is
     * launched so [com.devomer.previewgallery.service.SnapshotVerifyResults]' timestamp guard cannot be
     * defeated by a result written moments earlier.
     *
     * [buildRoot] is the directory Gradle was invoked from — [Target.projectPath], not the module's own
     * subproject directory — because that is what the image paths inside the results XML are relative to. It
     * travels with the run rather than being re-derived at read time: by then the only thing in hand is a
     * results directory, and guessing a build root back out of it by string surgery is exactly the kind of
     * inference [SnapshotVerifyResults] exists to avoid.
     */
    data class Started(
        val taskName: String,
        val resultsDirectory: Path,
        val startedAtMillis: Long,
        val buildRoot: Path,
    )

    /** The task this run is currently waiting on, if any — claimed once by [onTaskStarted], never overwritten
     *  by an unrelated Gradle task that happens to start in this project while this run is in flight. */
    private val currentTaskId = AtomicReference<ExternalSystemTaskId?>(null)

    /** Bumped once per [verify] call. [ExternalSystemUtil.runTask] returns before Gradle has actually started
     *  the task, so the real [ExternalSystemTaskId] only shows up later, asynchronously, through the
     *  notification listener. Stamping each call with its own generation lets a late [onTaskStarted] or
     *  [finish] tell whether it still belongs to the run this service is tracking, or to one a newer [verify]
     *  call has already superseded. */
    private val generation = AtomicLong(0)

    /**
     * Verifies [module]'s snapshots for [buildVariant], cancelling whatever run this service already has in
     * flight (single-flight, B4 — and spec D2: one question at a time).
     *
     * Calls [onDone] with [Outcome.NOT_RUN], a null [Started] and `buildSucceeded = false` when the run could not
     * be launched at all: indexing, no linked Gradle project, or the external-system call itself failing. Nothing
     * ran, so nothing succeeded.
     *
     * A run superseded by a later [verify] call before it finishes reports nothing at all — [onDone] simply
     * never fires for it, and only the newer run's callback does. A stale result landing after a fresher one
     * would otherwise be able to overwrite it.
     */
    fun verify(module: Module, buildVariant: String, onDone: (Outcome, Started?, buildSucceeded: Boolean) -> Unit) {
        if (DumbService.isDumb(project)) {
            thisLogger().debug("Skipping verify for '${module.name}': the project is indexing")
            onDone(Outcome.NOT_RUN, null, false)
            return
        }
        val target = resolveTarget(module, buildVariant)
        if (target == null) {
            thisLogger().warn("Cannot verify module '${module.name}': it is not part of a linked Gradle project")
            onDone(Outcome.NOT_RUN, null, false)
            return
        }

        val myGeneration = generation.incrementAndGet()
        cancelCurrent()

        val submittedTasks = listOf(target.taskPath)
        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = target.projectPath
            taskNames = submittedTasks
            externalSystemIdString = GradleConstants.SYSTEM_ID.id
            scriptParameters = GATE_FLAG
        }
        val started = Started(
            taskName = target.taskName,
            resultsDirectory = target.resultsDirectory,
            startedAtMillis = System.currentTimeMillis(),
            buildRoot = Path.of(target.projectPath),
        )

        val notifications = ExternalSystemProgressNotificationManager.getInstance()
        val taskIdForThisRun = AtomicReference<ExternalSystemTaskId?>(null)
        // onStart(ExternalSystemTaskId) is deprecated in favor of onStart(String, ExternalSystemTaskId), but its
        // default body is the one every other onStart overload ultimately delegates to (verified with javap -c
        // on ExternalSystemTaskNotificationListener), so it is the one override guaranteed to fire regardless of
        // which overload the platform's dispatcher calls directly — "fixing" the deprecation warning by
        // overriding a newer overload instead would silently stop this run from ever being tracked.
        @Suppress("OVERRIDE_DEPRECATION")
        val listener = object : ExternalSystemTaskNotificationListener {
            override fun onStart(id: ExternalSystemTaskId) =
                onTaskStarted(id, myGeneration, taskIdForThisRun, submittedTasks)
        }
        val callback = object : TaskCallback {
            override fun onSuccess() =
                finish(myGeneration, listener, notifications, Outcome.RAN, started, buildSucceeded = true, onDone)
            override fun onFailure() =
                finish(myGeneration, listener, notifications, Outcome.RAN, started, buildSucceeded = false, onDone)
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
        } catch (e: ProcessCanceledException) {
            removeListener(notifications, listener)
            throw e
        } catch (e: Exception) {
            thisLogger().warn("Failed to start a verify for module '${module.name}'", e)
            removeListener(notifications, listener)
            onDone(Outcome.NOT_RUN, null, false)
        } catch (e: LinkageError) {
            thisLogger().warn("The Gradle build API is incompatible with this IDE build", e)
            removeListener(notifications, listener)
            onDone(Outcome.NOT_RUN, null, false)
        }
    }

    override fun dispose() {
        cancelCurrent()
    }

    /**
     * [ExternalSystemProgressNotificationManager] is an application-level bus — it notifies of every
     * external-system task for every open project, not just the one this run launched. So [id] is trusted only
     * once it is *confirmed* to be this run's own task: a Gradle execute-task, for this project, whose submitted
     * task names are the ones [verify] put in [ExternalSystemTaskExecutionSettings.taskNames] (see [isOurTask]).
     *
     * Matching on the project alone is not enough, and the window where it goes wrong is wide open rather than
     * theoretical. [GATE_FLAG] invalidates Gradle's configuration cache, so this run's own task can take seconds
     * to minutes to start, and the listener is registered before [ExternalSystemUtil.runTask] is even called.
     * Any Gradle task starting in that window — a [BuildService] compile the user's next preview selection
     * triggers, or an `assemble` they started themselves — would otherwise be claimed as this run's, and the next
     * [verify]'s [cancelCurrent] would cancel *that* task while the real verify ran untracked.
     *
     * [taskIdForThisRun] still lets only the first confirmed notification claim the run, so a second verify task
     * (the user's own `validate…` invocation, with the same task names) cannot displace the one being tracked.
     * An id that cannot be confirmed is neither claimed nor cancelled — a task this service did not start is
     * never this service's to stop.
     */
    private fun onTaskStarted(
        id: ExternalSystemTaskId,
        myGeneration: Long,
        taskIdForThisRun: AtomicReference<ExternalSystemTaskId?>,
        taskNames: List<String>,
    ) {
        try {
            if (id.type != ExternalSystemTaskType.EXECUTE_TASK) return
            if (id.projectSystemId != GradleConstants.SYSTEM_ID) return
            if (id.findProject() != project) return
            if (!isOurTask(id, taskNames)) return
            if (!taskIdForThisRun.compareAndSet(null, id)) return
            if (generation.get() != myGeneration) cancelTaskId(id) else currentTaskId.set(id)
        } catch (e: ProcessCanceledException) {
            throw e
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
        buildSucceeded: Boolean,
        onDone: (Outcome, Started?, Boolean) -> Unit,
    ) {
        removeListener(notifications, listener)
        if (generation.get() != myGeneration) {
            thisLogger().info(
                "Verify of ${started.taskName} finished but a newer run superseded it; reporting nothing",
            )
            return
        }
        currentTaskId.set(null)
        thisLogger().info(
            "Verify of ${started.taskName} finished: outcome=$outcome buildSucceeded=$buildSucceeded " +
                "results=${started.resultsDirectory} launchedAt=${started.startedAtMillis}",
        )
        onDone(outcome, started, buildSucceeded)
    }

    private fun cancelCurrent() {
        currentTaskId.getAndSet(null)?.let { cancelTaskId(it) }
    }

    /**
     * Whether [id] names a task this service submitted, rather than any other Gradle task the shared
     * notification bus happens to be reporting.
     *
     * [ExternalSystemProcessingManager] hands back the task object itself — registered before the task's own
     * `onStart` is broadcast, verified against this IDE's jars — and an execute-task's
     * [ExternalSystemExecuteTaskTask.getTasksToExecute] is a verbatim copy of the
     * [ExternalSystemTaskExecutionSettings.taskNames] it was built from, so comparing the two answers the
     * question exactly. Anything this cannot confirm — a lookup that fails, a task of another shape, an id
     * already released — is not ours, and an id that is not ours is never claimed and never cancelled.
     */
    private fun isOurTask(id: ExternalSystemTaskId, taskNames: List<String>): Boolean = try {
        val task = ExternalSystemProcessingManager.getInstance().findTask(id)
        val ours = task is ExternalSystemExecuteTaskTask && task.tasksToExecute == taskNames
        if (!ours) thisLogger().debug("Ignoring Gradle task $id: not a task this verify submitted")
        ours
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        thisLogger().warn("Failed to confirm whether Gradle task $id belongs to this verify", e)
        false
    } catch (e: LinkageError) {
        thisLogger().warn("The Gradle task-lookup API is incompatible with this IDE build", e)
        false
    }

    private fun cancelTaskId(id: ExternalSystemTaskId) {
        try {
            ExternalSystemProcessingManager.getInstance().findTask(id)?.cancel()
        } catch (e: ProcessCanceledException) {
            throw e
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
        try {
            notifications.removeNotificationListener(listener)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            thisLogger().debug("Failed to remove the verify notification listener", e)
        } catch (e: LinkageError) {
            thisLogger().debug("Failed to remove the verify notification listener", e)
        }
    }

    /**
     * Where to invoke Gradle, which task, and where that task writes. [Target.projectPath] and [Target.taskPath]
     * reproduce [BuildService]'s own construction, including that a root project never doubles its colon — but
     * [Target.resultsDirectory] deliberately does not reuse [GradleModuleData.getDirectoryToRunTask] for that:
     * that path is the *build root*, the directory an absolute task path resolves against, not where a
     * subproject writes its own output. [GradleModuleData.getGradleProjectDir] is that module's own subproject
     * directory — the same property [ModuleFreshness.gradleBuildOutputDir] already uses for the same reason.
     *
     * The distinction cuts the other way for the image paths inside those results: they are written relative to
     * the build root, so [Started.buildRoot] carries [Target.projectPath] on, not [Target.resultsDirectory]'s
     * subproject directory.
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
                    resultsDirectory = Path.of(data.gradleProjectDir, "build", "test-results", taskName),
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
