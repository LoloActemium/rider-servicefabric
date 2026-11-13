plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val riderPath: String? = providers.gradleProperty("riderPath").orNull
    ?: System.getenv("LOCALAPPDATA")?.let { "$it/Programs/Rider" }
val hasLocalRider = riderPath != null && file(riderPath).exists()

dependencies {
    intellijPlatform {
        if (hasLocalRider) {
            local(riderPath!!)
        } else {
            rider(providers.gradleProperty("riderVersion")) { useInstaller = false }
        }
        bundledModule("intellij.rider.rdclient.dotnet")
        bundledModule("intellij.platform.debugger")
        bundledModule("intellij.platform.debugger.impl")
        bundledPlugin("com.intellij.xml")
        bundledPlugin("com.intellij.modules.jna")
    }

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        id = "ch.actemium.rider.servicefabric"
        name = "Azure Service Fabric"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "263"
            untilBuild = "263.*"
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
    pluginVerification {
        // Plugin ID is fixed by the spec (internal use, not published to Marketplace).
        freeArgs = listOf("-mute", "TemplateWordInPluginId")
        ides {
            if (hasLocalRider) local(riderPath!!) else recommended()
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

// --- Distribution through a custom plugin repository (Rider proposes updates when the version increases) ---

val pluginRepositoryUrl = providers.gradleProperty("pluginRepositoryUrl").orElse("")
val pluginRepositoryDir = providers.gradleProperty("pluginRepositoryDir").orElse("")

val generateUpdatePlugins = tasks.register("generateUpdatePlugins") {
    group = "distribution"
    description = "Writes build/distributions/updatePlugins.xml for a custom plugin repository."
    val archive = tasks.buildPlugin.flatMap { it.archiveFile }
    val pluginVersion = project.version.toString()
    val baseUrl = pluginRepositoryUrl
    val repositoryDir = pluginRepositoryDir
    val output = layout.buildDirectory.file("distributions/updatePlugins.xml")
    inputs.file(archive)
    inputs.property("version", pluginVersion)
    inputs.property("baseUrl", baseUrl)
    inputs.property("repositoryDir", repositoryDir)
    outputs.file(output)
    doLast {
        val zip = archive.get().asFile
        val base = baseUrl.get().trimEnd('/')
        val dir = repositoryDir.get()
        val url = when {
            base.isNotEmpty() -> "$base/${zip.name}"
            dir.isNotBlank() -> zip.toPath().fileSystem.getPath(dir, zip.name).toUri().toString()
            else -> zip.toPath().toUri().toString()
        }
        output.get().asFile.writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<plugins>
            |  <plugin id="ch.actemium.rider.servicefabric" url="$url" version="$pluginVersion">
            |    <idea-version since-build="263" until-build="263.*"/>
            |    <name>Azure Service Fabric</name>
            |    <vendor>Actemium</vendor>
            |    <description>Package and publish Azure Service Fabric applications from Rider.</description>
            |  </plugin>
            |</plugins>
            |""".trimMargin(),
        )
    }
}

tasks.buildPlugin {
    finalizedBy(generateUpdatePlugins)
}

tasks.register<Copy>("publishToRepository") {
    group = "distribution"
    description = "Copies the plugin zip and updatePlugins.xml to -PpluginRepositoryDir."
    dependsOn(generateUpdatePlugins)
    val target = pluginRepositoryDir
    from(tasks.buildPlugin.flatMap { it.archiveFile })
    from(layout.buildDirectory.file("distributions/updatePlugins.xml"))
    into(target.map { dir -> require(dir.isNotBlank()) { "Set -PpluginRepositoryDir=<folder>" }; dir })
}

tasks.register("bumpVersion") {
    group = "distribution"
    description = "Increments the patch number of pluginVersion in gradle.properties and removes stale plugin zips."
    val properties = layout.projectDirectory.file("gradle.properties")
    val distributions = layout.buildDirectory.dir("distributions")
    doLast {
        val file = properties.asFile
        val text = file.readText()
        val match = Regex("""(?m)^pluginVersion=(\d+)\.(\d+)\.(\d+)[ \t]*$""").find(text)
            ?: error("pluginVersion=<major>.<minor>.<patch> not found in gradle.properties")
        val (major, minor, patch) = match.destructured
        val next = "$major.$minor.${patch.toInt() + 1}"
        file.writeText(text.replaceRange(match.range, "pluginVersion=$next"))
        distributions.get().asFile.listFiles { f -> f.isFile && f.name.endsWith(".zip") }?.forEach { it.delete() }
        println("pluginVersion: $major.$minor.$patch -> $next")
    }
}
