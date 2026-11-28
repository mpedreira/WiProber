# Releases: version, identity and signing

## Version scheme

From **4.0.0** versions follow semantic versioning and releases are built from tags `vX.Y.Z` (test builds `vX.Y.Z-rc.N`). The 3.0.0 builds used `v3.0.0-throughput.N`.

| | Rule | Example |
|---|---|---|
| `versionName` | tag without `v`, plus `-debug` for the debug build type | `4.0.0-debug` |
| `versionCode` | `X·1 000 000 + Y·10 000 + Z·100 + slot` — slot = `N` for `-rc.N` (1–98), **99** for the stable release; `-throughput.N` keeps its published code | see below |

| Version | versionCode |
|---|---|
| `3.0.0-throughput.5` (published) | 3000005 |
| `4.0.0-rc.1` | 4000001 |
| `4.0.0-rc.2` | 4000002 |
| `4.0.0` | 4000099 |
| `4.0.1-rc.1` | 4000101 |

Every RC sorts below its stable release, and the stable release below the next version's RCs, so testers on an RC get the stable build as an update. (The previous rule gave `4.0.0` the code 4000000, *below* `4.0.0-rc.1`: Android would have refused that update.) No 4.x build had been published with the old rule. Y and Z stay below 100 and X at most 2099 (Android's limit is 2 100 000 000). The build checks the sequence `3.0.0-throughput.5 < 4.0.0-rc.1 < 4.0.0-rc.2 < 4.0.0 < 4.0.1-rc.1 < 4.0.1 < 4.1.0-rc.1` every time it is configured and refuses an out-of-range version. `./gradlew :app:printVersionInfo -PappVersionName=…` prints what a tag produces. `-PappVersionName`/`-PappVersionCode` override the rule; local builds without them are `4.0.0-dev` with `versionCode 1`. Changes per version are in [CHANGELOG.md](../CHANGELOG.md).

## Identifying an APK

`.github/workflows/field-build.yml` runs on every `v*` tag (or by hand with a tag). It checks out that tag and, from the same source:

