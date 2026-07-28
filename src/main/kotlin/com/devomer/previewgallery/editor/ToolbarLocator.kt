package com.devomer.previewgallery.editor

import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.util.ui.UIUtil
import java.awt.Component

/**
 * Finds the action toolbars of a Swing subtree by their place string.
 *
 * `ActionToolbarImpl` is itself a component, so a plain UI traversal reaches every toolbar the editor built.
 */
object ToolbarLocator {

    fun findByPlace(root: Component?, place: String): List<ActionToolbar> {
        if (root == null) return emptyList()
        return UIUtil.uiTraverser(root)
            .filter(ActionToolbar::class.java)
            .filter { it.place == place }
            .toList()
    }
}
