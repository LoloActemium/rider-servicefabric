# Changelog

All notable changes to the Azure Service Fabric plugin for Rider are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Richer plugin description in Rider's plugin manager (features, getting started, requirements).

## [0.1.17] - 2026-11-17

### Added

- Plugin logo in Rider's plugin manager.

## [0.1.16] - 2026-11-17

### Added

- Icons on the Service Fabric menu actions (Package, Publish, Clean, Edit Versions, Create Run Configuration,
  Re-check Environment).
- "What's New" notes in Rider and GitHub release notes, generated from this changelog.

## [0.1.15] - 2026-11-17

### Added

- Releases published on GitHub Releases: add
  `https://github.com/LoloActemium/rider-servicefabric/releases/latest/download/updatePlugins.xml`
  as a plugin repository to get updates automatically.

### Fixed

- The orange "starting" dot is vertically aligned with the other status bar dots.

## [0.1.14] - 2026-11-17

### Changed

- The Service Fabric submenu is placed right after *Advanced Build Actions* in the Solution Explorer context menu.

## [0.1.13] - 2026-11-17

### Changed

- The cluster indicator reflects the infrastructure only (nodes and `fabric:/System`); an unhealthy application no
  longer turns the cluster red. Other unhealthy applications are listed in the tooltip.

### Added

- Orange "starting" state with a spinner while an application starts (partitions or replicas not ready, or right
  after a deployment from Rider). Errors during a startup grace period (setting, 120 s by default) are shown as
  starting.

## [0.1.12] - 2026-11-13

### Fixed

- The "ready" notification is sent only after the application has been healthy continuously for a configurable
  duration (30 s by default), and the widget keeps polling in the background while a deployment is pending.

## [0.1.11] - 2026-11-13

### Fixed

- Build: the first build after `bumpVersion` uses the new version.

## [0.1.10] - 2026-11-13

### Added

- Publish and Clean warn when the application is being debugged and stop the debug sessions first; Rider's Clean
  stops them too before removing the application from the local cluster.

## [0.1.9] - 2026-11-13

### Added

- Status bar widget with the local cluster state and the health of each application of the solution, a popup with
  details and a link to Service Fabric Explorer, and an "application is ready" notification after a deployment.

## [0.1.8] - 2026-11-13

### Fixed

- No more internal error logged when a debug session starts.

## [0.1.7] - 2026-11-13

### Fixed

- Run stays disabled while debugging; every debug session is tracked, even when Rider creates it late.

## [0.1.6] - 2026-11-13

### Added

- `Service Fabric > Create Run Configuration` recreates a deleted run configuration.

## [0.1.5] - 2026-11-13

### Fixed

- Debug attaches to every service process (it attached to the first started one only).
- Run and Debug are disabled while the application is being debugged.

## [0.1.4] - 2026-11-13

### Changed

- Targets Rider 2026.3 (build 263.x).

## [0.1.3] - 2026-11-11

### Added

- *Service Fabric Application* run configuration: Run publishes, Debug publishes and attaches the debugger.
- Completion and validation in Service Fabric manifests from the SDK XML schemas.
- `Service Fabric > Edit Versions` dialog with automatic version roll-up.
- `Service Fabric > Clean`: removes the application from the local cluster, runs MSBuild Clean and deletes `pkg`.
- Rider's *Build > Clean* also removes Service Fabric applications from the local cluster.

## [0.1.2] - 2026-11-10

### Added

- First version: Service Fabric project detection and context menu, settings, Package and Publish actions,
  environment diagnostics with Re-check, Service Fabric icons.

[Unreleased]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.17...HEAD
[0.1.17]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.16...v0.1.17
[0.1.16]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.15...v0.1.16
[0.1.15]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.14...v0.1.15
[0.1.14]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.13...v0.1.14
[0.1.13]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.12...v0.1.13
[0.1.12]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.11...v0.1.12
[0.1.11]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.10...v0.1.11
[0.1.10]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.9...v0.1.10
[0.1.9]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.8...v0.1.9
[0.1.8]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.7...v0.1.8
[0.1.7]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.6...v0.1.7
[0.1.6]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/LoloActemium/rider-servicefabric/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/LoloActemium/rider-servicefabric/commits/v0.1.2
