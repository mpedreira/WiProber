# WiProber

**WiProber** is an open-source Android application for conducting Wi-Fi site surveys. It allows users to load a floor plan, perform Wi-Fi scans using different modes (Stop-and-Go or Continuous), and export the collected data into the `.esx` format, compatible with Ekahau Pro™.

This project was born out of the need for a simple, mobile-first tool for network engineers and enthusiasts to perform quick on-site surveys without expensive, proprietary hardware.

Version **3.0.0** requires **Android 11 (API 30) or newer**. It adds a project hub, recent projects, multi-floor surveying, and ESX import that preserves original archive contents while adding new surveys.

> **About this fork.** [mpedreira/WiProber](https://github.com/mpedreira/WiProber) extends the original [htechno/WiProber](https://github.com/htechno/WiProber) with **active survey** features: per-point throughput tests (iPerf3 and Internet), network service tests (DNS, ping, TCP, HTTP), replayed HTTP scripts, a Continuous-mode roaming probe and a NetSpot-style survey guide. Results are exported next to the unchanged `.esx` in a ZIP, as documented in [docs/wiprober-performance.md](docs/wiprober-performance.md). Prebuilt APKs are on the [Releases](https://github.com/mpedreira/WiProber/releases) page.

## Screenshots

| Project hub | Multi-floor survey |
| :---: | :---: |
| [<img src="docs/screenshots/v3-project-hub.png" width="320" alt="WiProber v3 project hub with New project, Open ESX project, and a recent two-floor project">](docs/screenshots/v3-project-hub.png) | [<img src="docs/screenshots/v3-continuous-survey.png" width="320" alt="WiProber v3 survey screen with floor selector, survey points, a route, and Continuous mode controls">](docs/screenshots/v3-continuous-survey.png) |
| Create a project, import an ESX file, or resume recent work. | Switch floors and continue surveying with points, routes, notes, and scale calibration. |

Select a screenshot to view it at full resolution.

## Features

*   **Interactive Floor Plan:** Load any image as a floor plan, with support for zoom and pan.
*   **Local Project Hub:** Create a project, open an ESX file, or continue one of the most recently used local projects.
*   **Project Status and Management:** Recent projects expose explicit loading/error states and a confirmed local-delete action. The survey screen shows active-floor counts and complete-project export results.
*   **Multi-floor Projects:** Import every supported floor from an ESX archive, switch floors while surveying, and add new named floor plans.
*   **Two Survey Modes:**
    *   **🔴 Stop-and-Go:** Tap a point, wait for a scan, move to the next point. High precision.
    *   **👣 Continuous (New in v2.0):** Tap "Start", walk along a path, and tap to mark turns. The app scans continuously in the background. Faster data collection.
*   **Advanced Data Collection:** Gathers comprehensive data for each network, including SSID, BSSID, RSSI, frequency, security standard (802.11ax/ac/n...), and raw Information Elements (IEs).
*   **Dynamic Adapter Info:** Automatically detects your device model (e.g., "Samsung S23") and injects this metadata into the project file.
*   **Note-Taking:** Add text and photo annotations directly onto the map to document access point locations, obstacles, or other points of interest.
*   **Scale Calibration:** Set the physical scale of the map for accurate-to-reality measurements.
*   **Ekahau-compatible `.esx` Export:** New projects are generated from scratch with the required JSON files, binary Wi-Fi tracks, floor plans, and attached note images. Imported projects keep the complete source archive and append WiProber surveys without regenerating the original measurements.
*   **`.esx` Import:** Imports floor plans, route geometry, scale, and map-positioned picture notes for display. Original Wi-Fi tracks, spectrum data, and unknown payloads remain opaque and are preserved for the next export.

Added in this fork:

*   **Per-point throughput tests:** after each Stop-and-Go scan, download, upload and latency (min/avg/jitter) against your own **iPerf3** server and an **Internet** speed test (Cloudflare by default, any compatible URL such as LibreSpeed). All test traffic is bound to the Wi-Fi network, never mobile data. A progress dialog shows each phase.
*   **Connected-link details:** for each test, the associated AP (SSID, BSSID at start and end to detect roaming, RSSI), transmit/receive link rates, channel, channel width, Wi-Fi standard, the AP's *BSS Load* (clients and channel utilisation) and the phone model.
*   **Network service tests:** configurable `dns` (query sent straight to the Wi-Fi's DNS server), `ping` (ICMP, TCP fallback), `tcp` (port) and `http` (DNS/TCP/TLS/first-byte/download breakdown) tests at every point, like the AutoTest of commercial survey tools. `${gateway}` and `${dns}` come from the network itself.
*   **HTTP scripts:** replay a recorded user flow (login → token → query) at every point, with variables, cookies and checks, each step timed. Import it as JSON or straight from a browser `.har` recording. Read-only unless a step explicitly allows writes.
*   **Roaming probe (Continuous mode):** every 200 ms the associated AP is read and the target is probed; roams (with time without connectivity) and outages are placed on the route you mark on the map.
*   **Survey guide:** once the scale is calibrated, a grid suggests the next Stop-and-Go point (tap to measure there, long-press to move, *Skip* unreachable cells) and measured areas are shaded with a signal-coloured gradient.
*   **Imported configuration:** servers, credentials, tests and scripts are never in the code. They are imported on the phone from a `wiprober-config.json` ([example](docs/wiprober-config.example.json)), kept in app-private storage and excluded from backups. Imports are previewed before activation, credentials are stored apart from definitions and stripped from HAR recordings ([details](docs/wiprober-config.md)).
*   **Performance export:** projects with results export a ZIP with the untouched `.esx` plus `wiprober-performance.json`, joinable with the ESX by survey ID or by floor-plan position.

## Limitations & System Requirements

This is a non-commercial, open-source project. Please be aware of the following system limitations:

*   **Wi-Fi Scan Throttling:**
    *   **The Issue:** Android limits third-party apps to **4 scans per 2 minutes**.
    *   **Stop-and-Go Mode:** WiProber enforces a countdown to prevent errors if you scan too fast.
    *   **Continuous Mode:** **REQUIRED:** You MUST disable "Wi-Fi scan throttling" in Developer Options. WiProber will check this setting and warn you if it's enabled.
*   **Location Services (GPS):** Android requires Location Services to be enabled to see Wi-Fi networks. The app will prompt you to turn it on.
*   **No "My Networks" Detection:** The app currently does not automatically identify or flag your own networks ("My Networks"). This must be done manually within Ekahau Pro after import.
*   **No AP Merging:** WiProber creates a new, separate access point (`accessPoints.json`) for every unique BSSID found. It does not attempt to group multiple radios (e.g., 2.4-GHz and 5-GHz radios) under a single physical access point device. This grouping should be performed manually in Ekahau.
*   **ESX Editing Scope:** WiProber does not display or edit imported RSSI/frequency/spectrum measurements, walls, requirements, or AP grouping. Schema-v3 projects preserve those source entries unchanged and add new WiProber survey data alongside them. New projects still contain the supported WiProber-generated subset. Projects imported by older schema-v2 builds must be re-imported from their original ESX to gain source-preservation guarantees.
*   **No noise floor / SNR:** Android does not expose the noise floor, so neither the scans nor the throughput tests report noise or SNR.
*   **Network access (fork):** this fork requests the `INTERNET` permission for the optional tests. Nothing is sent unless you enable a test or import a configuration, and then only to the servers you configure (and `speed.cloudflare.com` for the default Internet test). Trusts user/MDM-installed CAs so corporate HTTPS servers can be tested.
*   **ICMP ping:** Android apps cannot send ICMP directly; the system `ping` bound to the Wi-Fi interface is used and, where a device does not allow it, a TCP ping (reported as `method: "tcp"`).
*   **Association and DHCP timing:** apps cannot disconnect and reconnect Wi-Fi on Android 10+, so association/authentication/DHCP times are not measured.
*   **Roaming probe and scans:** while Continuous mode scans, the radio leaves the channel; outages that overlap a scan are flagged (`duringScan`).
*   **Android Version:** WiProber v3 requires Android 11 (API 30) or newer. Android 10 and older devices cannot install or update to v3. Public beacon Information Elements and `wifiStandard` are part of the v3 scan baseline, although the exact IE set still depends on the device chipset and driver.

## Getting Started

### Prerequisites
*   Android Studio (latest version recommended)
*   An Android device with Android 11 (API 30) or higher.

### Building and Running
1.  Clone the repository: `git clone https://github.com/mpedreira/WiProber.git` (this fork) or `https://github.com/htechno/WiProber.git` (original).
2.  Open the project in Android Studio.
3.  Let Gradle sync and download all dependencies.
4.  Build and run the application on your device.

### Installing a prebuilt APK (fork)

Each fork release on the [Releases](https://github.com/mpedreira/WiProber/releases) page attaches a debug-signed APK (`com.example.wiprober.debug`) with its SHA-256 in the notes. Open the release on the phone, download the APK and install it. It installs next to the F-Droid build; export your projects before uninstalling any build.

### Building an APK on GitHub

Open **Actions → Build APK → Run workflow**, select the branch, and start the build. Pull requests targeting `main` also run this workflow; ordinary pushes and tags do not automatically build or publish a release.

The workflow runs JVM tests and Android lint, builds the app and instrumentation-test APKs, and uploads the app as the **wiprober-debug-apk** artifact. Download that artifact from the successful run and extract **WiProber-debug.apk**. It includes the source commit, APK metadata, and a SHA-256 checksum. Artifacts are retained for 30 days; device tests and the external customer ESX fixture are not run on GitHub.

This is a debug-signed APK (`com.example.wiprober.debug`), not a production-signed release. GitHub runners may generate different debug keys between builds, so an existing debug installation may not accept an update. Export your projects before uninstalling: uninstalling removes app-owned projects and their source archives.

## How to Use

### Projects
1.  On the start screen, tap **"New project"** and select the first floor-plan image, or tap **"Open ESX project"**.
2.  A multi-floor ESX is imported as one project and initially opens on its first floor.
3.  Use the anchored **Floor** dropdown on the survey screen to switch floors. Its last item, **Add floor…**, creates another named floor.
4.  Projects are copied into app-owned storage, autosaved, and appear under **Recent projects** for later use. For an imported project, the complete original ESX is retained as an immutable source archive.
5.  Use the three-dot action on a recent project to delete its complete local workspace. This is permanent and includes the retained source ESX, so export a copy first if needed.

### Stop-and-Go Mode (Default)
1.  Open or create a project.
2.  Tap on the map to scan. A **Red Dot** will appear.
3.  Wait for the scan to finish. Repeat.

### Continuous Mode
1.  **Disable Throttling:** Ensure "Wi-Fi scan throttling" is OFF in Developer Options.
2.  Select **Continuous** in the bottom survey dock.
3.  Tap **Start route**, then select the start point on the map.
4.  Start walking. The app scans in the background.
5.  Tap again to mark a **Turn** (waypoint). A Magenta line follows your path.
6.  Tap the red **Stop route** action to finish the path. The line turns green.

### Survey guide (fork)
1.  Calibrate the floor scale first; without it the guide is hidden and a hint asks for calibration.
2.  After the first Stop-and-Go point, a blue ring with a cross marks the **next suggested point**: the nearest unmeasured grid cell (default 10 m), preferring the current walking direction.
3.  Tap the ring to measure exactly there, or tap anywhere else to measure there instead. **Long-press** the map to move the suggestion. **Skip** marks a cell as "do not measure" (outside the building, racks); Undo restores it.
4.  Measured areas are shaded around each point and route scan (default 5 m) from red (−85 dBm) through yellow to green (−55 dBm). Configure spacing, radius and an optional SSID in the survey menu → **Survey guide**.

### Throughput tests (fork)
1.  In the survey menu → **Throughput tests**, enable the iPerf3 test (server, port, target name shown in the export) and/or the Internet test (download/upload URLs, Cloudflare by default). Durations are seconds per direction (default 5).
2.  Measure Stop-and-Go points as usual: after each scan a dialog shows every phase with a countdown and the values already measured. An unreachable server is skipped quickly.

### Imported configuration: network tests, scripts and roaming (fork)
1.  Prepare a `wiprober-config.json` (start from the [example](docs/wiprober-config.example.json)) or record a browser `.har` (Developer tools → Network → *Save all as HAR*; only GET requests of documents/XHR/fetch are kept).
2.  In the survey menu → **Configuration…** → **Import file…**, select it. The dialog lists the imported tests, scripts and roaming probe; **Remove tests & scripts** clears them.
3.  Network tests and scripts run at every Stop-and-Go point after the throughput tests. The roaming probe runs during Continuous tracks.
4.  Use a read-only test account in scripts: credentials in the file are stored in clear text on the phone.

### Export
Tap **"Export ESX"** to generate one `.esx` containing every floor currently stored in the local project. In this fork, a project with performance results (throughput, network tests, scripts or roaming) is exported as a **ZIP** with that same `.esx` plus `wiprober-performance.json` ([specification](docs/wiprober-performance.md)). A new project is generated from scratch; an imported project preserves all original archive entries and overlays only WiProber additions or supported edits. Export runs behind a labelled progress state and reports the number of floors, points, routes, and notes written.

### Import
On the start screen, tap **"Open ESX project"** and select a project. All supported floor plans are imported together; use the selector in the survey screen to move between them.

## Post-Processing in Ekahau Pro

After importing the `.esx` file into Ekahau Pro, you will need to perform a few manual steps to finalize your project:

1.  **Set "My Networks":** Add Stars in the Network menu.
2.  **Group Radios:** To group multiple radios (BSSIDs) under a single physical AP, navigate to **Project -> Measurement Grouping Options** and configure the desired grouping logic.
3.  **Auto-Place Access Points:** To estimate the location of the access points on the map, use the **Auto-Placing** feature.

## Release optimization checks

Release APKs use R8 and resource shrinking. `@JsonModel` marks the reflection-based
Gson models whose field names and generic types must remain compatible with saved
projects and ESX catalogues. Apply it to any new model serialized through Gson.

Run the minified storage and export tests on a connected device in a separate
`com.example.wiprober.r8validation` package (does not replace the installed app):

```sh
./gradlew connectedR8ValidationAndroidTest -Pr8Validation \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.wiprober.ProjectRepositoryInstrumentedTest,com.example.wiprober.EsxExportServiceInstrumentedTest
```

This opt-in variant inherits release optimization settings and uses the debug
signing key. Its additional rules retain shared test-runtime dependencies and the public
methods/constructors called from the separate instrumentation APK;
project/ESX model rules are identical to release. Production releases do not use
these instrumentation rules. Run normal debug checks without `-Pr8Validation`.

## Contributing

**WiProber** is a community-driven project, and your contributions are welcome!

*   **Have an idea or a bug fix?** The best way to get it into the project is to implement it yourself and submit a PR.
*   **Want to request a feature?** You are welcome to open an issue to discuss it.

I'm a wireless engineer, just like many of you, not a full-time developer. Let's build this tool together!

## License
This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details. The fork's additions are released under the same license.

---
*Disclaimer: Ekahau Pro™ is a trademark of Ekahau. This project is not affiliated with, endorsed by, or sponsored by Ekahau.*

---
## Third-Party Libraries and Assets

This project uses some third-party libraries and assets. Here are their licenses:

*   **Material Design Icons:** The icons used in this app are provided by Google ([Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0)).
*   **PhotoView by Chris Banes:** Licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
*   **Gson by Google:** Licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
*   **Coil (Coil-kt):** Licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
*   **AndroidX Libraries:** Licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

This project itself is licensed under the MIT license.
