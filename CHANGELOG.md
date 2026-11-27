# Changelog

nimWifi is a personal fork of [WiProber](https://github.com/htechno/WiProber) (MIT). Versions follow semantic versioning from 4.0.0.

## 4.0.0-rc.4

### Added
- **Report verdict**: optional `requirements` in `wiprober-config.json` (private iPerf3 minimum download/upload, latency median and p95, sample/ping loss, roaming outage window and losses per floor; no defaults). The report and the export say, per floor and SSID, whether it meets them (meets / does not meet / missing data, with explicit denominators) and which points fail with requirement, value, threshold and `runId`. Without requirements nothing changes.
- **AP names**: an optional BSSID → AP table (`apNames`, or `ap-names.csv` in the configuration ZIP) shows AP names next to BSSIDs in the report and adds them to the export. Display only: nothing is inferred or merged.

## 4.0.0-rc.3

Fixes over `v4.0.0-rc.2` (not tagged yet).

### Fixed
- **Field build**: the APK identity check read the package from the wrong `name=` field and failed with `Package 16`. It now lives in `scripts/verify-apk.sh` (anchored parsing, any `aapt`/`apksigner` failure fails the step) with regression tests in `scripts/test-verify-apk.sh`.
- **Active tests never start on an unchecked Wi-Fi**: without a network from the check (no Wi-Fi, or the check could not run) no other network is chosen silently; a Wi-Fi that appears meanwhile goes through the check and decision first (`network_changed`, `unchecked`), and without Wi-Fi nothing is sent and the reason is recorded.
- **Drafts from rc.1 resumed with another probe** keep the original target and interval of their old probes (method `unknown`); stretches without probes stay without one.
- **Report floor totals** count every event of the floor in scope once, by its identity, also events of SSIDs without a section; two independent identical events count as two.

## 4.0.0-rc.2

Fixes over `v4.0.0-rc.1`.

### Fixed
- **Restore and replace** can no longer lose the project if Android kills the app midway: each restore keeps a journal (`prepared` → `parking` → `installed`), the original is kept until the installed copy opens, and an interrupted restore is finished or rolled back on the next start (idempotent). `.deleting-*` is only used by explicit deletion.
- **Route drafts** are saved while the roaming probe keeps sending samples: a first draft when the route starts, then at most every 5 s while it changes (the 1.5 s debounce could be postponed for ever). A route saved or discarded is never brought back by a late write.
- **Active tests run on the Wi-Fi the technician approved**: the run is bound to that network and revalidated before any traffic; if it is gone or replaced nothing is sent (`network_changed`) and a new decision is asked. AP changes inside the same network are still recorded. Runs export the network they were really bound to (`networkId`).
- **Roaming**: each route stretch keeps the probe it used (method, target, alias, interval); a route resumed with another probe exports one log per probe (`stretch`) instead of mixing them. Drafts from before show the method as unknown. ICMP `avgRttMs` is the echo's `time=` (not the `ping` process duration, now `avgAttemptMs`); a `ping` that cannot run is `probe_error`, not a lost probe.
- **Report**: roaming events without any SSID are shown once under «SSID desconocido» (also on floors without active tests) and the floor total counts each event once.
- A connection opened after a phase deadline is closed at once instead of blocking until its read timeout (the rc.1 field build failed on this in CI).

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
- Version scheme: semantic versioning (`vX.Y.Z` tags, `-rc.N` test builds); versionCode `X·10⁶ + Y·10⁴ + Z·100 + slot` with slot = RC number or 99 for the stable release, so `4.0.0-rc.N` < `4.0.0` (4000099) < `4.0.1-rc.1`.
- Field workflow (`field-build.yml`): builds and verifies the tag's exact APK (package, version, source SHA, APK SHA-256, signer), runs the iPerf3 integration test against a real iperf3, builds the R8 variant and keeps development and field-channel APKs apart. It does not publish.
- Project copy (*Back up project*) and restore; analysis ZIPs open as *Import ESX only*.
- About: description, build identity and links to nimWifi and to WiProber.
- Autosave runs outside the survey screen, retries and warns when saving keeps failing.
- Active tests, roaming and template import moved out of the survey screen code.

### Fixed
- ICMP ping now binds to the Wi-Fi source address (binding to the interface name needs a privilege apps do not have).
- A slow or failing DNS no longer slows the roaming probe; the attempt is recorded as `resolution_failed`.

## 3.0.0-throughput.5

First nimWifi build: rename from WiProber, audit blocks 1–6 (measurement network, saved and cancellable runs, roaming time base, HAR and credential hardening, point reuse and per-SSID report, build identity), crash fix for the first active test, survey-guide grid.
