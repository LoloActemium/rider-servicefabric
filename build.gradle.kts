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
            sinceBuild = "262"
            untilBuild = "262.*"
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
