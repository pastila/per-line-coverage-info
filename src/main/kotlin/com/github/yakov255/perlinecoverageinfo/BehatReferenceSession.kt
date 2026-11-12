package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.io.IOException

/** One background pass over historical sources and a consistent set of current document texts. */
internal class BehatReferenceSession(private val project: Project, private val gitRoot: File?) {
    private val renames = mutableMapOf<String, Map<String, String?>>()

    fun resolve(requests: List<BehatTestReferences.Request>): BehatTestReferences.Result =
        BehatTestReferences(
            readSource = { revision, path -> gitRoot?.let { GitCommand.runRaw(it, "show", "$revision:$path") } },
            readCurrent = ::readCurrent,
            currentPath = ::currentPath,
        ).resolve(requests)

    private fun readCurrent(path: String): String? {
        if (project.isDisposed) return null
        val file = if (gitRoot != null) LocalFileSystem.getInstance().findFileByIoFile(File(gitRoot, path))
            else project.guessProjectDir()?.let { VfsUtil.findRelativeFile(path, it) }
        if (file == null || file.isDirectory) return null
        val documentText = ReadAction.compute<String?, RuntimeException> {
            FileDocumentManager.getInstance().getCachedDocument(file)?.text
        }
        return documentText ?: try { String(file.contentsToByteArray(), Charsets.UTF_8) } catch (_: IOException) { null }
    }

    private fun currentPath(revision: String, path: String): String? {
        val root = gitRoot ?: return null
        val changes = renames.getOrPut(revision) {
            val diff = GitCommand.runRaw(root, "diff", "--no-ext-diff", "--no-textconv", "--name-status", "-z", "-M", revision, "--")
                ?: throw IOException("Cannot map test paths from the coverage revision")
            val fields = diff.split('\u0000')
            buildMap {
                var i = 0
                while (i < fields.size && fields[i].isNotEmpty()) {
                    val status = fields[i++]
                    val old = fields.getOrNull(i++) ?: break
                    when {
                        status.startsWith('R') -> put(old, fields.getOrNull(i++) ?: break)
                        status.startsWith('C') -> i++
                        status == "D" -> put(old, null)
                    }
                }
            }
        }
        return if (changes.containsKey(path)) changes[path] else path
    }
}
