package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.service.CoverageReport
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
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
 *
 * Deliberately **not** `DumbAware`, unlike the filters beside it: during indexing the panel holds no rows, and
 * a file on disk saying the project has no preview is a worse answer than a disabled button.
 */
class CoverageReportAction(
    private val project: Project,
    private val rows: () -> List<PreviewRow>,
) : AnAction(
    PreviewGalleryBundle.message("action.coverageReport.text"),
    PreviewGalleryBundle.message("action.coverageReport.text"),
    AllIcons.ToolbarDecorator.Export,
) {

    override fun actionPerformed(event: AnActionEvent) {
        val descriptor = FileSaverDescriptor(PreviewGalleryBundle.message("action.coverageReport.text"), "", "md")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as VirtualFile?, DEFAULT_NAME) ?: return
        // Read the rows on the EDT, where the panel owns them, and leave the grouping and the write off it: both
        // scale with the project, and the reference one has 1371 modules.
        val snapshot = rows()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                wrapper.file.writeText(CoverageReport.markdown(snapshot))
            } catch (e: IOException) {
                thisLogger().warn("Failed to write the coverage report to ${wrapper.file}", e)
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Compose Preview Gallery")
                    .createNotification(PreviewGalleryBundle.message("report.saveFailed"), NotificationType.WARNING)
                    .notify(project)
            }
        }
    }

    private companion object {
        private const val DEFAULT_NAME = "snapshot-coverage.md"
    }
}
