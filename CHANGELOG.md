# Changelog

nimWifi is a personal fork of [WiProber](https://github.com/htechno/WiProber) (MIT). Versions follow semantic versioning from 4.0.0.

## 4.0.0

### Added
- Point sheet: tap a point → *View results* shows its scans and every test run with values and errors.
- *Skip point* in the test progress dialog (the run is recorded as skipped by the user).
- Configuration screen: roaming probe, network tests, HTTP scripts and credentials (names only); import a JSON, HAR or configuration ZIP; export the current configuration without credential values.
- Configuration ZIP (`wiprober-config.json` + `.har` files) with `_comment` keys; commented example in `docs/examples/`.
- Signal profiles (handheld, voice, data) with the same thresholds as the wifianalisys report.
- Opening ESX files, nimWifi export ZIPs (their ESX is imported) and configuration files from other apps.
- Foreground service while a Continuous track or active tests run, so they continue with the screen off.

### Changed
- Spanish and English interface (English is the default).
- Version scheme: semantic versioning (`vX.Y.Z` tags), versionCode 4000000.
- About: description, build identity and links to nimWifi and to WiProber.
- Autosave runs outside the survey screen, retries and warns when saving keeps failing.
- Active tests, roaming and template import moved out of the survey screen code.

### Fixed
- ICMP ping now binds to the Wi-Fi source address (binding to the interface name needs a privilege apps do not have).
- A slow or failing DNS no longer slows the roaming probe; the attempt is recorded as `resolution_failed`.

## 3.0.0-throughput.5

First nimWifi build: rename from WiProber, audit blocks 1–6 (measurement network, saved and cancellable runs, roaming time base, HAR and credential hardening, point reuse and per-SSID report, build identity), crash fix for the first active test, survey-guide grid.
