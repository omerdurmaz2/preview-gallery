package com.devomer.previewgallery.editor

import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.util.ui.UIUtil
import java.awt.Component

/**
 * Finds the action toolbars of a Swing subtree by their place string.
 *
 * `ActionToolbarImpl` is itself a component, so a plain UI traversal reaches every toolbar the editor built. Must
 * be called on the EDT, since it walks live Swing components. A null [root] yields an empty list rather than
 * throwing, so callers can pass a possibly-absent component without a separate null check.
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
