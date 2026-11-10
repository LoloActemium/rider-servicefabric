# Azure Service Fabric for Rider

Rider plugin (frontend-only, Kotlin) to package and publish Service Fabric applications (`.sfproj`). See `SPEC.md`.

## Requirements

- Windows, JetBrains Rider 2026.2 installed in `%LOCALAPPDATA%\Programs\Rider` (override with `-PriderPath=<path>`).
- JDK 25 for the Gradle toolchain. Rider's bundled JBR works:
  `-Porg.gradle.java.installations.paths=%LOCALAPPDATA%\Programs\Rider\jbr`
  (or set it once in `%USERPROFILE%\.gradle\gradle.properties`). Otherwise Gradle tries to download it (foojay).

## Build

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin            # -> build\distributions\rider-servicefabric-<version>.zip
.\gradlew.bat verifyPluginStructure verifyPlugin
```

## Run

```powershell
.\gradlew.bat runIde                 # starts a sandboxed Rider with the plugin
```

## Install

Rider > Settings > Plugins > ⚙ > *Install Plugin from Disk…* and pick the zip from `build\distributions`.

Settings live under *Tools > Service Fabric*.

## Updates through a custom plugin repository

Rider proposes updates only for plugins installed from a repository (not from disk).

```powershell
.\gradlew.bat bumpVersion                                             # 0.1.1 -> 0.1.2 in gradle.properties
.\gradlew.bat publishToRepository -PpluginRepositoryDir=\\server\share\rider-plugins
# or, when the folder is served over HTTP:
.\gradlew.bat publishToRepository -PpluginRepositoryDir=<folder> -PpluginRepositoryUrl=https://intranet/rider-plugins
```

`buildPlugin` always writes `build\distributions\updatePlugins.xml`; `publishToRepository` copies it with the zip.
The zip URL is `<pluginRepositoryUrl>/<zip>` when set, else a `file:///` URL to `pluginRepositoryDir`.

Each developer adds the repository once: *Settings > Plugins > ⚙ > Manage Plugin Repositories…* →
`file:///<folder>/updatePlugins.xml` (or the HTTP URL), then installs *Azure Service Fabric* from the
Marketplace tab. Rider then offers new versions automatically.
