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
     * Calls [onDone] with [Outcome.NOT_RUN] and a null [Started] when the run could not be launched at all:
     * indexing, no linked Gradle project, or the external-system call itself failing.
     *
     * A run superseded by a later [verify] call before it finishes reports nothing at all — [onDone] simply
     * never fires for it, and only the newer run's callback does. A stale result landing after a fresher one
     * would otherwise be able to overwrite it.
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
        val taskIdForThisRun = AtomicReference<ExternalSystemTaskId?>(null)
        // onStart(ExternalSystemTaskId) is deprecated in favor of onStart(String, ExternalSystemTaskId), but its
        // default body is the one every other onStart overload ultimately delegates to, so it is the one
        // override guaranteed to fire regardless of which overload the platform's dispatcher calls directly —
        // "fixing" the deprecation warning by overriding a newer overload instead would silently stop this run
        // from ever being tracked.
        @Suppress("OVERRIDE_DEPRECATION")
        val listener = object : ExternalSystemTaskNotificationListener {
            override fun onStart(id: ExternalSystemTaskId) = onTaskStarted(id, myGeneration, taskIdForThisRun)
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
        } catch (e: ProcessCanceledException) {
            throw e
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

    /**
     * [ExternalSystemProgressNotificationManager] is an application-level bus — it notifies of every
     * external-system task for every open project, not just the one this run launched. So [id] is trusted only
     * once confirmed to be our own Gradle execute-task for this project, and even then [taskIdForThisRun] lets
     * only the first such notification claim it: a second, unrelated Gradle task starting in this project while
     * this run is still in flight — a [BuildService] compile, or the user's own Gradle invocation — must not be
     * able to overwrite the task this run is actually tracking.
     */
    private fun onTaskStarted(
        id: ExternalSystemTaskId,
        myGeneration: Long,
        taskIdForThisRun: AtomicReference<ExternalSystemTaskId?>,
    ) {
        try {
            if (id.type != ExternalSystemTaskType.EXECUTE_TASK) return
            if (id.projectSystemId != GradleConstants.SYSTEM_ID) return
            if (id.findProject() != project) return
            if (generation.get() != myGeneration) {
                cancelTaskId(id)
                return
            }
            if (taskIdForThisRun.compareAndSet(null, id)) currentTaskId.set(id)
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
