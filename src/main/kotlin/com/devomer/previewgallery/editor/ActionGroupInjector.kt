package com.devomer.previewgallery.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * Adds an action to a group exactly once.
 *
 * The injector runs again on every editor switch and on every retry attempt, and Android Studio keeps one
 * toolbar group instance per editor, so the identity check is what keeps a second button from appearing.
 */
object ActionGroupInjector {

    fun addOnce(group: DefaultActionGroup, action: AnAction): Boolean {
        if (group.childActionsOrStubs.any { it === action }) return false
        group.add(action)
        return true
    }
}
