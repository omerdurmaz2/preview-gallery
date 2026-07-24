package com.devomer.previewgallery.render

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// ── IDE Gradle / external-system integration (design §6). Platform API, not AS-internal render API — this class
// is NOT part of the LiveRenderer/RenderModelResolver render-coupling boundary — but every signature below was
// still verified against the real AS 253 jars with javap (see the task report) and every call site is guarded. ──
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.plugins.gradle.util.GradleModuleData
import org.jetbrains.plugins.gradle.util.GradleUtil

/**
 * Builds one module's classes on demand through the IDE's own Gradle integration, so [LiveRenderer] has something
 * to load (design §6). This is platform/Gradle plumbing, not AS-internal render API, so it lives outside
 * [LiveRenderer] / [RenderModelResolver] — but every external-system call is still guarded against [Exception] and
 * [LinkageError]; a signature change on a newer IDE degrades one build request to `onDone(false)` instead of
 * crashing the plugin.
 *
 * - **Never spawns a Gradle daemon directly** (rule B1): everything goes through [ExternalSystemUtil.runTask] with
 *   [GradleConstants.SYSTEM_ID], the same entry point the IDE's own Gradle actions use.
 * - **Compiles the minimum** (rule B2): `:a:b:module:compileDebugKotlin`, never `assembleDebug`.
 * - **Single-flight** (rule B4): [build] cancels whatever build this service currently has in flight before
 *   starting a new one.
 * - **Dumb-mode gated** (rule B5): a no-op returning `onDone(false)` while [DumbService.isDumb].
 * - **Standard progress UI** (rule B6): runs with [ProgressExecutionMode.IN_BACKGROUND_ASYNC] — the same
 *   cancellable background-progress indicator the platform's own external-system tasks use.
 */
@Service(Service.Level.PROJECT)
class BuildService(private val project: Project) : Disposable {

    /** The task this service is currently waiting on, if any. */
    private val currentTaskId = AtomicReference<ExternalSystemTaskId?>(null)

    /**
     * Bumped on every [build] call. [ExternalSystemUtil.runTask] returns before the task starts, and the real
     * [ExternalSystemTaskId] only arrives later (asynchronously) via the notification listener in [onTaskStarted].
     * If build B is requested before build A's `onStart` has fired, [cancelCurrent] finds nothing to cancel yet;
     * comparing the generation A captured against the current one lets A's own late `onStart` notice it has been
     * superseded and cancel itself, instead of running un-tracked alongside B. Same guard on the way out, in
     * [finish], so A finishing late can't clear B's id.
     */
    private val generation = AtomicLong(0)

    /**
     * Compiles [module]'s `compileDebugKotlin` Gradle task and calls [onDone] with whether it succeeded. Cancels
     * any build this service already has running first (single-flight, rule B4). A no-op that immediately calls
     * `onDone(false)` while [DumbService.isDumb] (rule B5), or when [module] is not part of a linked Gradle
     * project.
     */
    fun build(module: Module, onDone: (Boolean) -> Unit) {
        if (DumbService.isDumb(project)) {
            thisLogger().debug("Skipping build for '${module.name}': the project is indexing")
            onDone(false)
            return
        }

        val target = resolveCompileTarget(module)
        if (target == null) {
            thisLogger().warn("Cannot build module '${module.name}': it is not part of a linked Gradle project")
            onDone(false)
            return
        }

        val myGeneration = generation.incrementAndGet()
        cancelCurrent()

        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = target.projectPath
            taskNames = listOf(target.taskPath)
            externalSystemIdString = GradleConstants.SYSTEM_ID.id
        }

