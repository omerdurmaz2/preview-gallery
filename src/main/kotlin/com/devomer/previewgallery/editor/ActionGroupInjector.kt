package com.devomer.previewgallery.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * Adds an action to a group exactly once.
 *
 * The injector runs again on every editor switch and on every retry attempt, and Android Studio keeps one
 * toolbar group instance per editor, so the identity check is what keeps a second button from appearing.
 *
 * [DefaultActionGroup.add] resolves an `ActionManager` via `ActionManager.getInstance()`, which requires a
 * live IntelliJ `Application` and is unavailable in this module's plain (non-platform-fixture) unit tests. The
 * [DefaultActionGroup.addAction] overload that takes an explicit action-id lookup function avoids that call —
 * the lookup is only used when replacing an action that the identity check above has already ruled out — so
 * behavior is identical in tests and inside the real IDE.
 */
object ActionGroupInjector {

    fun addOnce(group: DefaultActionGroup, action: AnAction): Boolean {
        if (group.childActionsOrStubs.any { it === action }) return false
        group.addAction(action, Constraints.LAST) { null }
        return true
    }
}
