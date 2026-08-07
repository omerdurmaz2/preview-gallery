package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.service.CoverageReport
import com.devomer.previewgallery.service.GoldenInspector
import com.devomer.previewgallery.service.HealthReport
import com.devomer.previewgallery.service.ModuleDirectoryResolver
import com.devomer.previewgallery.service.ReferenceRoots
import com.devomer.previewgallery.service.ReferenceImageLocator
import com.devomer.previewgallery.service.SnapshotHealth
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Writes the project's snapshot coverage and health to a markdown file the user picks.
 *
 * [rows] is the panel's **unfiltered** list, never what the tree is showing (PG16 spec D5): a report titled
 * "snapshot coverage" that quietly described whatever the search box had narrowed it to is a number that
 * lands in a ticket with nobody remembering why it was wrong.
 *
 * [orphans] is passed alongside because a snapshot matching no preview is the population most likely to be
 * misnamed, and because the name rule learns which stems are real components from every row's call targets —
 * including theirs (PG18 spec D9).
 *
 * Deliberately **not** `DumbAware`: during indexing the panel holds no rows, and a file on disk saying the
 * project has no preview is a worse answer than a disabled button.
 */
class CoverageReportAction(
    private val project: Project,
    private val rows: () -> List<PreviewEntry>,
    private val orphans: () -> List<PreviewEntry>,
) : AnAction(
    PreviewGalleryBundle.message("action.coverageReport.text"),
    PreviewGalleryBundle.message("action.coverageReport.text"),
    AllIcons.ToolbarDecorator.Export,
) {

    override fun actionPerformed(event: AnActionEvent) {
        val descriptor = FileSaverDescriptor(PreviewGalleryBundle.message("action.coverageReport.text"), "", "md")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as VirtualFile?, DEFAULT_NAME) ?: return
        // Read the rows on the EDT, where the panel owns them, and leave everything else off it: the grouping,
        // the PNG decoding and the write all scale with the project.
        val previews = rows()
        val orphanRows = orphans()
        ApplicationManager.getApplication().executeOnPooledThread {
            val document = try {
                buildDocument(previews, orphanRows)
            } catch (e: IOException) {
                thisLogger().warn("Failed to build the coverage report", e)
                notifyFailure()
                return@executeOnPooledThread
            }
            try {
                wrapper.file.writeText(document)
            } catch (e: IOException) {
                thisLogger().warn("Failed to write the coverage report to ${wrapper.file}", e)
                notifyFailure()
            }
        }
    }

    private fun buildDocument(previews: List<PreviewEntry>, orphanRows: List<PreviewEntry>): String {
        val all = previews + previews.flatMap { it.snapshots } + orphanRows
        val names = SnapshotHealth.check(all)
        val goldens = GoldenInspector.inspect(candidates(previews.flatMap { it.snapshots } + orphanRows))
        return CoverageReport.markdown(previews) + "\n" + HealthReport.markdown(names, goldens)
    }

    /**
     * Resolving a module directory and its reference roots reads the project model, so it takes a read action;
     * decoding the images afterwards deliberately does not (see [GoldenInspector]).
     */
    private fun candidates(snapshots: List<PreviewEntry>): List<GoldenInspector.Candidate> =
        ReadAction.compute<List<GoldenInspector.Candidate>, RuntimeException> {
            snapshots.flatMap { snapshot ->
                val moduleDirectory = ModuleDirectoryResolver.resolve(project, snapshot.file)
                    ?: return@flatMap emptyList()
                ReferenceImageLocator.locate(snapshot, ReferenceRoots.of(moduleDirectory)).map {
                    GoldenInspector.Candidate(snapshot.indexed.composableFqn, snapshot.moduleName, it)
                }
            }
        }

    private fun notifyFailure() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Compose Preview Gallery")
            .createNotification(PreviewGalleryBundle.message("report.saveFailed"), NotificationType.WARNING)
            .notify(project)
    }

    private companion object {
        private const val DEFAULT_NAME = "snapshot-coverage.md"
    }
}