1. runs the unit tests with a real `iperf3` (`IPERF3_BIN`) and **fails if the iPerf3 integration tests were skipped**, lint, the debug, release and instrumentation-test builds;
2. builds the R8 validation variant (persistence keep rules);
3. checks the APK against the tag with `scripts/verify-apk.sh` (self-tested first by `scripts/test-verify-apk.sh`, taken from the workflow's own commit so older tags can be verified too; any `aapt`/`apksigner` failure fails the step): package `com.mpedreira.nimwifi.debug`, `versionName`, `versionCode` (from `printVersionInfo`), and records the APK SHA-256 and the signer certificate SHA-256;
4. uploads one of two artifacts, never both:
   - `nimwifi-<version>-field` when it was signed with the field key — the only kind that can update field installations;
   - `nimwifi-<version>-development-only` when the field key is not configured (the runner's throwaway debug key) — for testing, never for the field.

Each artifact has `nimWifi-<version>-debug.apk`, `SHA256SUMS`, `output-metadata.json` and `build-info.json` (`tag`, `sourceSha`, `package`, `versionName`, `versionCode`, `apkSha256`, `signerCertificateSha256`, `channel`, `fieldKey`, `instrumentation`, `workflowRun`). The run summary says that the instrumentation tests were **built, not executed**: CI has no device, and compiling them is not running them.

**It never publishes a GitHub release.** Publishing (and attaching that exact APK, with its `build-info.json`) stays a manual decision. `build-apk.yml` (pull requests) keeps building a development APK only.

## Signing identity and package

Android only updates an installed app with an APK of the **same package** signed by the **same certificate**, and a higher `versionCode`.

| Builds | Package | Signed with | Certificate SHA-256 |
|---|---|---|---|
| `v3.0.0-throughput.1` … `.4` | `com.example.wiprober.debug` | The Android debug key of the build machine (`CN=Android Debug`) | `3f527115f1fdb7d0ea5f70e16ef9700f31e8651e007dfc2468180863613e6304` |
| `v3.0.0-throughput.5` | `com.mpedreira.nimwifi.debug` | The same debug key | `3f527115…6304` |
| Field workflow with `FIELD_KEYSTORE_B64` | `com.mpedreira.nimwifi.debug` | The field key | see `build-info.json` |
| Field workflow without it | `com.mpedreira.nimwifi.debug` | The runner's throwaway debug key | changes every run (development only) |

The field channel keeps the package `com.mpedreira.nimwifi.debug` (that of `.5`) and one signing key.

**Recommended field key: the key that signed `.1`–`.5`.** It is the debug keystore of the machine that built them (`~/.android/debug.keystore`, alias `androiddebugkey`, certificate `3f527115…6304`). Back it up outside the repository, convert it to PKCS12 if needed and store it as `FIELD_KEYSTORE_B64` (`base64 -w0`), `FIELD_KEYSTORE_PASSWORD`, `FIELD_KEY_ALIAS`, `FIELD_KEY_PASSWORD`. If that keystore is lost, create a dedicated key instead (`keytool -genkeypair -alias nimwifi-field -keyalg RSA -keysize 3072 -validity 10000 -keystore nimwifi-field.p12 -storetype PKCS12`) and accept the uninstall described below.

## Release channel (package `com.mpedreira.nimwifi`)

From 4.1.1 the field workflow can also build the **release** APK: package `com.mpedreira.nimwifi` (no `.debug` suffix), `versionName` without `-debug`, R8 enabled, signed with its **own key**. It is another app for Android: it never updates a `.debug` installation, and both can be installed side by side.

| Builds | Package | Signed with |
|---|---|---|
| Field workflow with `RELEASE_KEYSTORE_B64` | `com.mpedreira.nimwifi` | The release key (see `build-info.json`; pinned with `RELEASE_CERT_SHA256`) |
| Without it | — | Not signed and not uploaded (`app-release-unsigned.apk`); never a debug or field key |

**Key.** Create it once, outside the repository, and keep a backup of the file and its passwords: without it no update of the release app can ever be published.

```
keytool -genkeypair -alias nimwifi-release -keyalg RSA -keysize 3072 -validity 10000 \
  -keystore nimwifi-release.p12 -storetype PKCS12
base64 -w0 nimwifi-release.p12        # → RELEASE_KEYSTORE_B64
keytool -list -v -keystore nimwifi-release.p12 | grep SHA256   # → RELEASE_CERT_SHA256 (lowercase, no colons)
```

**Repository settings** (*Settings → Secrets and variables → Actions*, or `https://github.com/<owner>/<repo>/settings/secrets/actions`; owner or admin only, not in the GitHub mobile app). Secrets: `RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Variable (optional, not secret): `RELEASE_CERT_SHA256` — the build then fails if the APK is signed by any other certificate. With the GitHub CLI: `gh secret set RELEASE_KEYSTORE_B64 < nimwifi-release.b64`, `gh variable set RELEASE_CERT_SHA256 --body <sha256>`.

**What the workflow checks.** `scripts/verify-apk.sh` on `app-release.apk`: package `com.mpedreira.nimwifi`, `versionName` = the tag, `versionCode` from `printVersionInfo`, valid signature, the pinned certificate when set, and never the debug key `3f527115…6304`. The artifact `nimwifi-<version>-release` has `nimWifi-<version>.apk`, `SHA256SUMS`, `output-metadata.json` and `build-info.json` (`channel: release`). The debug artifact is still built as before.

**Moving projects from the debug app.** In the `.debug` app, *Back up project* for each project; in the release app, restore each copy (same IDs, everything included). Check them, then uninstall the debug app. Test the release APK on a device before using it in the field: it is the R8 build.

### Transition from `3.0.0-throughput.5`

- **Same package.** With the recommended key, 4.x installs **over** `.5` and keeps its projects: the update reopens them (saved projects stay readable, schema migrations included).
- With a different field key Android refuses the update. Before uninstalling `.5`, export each project from it (*Export* → ESX + report ZIP). `.5` has no project copy, so on 4.x that ZIP is opened as **Import ESX only**: floors, scale, scans, routes and notes come back through the ESX; active test runs, planned points and skipped cells do not. From 4.x on, use *Back up project* ([project copy](project-backup.md)) before any reinstall: it restores everything with the same IDs.

### Transition from `3.0.0-throughput.4` (WiProber)

- **Different package** (`com.example.wiprober.debug`): 4.x never updates it; both apps install side by side whatever the key.
- Move projects by exporting them from WiProber (ESX or ZIP) and opening the file in nimWifi (**Import ESX only**, as above). Once the projects are checked in nimWifi, uninstall WiProber.

Locally, the same variables (`FIELD_KEYSTORE_FILE` pointing at the keystore, plus the three passwords/alias) sign `assembleDebug` with the field key.

## Verification

```sh
IPERF3_BIN=/usr/bin/iperf3 ./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew assembleRelease assembleDebugAndroidTest
./gradlew -Pr8Validation assembleR8Validation assembleR8ValidationAndroidTest
```

Persisted models are `@JsonModel` classes kept by `-keep @com.mpedreira.nimwifi.JsonModel class * { *; }`; after changing them, install the R8 validation build on a device and reopen a project saved by the previous version.

Needs a physical Android device (not runnable in CI or a container): Wi-Fi network selection and binding, association callbacks on Android 11 and 12+, scans, ICMP ping on the Wi-Fi interface, cancellation of real transfers, rotation and process death during a run, the cleartext policy and user/MDM CAs, the instrumentation tests. Needs real servers or files: an iPerf3 server on the corporate network, an Ekahau AI Pro installation to open the exported ESX, a NetSpot export sample.
