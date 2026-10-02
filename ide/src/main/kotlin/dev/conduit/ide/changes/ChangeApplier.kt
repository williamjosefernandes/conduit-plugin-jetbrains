package dev.conduit.ide.changes

import com.intellij.history.LocalHistory
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.conduit.core.PendingDiff
import dev.conduit.ide.ConduitBundle
import dev.conduit.mcp.ide.ProjectFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** A file changed between the moment a change was proposed and the moment it was applied. */
class ChangeConflictException(
    val path: String,
) : IOException(ConduitBundle.message("error.change.conflict", path))

/**
 * Writes reviewed changes to the project. The only place in Conduit that modifies files on the
 * agent's behalf: one undoable command per change set, with a Local History label before it so
 * the user can roll back in one click.
 */
object ChangeApplier {
    /**
     * Brings each file to its [PendingDiff.resolvedContent]: accepted hunks applied, the rest
     * untouched. Creates, deletes and renames files as the diffs say.
     *
     * @throws ChangeConflictException if a file no longer has the content the diff was computed
     *   against; nothing is written in that case
     */
    suspend fun apply(
        project: Project,
        title: String,
        diffs: List<PendingDiff>,
    ) {
        withContext(Dispatchers.EDT) {
            diffs.forEach { diff -> checkUnchanged(project, diff) }
            LocalHistory.getInstance().putSystemLabel(project, ConduitBundle.message("history.before", title))
            WriteCommandAction.writeCommandAction(project).withName(ConduitBundle.message("command.apply", title)).run<IOException> {
                diffs.forEach { diff -> applyOne(project, diff) }
            }
            FileDocumentManager.getInstance().saveAllDocuments()
        }
    }

    private fun checkUnchanged(
        project: Project,
        diff: PendingDiff,
    ) {
        val current = ProjectFiles.find(project, diff.path)?.let(ProjectFiles::text)
        if (current != diff.originalContent) throw ChangeConflictException(diff.path)
    }

    private fun applyOne(
        project: Project,
        diff: PendingDiff,
    ) {
        val content = diff.resolvedContent()
        val existing = ProjectFiles.find(project, diff.path)
        val file =
            when {
                existing == null && content == null -> return
                existing == null -> create(project, diff.path)
                content == null -> {
                    existing.delete(this)
                    return
                }
                else -> existing
            }
        val document =
            FileDocumentManager.getInstance().getDocument(file)
                ?: throw IOException(ConduitBundle.message("error.change.cannotEdit", diff.path))
        if (document.text != content) document.setText(content!!)
        if (diff.newPath != null && diff.resolvedContent() == diff.proposedContent) move(project, file, diff.newPath!!)
    }

    private fun create(
        project: Project,
        path: String,
    ): VirtualFile {
        val (directory, name) = split(path)
        return parent(project, directory).createChildData(this, name)
    }

    private fun move(
        project: Project,
        file: VirtualFile,
        newPath: String,
    ) {
        val (directory, name) = split(newPath)
        val target = parent(project, directory)
        if (target != file.parent) file.move(this, target)
        if (name != file.name) file.rename(this, name)
    }

    private fun split(path: String): Pair<String, String> {
        val normalized = path.replace('\\', '/')
        return normalized.substringBeforeLast('/', "") to normalized.substringAfterLast('/')
    }

    /** The directory [directory] points to, created if needed; relative paths start at the project root. */
    private fun parent(
        project: Project,
        directory: String,
    ): VirtualFile {
        val absolute = directory.startsWith("/") || directory.getOrNull(1) == ':'
        if (absolute) return VfsUtil.createDirectories(directory)
        val roots = ProjectRootManager.getInstance(project)
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val root =
            base?.takeIf { roots.fileIndex.isInContent(it) }
                ?: roots.contentRoots.firstOrNull()
                ?: base
                ?: throw IOException(ConduitBundle.message("error.NO_PROJECT_DIR"))
        return if (directory.isEmpty()) root else VfsUtil.createDirectoryIfMissing(root, directory)
    }
}
