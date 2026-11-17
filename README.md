# Azure Service Fabric for Rider

Rider plugin (frontend-only, Kotlin) for Service Fabric applications (`.sfproj`): package, publish, run/debug configuration,
clean (local cluster), manifest versions, XML schemas and local cluster health in the status bar. See `SPEC.md`.

## Install (users)

1. Rider > Settings > Plugins > ⚙ > *Manage Plugin Repositories…* → add
   `https://github.com/LoloActemium/rider-servicefabric/releases/latest/download/updatePlugins.xml`
2. Marketplace tab → search *Azure Service Fabric* → Install, restart Rider.

Rider then offers new versions automatically. Settings live under *Tools > Service Fabric*.
The plugin targets Rider 2026.3 (build 263.*).

## Requirements (development)

- Windows, JetBrains Rider 2026.3 installed in `%LOCALAPPDATA%\Programs\Rider` (override with `-PriderPath=<path>`).
- JDK 25 for the Gradle toolchain. Rider's bundled JBR works:
  `-Porg.gradle.java.installations.paths=%LOCALAPPDATA%\Programs\Rider\jbr`
  (or set it once in `%USERPROFILE%\.gradle\gradle.properties`). Otherwise Gradle tries to download it (foojay).

## Build

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin            # -> build\distributions\rider-servicefabric-<version>.zip + updatePlugins.xml
.\gradlew.bat verifyPluginStructure verifyPlugin
.\gradlew.bat runIde                 # starts a sandboxed Rider with the plugin
```

## Release (GitHub Releases)

Requires the GitHub CLI (`gh auth login`) and the release commit pushed to `main`.

```powershell
.\gradlew.bat bumpVersion            # 0.1.15 -> 0.1.16 in gradle.properties, removes old zips
.\gradlew.bat test buildPlugin verifyPluginStructure
git commit -am "chore: release 0.1.16"; git push
.\gradlew.bat githubRelease          # creates release v0.1.16 with the zip and updatePlugins.xml
```

`updatePlugins.xml` points to `<pluginRepositoryUrl>/<zip>`, where `{version}` in `pluginRepositoryUrl`
(`gradle.properties`) is replaced by the plugin version.

## Alternative: folder repository

```powershell
.\gradlew.bat publishToRepository -PpluginRepositoryDir=\\server\share\rider-plugins -PpluginRepositoryUrl=
```

With an empty `pluginRepositoryUrl`, the zip URL is a `file:///` URL into `pluginRepositoryDir`; users then add
`file:///<folder>/updatePlugins.xml` as repository.
