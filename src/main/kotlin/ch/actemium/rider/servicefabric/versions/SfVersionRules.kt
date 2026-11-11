package ch.actemium.rider.servicefabric.versions

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringReader
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class PackageVersion(val element: String, val name: String, val version: String) {
    val key: String get() = "$element:$name"
}

data class ServiceVersions(
    val manifestName: String,
    val refVersion: String,
    val manifestFile: Path?,
    val manifestVersion: String?,
    val packages: List<PackageVersion>,
) {
    val available: Boolean get() = manifestFile != null && manifestVersion != null
    val current: String get() = manifestVersion ?: refVersion
    val key: String get() = SfVersionRules.serviceKey(manifestName)
    fun packageKey(p: PackageVersion): String = SfVersionRules.packageKey(manifestName, p)
}

data class ApplicationVersions(
    val manifestFile: Path,
    val typeName: String,
    val version: String,
    val services: List<ServiceVersions>,
)

/** One attribute value to replace: first element [element] (matching [filterAttr]=[filterValue] if set) in [file]. */
data class AttributeEdit(
    val file: Path,
    val element: String,
    val filterAttr: String?,
    val filterValue: String?,
    val attribute: String,
    val value: String,
)

object SfVersionRules {
    const val APP_KEY = "app"
    val PACKAGE_ELEMENTS = listOf("CodePackage", "ConfigPackage", "DataPackage")

    fun serviceKey(name: String) = "svc:$name"
    fun packageKey(service: String, p: PackageVersion) = "pkg:$service:${p.key}"

    fun applicationManifestPath(sfproj: Path): Path =
        sfproj.parent.resolve("ApplicationPackageRoot").resolve("ApplicationManifest.xml")

    /** Builds the model; [read] returns file text or null when missing/unreadable. Throws when the application manifest is unusable. */
    fun load(sfproj: Path, read: (Path) -> String?): ApplicationVersions {
        val appFile = applicationManifestPath(sfproj)
        val appDoc = parse(read(appFile)) ?: throw IllegalStateException("Cannot read $appFile")
        val root = appDoc.documentElement
        val manifests = projectReferences(read(sfproj)).mapNotNull { csproj ->
            val file = sfproj.parent.resolve(csproj.replace('\\', '/')).normalize().parent
                ?.resolve("PackageRoot")?.resolve("ServiceManifest.xml") ?: return@mapNotNull null
            val doc = parse(read(file)) ?: return@mapNotNull null
            Triple(doc.documentElement.getAttribute("Name"), file, doc.documentElement)
        }
        val services = elements(appDoc, "ServiceManifestRef").map { ref ->
            val name = ref.getAttribute("ServiceManifestName")
            val found = manifests.firstOrNull { it.first == name }
            val manifest = found?.third
            ServiceVersions(
                manifestName = name,
                refVersion = ref.getAttribute("ServiceManifestVersion"),
                manifestFile = found?.second,
                manifestVersion = manifest?.takeIf { it.hasAttribute("Version") }?.getAttribute("Version"),
                packages = manifest?.let { packages(it) }.orEmpty(),
            )
        }
        return ApplicationVersions(appFile, root.getAttribute("ApplicationTypeName"), root.getAttribute("ApplicationTypeVersion"), services)
    }

    fun projectReferences(sfprojText: String?): List<String> {
        val doc = parse(sfprojText) ?: return emptyList()
        return elements(doc, "ProjectReference").map { it.getAttribute("Include").trim() }.filter { it.isNotEmpty() }
    }

