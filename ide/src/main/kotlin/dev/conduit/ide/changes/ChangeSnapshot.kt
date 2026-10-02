package dev.conduit.ide.changes

import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.conduit.core.FileChange
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.vcs.GitCli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/**
 * One file as it was when a snapshot was taken and as it is now.
 *
 * @property path path relative to the snapshot root, `/`-separated
 * @property before null when the file did not exist
 * @property after null when the file no longer exists
 */
class TrackedChange(
    val path: String,
    val absolutePath: String,
    val before: String?,
    val after: String?,
    val change: FileChange,
)

/**
 * Remembers the state of a directory so that what changed afterwards can be listed, shown as a
 * diff and reverted, whoever made the change: agents write to disk themselves (DECISIONS D-016).
 *
 * In a git repository the snapshot is a `git stash create` commit (tracked files, including
 * uncommitted edits, without touching the working tree or the stash list) plus the content of
 * untracked files; elsewhere, a Local History label. Files written while the snapshot is open
 * are also collected from VFS events, so changes outside git's view are not missed.
 */
class ChangeSnapshot private constructor(
    private val project: Project,
    /** Absolute, `/`-separated directory the snapshot covers. */
    val root: String,
    private val revision: String?,
    /** Content at snapshot time of files git cannot tell: untracked ones, or every small text file outside git. */
    private val contents: Map<String, String>,
    /** Outside git: every file with its size and date, to find what changed even where the VFS never looked. */
    private val tree: Map<String, Long>?,
    private val label: Label,
) : Disposable {
    private val touched = ConcurrentHashMap.newKeySet<String>()

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    events.forEach { event -> event.path.takeIf { it.startsWith("$root/") }?.let(touched::add) }
                }
            },
        )
    }

    /**
     * Everything that differs from the snapshot now. Refreshes the file system first, because
     * agents write behind the IDE's back.
     */
    suspend fun changes(): List<TrackedChange> {
        refresh()
        val candidates = LinkedHashSet<String>()
        touched.mapTo(candidates) { it.removePrefix("$root/") }
        if (revision != null) {
            candidates += lines(GitCli.run(root, "diff", "--name-only", "--relative", "-z", revision).stdout)
            candidates += lines(GitCli.run(root, "ls-files", "--others", "--exclude-standard", "-z").stdout)
            candidates += contents.keys
        }
        if (tree != null) {
            val now = withContext(Dispatchers.IO) { scan(root) }
            now.forEach { (path, stamp) -> if (tree[path] != stamp) candidates += path }
            tree.keys.forEach { if (it !in now) candidates += it }
        }
        return candidates
            .filter { relevant(it) }
            .mapNotNull { path ->
                val before = before(path)
                // A file that existed but whose earlier content is unknown (too large to keep) is not reported as new.
                if (before == null && tree?.containsKey(path) == true) return@mapNotNull null
                val after = current(path)
                FileChange.between(path, before, after)?.let { TrackedChange(path, "$root/$path", before, after, it) }
            }.sortedBy { it.path }
    }

    /** Puts [changes] back the way they were in the snapshot, as one undoable command. */
    suspend fun revert(changes: List<TrackedChange>) = Companion.revert(project, changes)

    override fun dispose() = Unit

    /** Stops collecting VFS events. */
    fun close() = Disposer.dispose(this)

    private suspend fun before(path: String): String? {
        contents[path]?.let { return it }
        if (revision != null) return GitCli.show(root, revision, path)?.normalized()
        return withContext(Dispatchers.IO) {
            label
                .getByteContent("$root/$path")
                ?.takeIf { !it.isDirectory }
                ?.bytes
                ?.let(::decode)
        }
    }

    private suspend fun current(path: String): String? =
        readAction {
            val file = LocalFileSystem.getInstance().findFileByPath("$root/$path")?.takeIf { it.isValid && !it.isDirectory }
            file?.let { text(it) }
        }

    /** Leaves out build output, IDE metadata, ignored files and binaries. */
    private suspend fun relevant(path: String): Boolean {
        if (path.isEmpty() || path.split('/').any { it in SKIPPED_DIRS }) return false
        return readAction {
            val file = LocalFileSystem.getInstance().findFileByPath("$root/$path")
            when {
                file == null -> true
                file.isDirectory || file.fileType.isBinary -> false
                ChangeListManager.getInstance(project).isIgnoredFile(file) -> false
                else -> !ProjectFileIndex.getInstance(project).isExcluded(file)
            }
        }
    }

    private suspend fun refresh() {
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByPath(root) ?: return
        withContext(Dispatchers.IO) { VfsUtil.markDirtyAndRefresh(false, true, true, dir) }
    }

    companion object {
        private val SKIPPED_DIRS = setOf(".git", ".idea", ".gradle", "build", "out", "target", "node_modules", ".conduit-worktrees")
        private const val MAX_UNTRACKED_FILE = 512 * 1024
        private const val MAX_UNTRACKED_TOTAL = 4 * 1024 * 1024
        private const val MAX_SCANNED = 50_000
        private const val STAMP_FACTOR = 31L

        /**
         * Takes a snapshot of [root] (the project directory by default). Saves open editors first,
         * so the snapshot holds what the user sees.
         */
        suspend fun take(
            project: Project,
            root: String,
            labelText: String,
        ): ChangeSnapshot {
            withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().saveAllDocuments() }
            val dir = root.replace('\\', '/').trimEnd('/')
            val label = LocalHistory.getInstance().putSystemLabel(project, labelText)
            val revision = if (GitCli.isRepository(dir)) gitRevision(dir) else null
            if (revision != null) return ChangeSnapshot(project, dir, revision, untrackedContent(dir), null, label)
            val tree = withContext(Dispatchers.IO) { scan(dir) }
            return ChangeSnapshot(project, dir, null, withContext(Dispatchers.IO) { textContent(dir, tree.keys) }, tree, label)
        }

        /** Restores the `before` side of [changes]: rewrites edited files, recreates deleted ones, deletes created ones. */
        suspend fun revert(
            project: Project,
            changes: List<TrackedChange>,
        ) {
            if (changes.isEmpty()) return
            LocalHistory.getInstance().putSystemLabel(project, ConduitBundle.message("history.beforeRevert"))
            withContext(Dispatchers.EDT) {
                WriteCommandAction.writeCommandAction(project).withName(ConduitBundle.message("command.revert")).run<java.io.IOException> {
                    changes.forEach { change -> restore(change) }
                }
            }
        }

        private fun restore(change: TrackedChange) {
            val fs = LocalFileSystem.getInstance()
            val existing = fs.refreshAndFindFileByPath(change.absolutePath)
            val before = change.before
            when {
                before == null -> existing?.delete(ChangeSnapshot::class.java)
                existing != null -> write(existing, before)
                else -> {
                    val parent = VfsUtil.createDirectoryIfMissing(change.absolutePath.substringBeforeLast('/')) ?: return
                    write(parent.createChildData(ChangeSnapshot::class.java, change.absolutePath.substringAfterLast('/')), before)
                }
            }
        }

        private fun write(
            file: VirtualFile,
            text: String,
        ) {
            val document = FileDocumentManager.getInstance().getDocument(file)
            if (document != null) {
                document.setText(text)
                FileDocumentManager.getInstance().saveDocument(document)
            } else {
                VfsUtil.saveText(file, text)
            }
        }

        private suspend fun gitRevision(dir: String): String? {
            val stash =
                GitCli
                    .run(dir, "stash", "create")
                    .takeIf { it.ok }
                    ?.stdout
                    ?.trim()
                    .orEmpty()
            if (stash.isNotEmpty()) return stash
            return GitCli
                .run(dir, "rev-parse", "--verify", "--quiet", "HEAD")
                .takeIf { it.ok }
                ?.stdout
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        private suspend fun untrackedContent(dir: String): Map<String, String> {
            val paths = lines(GitCli.run(dir, "ls-files", "--others", "--exclude-standard", "-z").stdout)
            return withContext(Dispatchers.IO) { textContent(dir, paths) }
        }

        /** The content of the small text files among [paths], up to an overall limit. Call on a background thread. */
        private fun textContent(
            dir: String,
            paths: Collection<String>,
        ): Map<String, String> {
            var total = 0
            return buildMap {
                for (path in paths) {
                    val file = File(dir, path)
                    if (!file.isFile || file.length() > MAX_UNTRACKED_FILE) continue
                    total += file.length().toInt()
                    if (total > MAX_UNTRACKED_TOTAL) break
                    val bytes = file.readBytes()
                    if (!isBinary(bytes)) put(path, decode(bytes))
                }
            }
        }

        /** Every file under [dir] (build output and metadata left out) with a stamp of its size and date. */
        private fun scan(dir: String): Map<String, Long> {
            val base = Path.of(dir)
            val result = LinkedHashMap<String, Long>()
            Files.walkFileTree(
                base,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        path: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult =
                        if (path != base &&
                            path.fileName.toString() in SKIPPED_DIRS
                        ) {
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }

                    override fun visitFile(
                        path: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        if (!attributes.isRegularFile) return FileVisitResult.CONTINUE
                        val relative = base.relativize(path).joinToString("/")
                        result[relative] = attributes.size() * STAMP_FACTOR + attributes.lastModifiedTime().toMillis()
                        return if (result.size >= MAX_SCANNED) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        path: Path,
                        error: java.io.IOException,
                    ): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
            return result
        }

        private fun text(file: VirtualFile): String =
            FileDocumentManager.getInstance().getDocument(file)?.text ?: decode(file.contentsToByteArray())

        private fun lines(zeroSeparated: String): List<String> = zeroSeparated.split('\u0000').map { it.trim() }.filter { it.isNotEmpty() }

        private fun decode(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8).normalized()

        private fun String.normalized(): String = replace("\r\n", "\n")

        private fun isBinary(bytes: ByteArray): Boolean = bytes.take(BINARY_PROBE).any { it == 0.toByte() }

        private const val BINARY_PROBE = 8_000
    }
}
