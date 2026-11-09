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