    private fun packages(root: Element): List<PackageVersion> {
        val result = mutableListOf<PackageVersion>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            val local = el.localName ?: el.tagName
            if (local in PACKAGE_ELEMENTS) result += PackageVersion(local, el.getAttribute("Name"), el.getAttribute("Version"))
        }
        return result
    }

    private fun elements(doc: Document, local: String): List<Element> {
        val nodes = doc.getElementsByTagNameNS("*", local)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun parse(xml: String?): Document? {
        if (xml.isNullOrBlank()) return null
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().apply { setErrorHandler(null) }
                .parse(InputSource(StringReader(xml.removePrefix("\uFEFF"))))
        } catch (_: Exception) {
            null
        }
    }

    /** Current versions keyed by node key. */
    fun currentVersions(app: ApplicationVersions): Map<String, String> = buildMap {
        put(APP_KEY, app.version)
        app.services.forEach { s ->
            put(s.key, s.current)
            s.packages.forEach { put(s.packageKey(it), it.version) }
        }
    }

    /** Segment-wise numeric comparison; null when either is non-numeric. */
    fun compare(a: String, b: String): Int? {
        val pa = segments(a) ?: return null
        val pb = segments(b) ?: return null
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { java.math.BigInteger.ZERO }.compareTo(pb.getOrElse(i) { java.math.BigInteger.ZERO })
            if (c != 0) return c
        }
        return 0
    }

    private fun segments(v: String): List<java.math.BigInteger>? {
        val parts = v.split('.')
        if (parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        return parts.map { it.toBigInteger() }
    }

    /** Highest version, or null if empty or any is non-numeric. */
    fun max(versions: List<String>): String? {
        if (versions.isEmpty()) return null
        var best = versions[0]
        for (v in versions.drop(1)) {
            val c = compare(v, best) ?: return null
            if (c > 0) best = v
        }
        return if (compare(best, best) == null) null else best
    }

    /**
     * Applies the rollup after [changedKey] was edited. Returns the updated map.
     * A parent in [manual] keeps its value when it is higher than the computed one.
     */
    fun rollup(app: ApplicationVersions, values: Map<String, String>, manual: Set<String>, changedKey: String): Map<String, String> {
        val result = values.toMutableMap()
        val current = currentVersions(app)
        fun apply(parentKey: String, children: List<String>): Boolean {
            val candidates = children.map { result[it].orEmpty() } + current[parentKey].orEmpty() +
                listOfNotNull(result[parentKey]?.takeIf { parentKey in manual })
            result[parentKey] = max(candidates) ?: return false
            return true
        }
        val service = app.services.firstOrNull { s -> s.packages.any { s.packageKey(it) == changedKey } }
        if (service != null) {
            if (service.packages.isNotEmpty()) apply(service.key, service.packages.map { service.packageKey(it) })
        } else if (app.services.none { it.key == changedKey }) {
            return result
        }
        apply(APP_KEY, app.services.map { it.key })
        return result
    }

    /** Null when all versions are valid, else the first invalid key. */
    fun firstInvalid(values: Map<String, String>): String? =
        values.entries.firstOrNull { (_, v) -> !isValid(v) }?.key

    fun isValid(version: String): Boolean = version.isNotEmpty() && version.none { it.isWhitespace() }

    fun edits(app: ApplicationVersions, values: Map<String, String>): List<AttributeEdit> {
        val result = mutableListOf<AttributeEdit>()
        values[APP_KEY]?.takeIf { it != app.version }?.let {
            result += AttributeEdit(app.manifestFile, "ApplicationManifest", null, null, "ApplicationTypeVersion", it)
        }
        for (s in app.services) {
            if (!s.available) continue
            val file = s.manifestFile!!
            val newService = values[s.key] ?: s.current
            if (newService != s.manifestVersion) {
                result += AttributeEdit(file, "ServiceManifest", null, null, "Version", newService)
            }
            if (newService != s.refVersion) {
                result += AttributeEdit(app.manifestFile, "ServiceManifestRef", "ServiceManifestName", s.manifestName, "ServiceManifestVersion", newService)
            }
            for (p in s.packages) {
                val v = values[s.packageKey(p)] ?: continue
                if (v != p.version) result += AttributeEdit(file, p.element, "Name", p.name, "Version", v)
            }
        }
        return result
    }

    private val COMMENT = Regex("<!--.*?-->|<!\\[CDATA\\[.*?]]>", RegexOption.DOT_MATCHES_ALL)
    private val START_TAG = Regex("<([A-Za-z_][\\w.:-]*)(\\s[^<>]*?)?/?>")

    /** Range of the attribute value (without quotes) in [text], or null. Comments are ignored. */
    fun findValueRange(text: CharSequence, edit: AttributeEdit): IntRange? {
        val masked = COMMENT.replace(text) { " ".repeat(it.value.length) }
        for (tag in START_TAG.findAll(masked)) {
            if (tag.groupValues[1].substringAfter(':') != edit.element) continue
            val attrs = tag.groups[2] ?: continue
            if (edit.filterAttr != null && attributeValue(masked, attrs.range, edit.filterAttr)?.let { masked.substring(it) } != edit.filterValue) continue
            return attributeValue(masked, attrs.range, edit.attribute)
        }
        return null
    }

    private fun attributeValue(text: String, range: IntRange, name: String): IntRange? {
        val m = Regex("(?:^|\\s)" + Regex.escape(name) + "\\s*=\\s*([\"'])(.*?)\\1", RegexOption.DOT_MATCHES_ALL)
            .find(text.substring(range)) ?: return null
        val g = m.groups[2]!!
        return (range.first + g.range.first) until (range.first + g.range.first + g.value.length)
    }

    /** Applies edits to a text (used by tests and as the reference for the writer). */
    fun applyToText(text: String, edits: List<AttributeEdit>): String? {
        val ranges = edits.map { (findValueRange(text, it) ?: return null) to it.value }.sortedByDescending { it.first.first }
        val sb = StringBuilder(text)
        ranges.forEach { (r, v) -> sb.replace(r.first, r.last + 1, v) }
        return sb.toString()
    }
}