        val notifications = ExternalSystemProgressNotificationManager.getInstance()
        // onStart(ExternalSystemTaskId) is deprecated in favor of onStart(String, ExternalSystemTaskId), but its
        // default body is the one every other onStart overload's default body ultimately delegates to (verified
        // with javap -c on ExternalSystemTaskNotificationListener), so overriding it is the one choice that fires
        // no matter which overload the platform's dispatcher calls directly.
        @Suppress("OVERRIDE_DEPRECATION")
        val listener = object : ExternalSystemTaskNotificationListener {
            override fun onStart(id: ExternalSystemTaskId) = onTaskStarted(id, myGeneration)
        }
        val callback = object : TaskCallback {
            override fun onSuccess() = finish(myGeneration, listener, notifications, true, onDone)
            override fun onFailure() = finish(myGeneration, listener, notifications, false, onDone)
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
            thisLogger().warn("Failed to start a build for module '${module.name}'", e)
            removeListener(notifications, listener)
            onDone(false)
        } catch (e: LinkageError) {
            thisLogger().warn("The Gradle build API is incompatible with this IDE build", e)
            removeListener(notifications, listener)
            onDone(false)
        }
    }

    /** Best-effort: don't leave a build running against a project that is closing. */
    override fun dispose() {
        cancelCurrent()
    }

    /**
     * [ExternalSystemProgressNotificationManager] is an application-level service — it notifies of every
     * external-system task for every open project, not just ours, so [id] is only trusted once it is confirmed to
     * be our own Gradle execute-task, for this project.
     */
    private fun onTaskStarted(id: ExternalSystemTaskId, myGeneration: Long) {
        try {
            if (id.type != ExternalSystemTaskType.EXECUTE_TASK) return
            if (id.projectSystemId != GradleConstants.SYSTEM_ID) return
            if (id.findProject() != project) return

            if (generation.get() == myGeneration) {
                currentTaskId.set(id)
            } else {
                // A newer build() call already ran cancelCurrent() before this task existed to be cancelled;
                // cancel it now instead of letting it run un-tracked (see the `generation` doc above).
                cancelTaskId(id)
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to track the started build task", e)
        } catch (e: LinkageError) {
            thisLogger().warn("The Gradle task-notification API is incompatible with this IDE build", e)
        }
    }

    private fun finish(
        myGeneration: Long,
        listener: ExternalSystemTaskNotificationListener,
        notifications: ExternalSystemProgressNotificationManager,
        success: Boolean,
        onDone: (Boolean) -> Unit,
    ) {
        removeListener(notifications, listener)
        if (generation.get() == myGeneration) {
            currentTaskId.set(null)
        }
        onDone(success)
    }

    /** Cancels whatever build this service currently has in flight (rule B4). A no-op if nothing is running. */
    private fun cancelCurrent() {
        currentTaskId.getAndSet(null)?.let { cancelTaskId(it) }
    }

    private fun cancelTaskId(id: ExternalSystemTaskId) {
        try {
            ExternalSystemProcessingManager.getInstance().findTask(id)?.cancel()
        } catch (e: Exception) {
            thisLogger().warn("Failed to cancel build task $id", e)
        } catch (e: LinkageError) {
            thisLogger().warn("The Gradle task-cancellation API is incompatible with this IDE build", e)
        }
    }

    private fun removeListener(
        notifications: ExternalSystemProgressNotificationManager,
        listener: ExternalSystemTaskNotificationListener,
    ) {
        runCatching { notifications.removeNotificationListener(listener) }
            .onFailure { thisLogger().debug("Failed to remove the build notification listener", it) }
    }

    /**
     * Derives the module's Gradle project path (`:a:b:module`) and the directory to invoke Gradle from, using the
     * same `org.jetbrains.plugins.gradle.util` helpers the IDE's own Gradle integration uses to run a task for a
     * module — never a hand-rolled derivation from [Module.getName]. `null` means [module] is not a module IDEA
     * imported from a linked Gradle project (for example, a module created by hand, or one belonging to a
     * different external system).
     */
    private fun resolveCompileTarget(module: Module): CompileTarget? = try {
        val dataNode = GradleUtil.findGradleModuleData(module)
        if (dataNode == null) {
            null
        } else {
            val data = GradleModuleData(dataNode)
            // GradleModuleData.getTaskPathOfSimpleTaskName(String) exists on the compiled-against jar (verified
            // with javap) but is unresolvable from Kotlin call sites — javap only sees its *JVM* name, and its
            // bytecode carries a kotlin.jvm.JvmName annotation, which means the real Kotlin-visible name differs
            // and isn't recoverable from bytecode alone (that mapping lives only in @kotlin.Metadata). Falling
            // back to gradleIdentityPathOrNull (a plain, unmangled property) and reproducing the exact prefixing
            // that method's own decompiled bytecode performs: root project (":" or empty) never doubles its colon.
            val identityPath = data.gradleIdentityPathOrNull
            if (identityPath == null) {
                null
            } else {
                val taskPath = if (identityPath.isEmpty() || identityPath == ":") {
                    ":$COMPILE_TASK_NAME"
                } else {
                    "$identityPath:$COMPILE_TASK_NAME"
                }
                CompileTarget(projectPath = data.directoryToRunTask, taskPath = taskPath)
            }
        }
    } catch (e: Exception) {
        thisLogger().warn("Failed to resolve the Gradle project for module '${module.name}'", e)
        null
    } catch (e: LinkageError) {
        thisLogger().warn("The Gradle module-data API is incompatible with this IDE build", e)
        null
    }

    /** [projectPath] is the directory to invoke Gradle from; [taskPath] is the fully qualified task, e.g. `:app:compileDebugKotlin`. */
    private class CompileTarget(val projectPath: String, val taskPath: String)

    companion object {
        private const val COMPILE_TASK_NAME = "compileDebugKotlin"

        fun getInstance(project: Project): BuildService = project.service()
    }
}
