package ch.actemium.rider.servicefabric.build

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/** Makes MSBuild diagnostics (`path(line[,col]): error|warning ...`) clickable. */
class MsBuildOutputFilter(private val project: Project) : Filter {

    data class Location(val path: String, val line: Int, val column: Int, val start: Int, val end: Int)

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val location = parse(line) ?: return null
        val file = File(location.path)
        if (!file.isAbsolute) return null
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return null
        val offset = entireLength - line.length
        val info = OpenFileHyperlinkInfo(project, virtualFile, maxOf(location.line - 1, 0), maxOf(location.column - 1, 0))
        return Filter.Result(offset + location.start, offset + location.end, info)
    }

    companion object {
        private val PATTERN =
            Regex("""^\s*(?:\d+>)?\s*(.+?)\((\d+)(?:,(\d+))?(?:,\d+,\d+)?\)\s*:\s*(?:[\w ]+\s)?(?:error|warning)\b""")

        /** Location of a diagnostic in [line]; start/end delimit `path(line,col)`. */
        fun parse(line: String): Location? {
            val m = PATTERN.find(line) ?: return null
            val path = m.groups[1]!!
            val lineNumber = m.groupValues[2].toIntOrNull() ?: return null
            val column = m.groupValues[3].toIntOrNull() ?: 1
            val end = line.indexOf(')', m.groups[2]!!.range.last) + 1
            return Location(path.value.trim(), lineNumber, column, path.range.first, end)
        }
    }
}
