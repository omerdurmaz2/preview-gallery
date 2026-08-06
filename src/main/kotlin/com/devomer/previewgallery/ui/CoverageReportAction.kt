package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.service.CoverageReport
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Writes the project's snapshot coverage to a markdown file the user picks.
 *
 * [rows] is the panel's **unfiltered** list, never what the tree is showing (spec D5): a report titled
 * "snapshot coverage" that quietly described whatever the search box had narrowed it to is a number that
 * lands in a ticket with nobody remembering why it was wrong. Reading the panel's own list rather than
 * asking the index again also keeps this off a read action it would have to take on the EDT.
 */
class CoverageReportAction(
    private val project: Project,
    private val rows: () -> List<PreviewRow>,
) : DumbAwareAction(
    PreviewGalleryBundle.message("action.coverageReport.text"),
    PreviewGalleryBundle.message("action.coverageReport.text"),
    AllIcons.ToolbarDecorator.Export,
) {

    override fun actionPerformed(event: AnActionEvent) {
        val descriptor = FileSaverDescriptor(PreviewGalleryBundle.message("action.coverageReport.text"), "", "md")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as VirtualFile?, DEFAULT_NAME) ?: return
        try {
            wrapper.file.writeText(CoverageReport.markdown(rows()))
        } catch (e: IOException) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Compose Preview Gallery")
                .createNotification(PreviewGalleryBundle.message("report.saveFailed"), NotificationType.WARNING)
                .notify(project)
        }
    }

    private companion object {
        private const val DEFAULT_NAME = "snapshot-coverage.md"
    }
}
