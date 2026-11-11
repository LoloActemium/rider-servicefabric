package ch.actemium.rider.servicefabric.versions

import ch.actemium.rider.servicefabric.SfBundle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.ReadonlyStatusHandler

/** Applies attribute edits through documents, in one undoable command. Must be called on the EDT. */
object SfVersionWriter {

    /** Returns null on success, else an error message; nothing is written on error. */
    fun apply(project: Project, edits: List<AttributeEdit>): String? {
        if (edits.isEmpty()) return null
        val lfs = LocalFileSystem.getInstance()
        val byFile = edits.groupBy { it.file }
        val files = byFile.keys.associateWith {
            lfs.refreshAndFindFileByNioFile(it) ?: return SfBundle.message("versions.error.fileMissing", it.toString())
        }
        val status = ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(files.values)
        if (status.hasReadonlyFiles()) return status.readonlyFilesMessage
        val fdm = FileDocumentManager.getInstance()
        val planned = byFile.map { (path, fileEdits) ->
            val document = fdm.getDocument(files.getValue(path))
                ?: return SfBundle.message("versions.error.fileMissing", path.toString())
            val text = document.charsSequence
            val ranges = fileEdits.map { edit ->
                (SfVersionRules.findValueRange(text, edit)
                    ?: return SfBundle.message("versions.error.attributeMissing", edit.attribute, edit.element, path.toString())) to edit.value
            }.sortedByDescending { it.first.first }
            document to ranges
        }
        WriteCommandAction.writeCommandAction(project)
            .withName(SfBundle.message("versions.command.name"))
            .withGlobalUndo()
            .run<RuntimeException> {
                planned.forEach { (document, ranges) ->
                    ranges.forEach { (r, v) -> document.replaceString(r.first, r.last + 1, v) }
                }
            }
        planned.forEach { (document, _) -> fdm.saveDocument(document) }
        return null
    }
}
