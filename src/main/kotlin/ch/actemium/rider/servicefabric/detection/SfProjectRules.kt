package ch.actemium.rider.servicefabric.detection

/** A selected Solution Explorer node, reduced to what SF detection needs. */
data class SelectedNode(val filePath: String?, val isUnloaded: Boolean = false)

enum class MenuVisibility {
    HIDDEN,
    ENABLED,

    /** Visible, but the actions are disabled because the project is unloaded. */
    UNLOADED,
}

object SfProjectRules {
    private const val SF_EXTENSION = ".sfproj"

    fun isSfProjectFile(path: String?): Boolean =
        path != null && path.endsWith(SF_EXTENSION, ignoreCase = true)

    /** True if [filePath] is under one of [sfProjectDirs] or has a `PackageRoot` ancestor folder (F5 scope). */
    fun isInSfScope(filePath: String?, sfProjectDirs: Collection<String>): Boolean {
        if (filePath.isNullOrBlank()) return false
        val file = normalize(filePath)
        if (sfProjectDirs.any { dir -> normalize(dir).trimEnd('/').let { it.isNotEmpty() && file.startsWith("$it/") } }) {
            return true
        }
        return file.split('/').dropLast(1).any { it == "packageroot" }
    }

    private fun normalize(path: String): String = path.replace('\\', '/').lowercase()

    fun menuVisibility(selection: List<SelectedNode>, isWindows: Boolean): MenuVisibility {
        if (!isWindows) return MenuVisibility.HIDDEN
        val node = selection.singleOrNull() ?: return MenuVisibility.HIDDEN
        if (!isSfProjectFile(node.filePath)) return MenuVisibility.HIDDEN
        return if (node.isUnloaded) MenuVisibility.UNLOADED else MenuVisibility.ENABLED
    }
}
