# Field builds: version, identity and signing

## Version scheme

The field channel is the **debug** build type (`com.mpedreira.nimwifi.debug`), built from tags `vX.Y.Z-throughput.N`:

| | Rule | Example (`v3.0.0-throughput.5`) |
|---|---|---|
| `versionName` | tag without `v`, plus `-debug` | `3.0.0-throughput.5-debug` |
| `versionCode` | `X·1 000 000 + Y·10 000 + Z·100 + N` (Y, Z, N < 100) | `3000005` |

Releases `.1`–`.4` all had `versionCode 5` and `versionName 3.0.0-debug`, so they cannot be told apart from the phone; every later field build is higher than 5 and increases with each tag. `-PappVersionName`/`-PappVersionCode` override the rule; local builds without them are `3.0.0-dev-debug` with `versionCode 1`, so a field build always installs over them.

*About* shows the visible version, the `versionCode`, the source commit (`BuildConfig.GIT_SHA`, first 12 characters) and the channel (`field`, `ci` or `local`).

## Identifying an APK

`.github/workflows/field-build.yml` runs on every `v*-throughput.*` tag (or by hand with a tag). It runs the unit tests (including the iPerf3 protocol test against a real `iperf3`), lint, the debug/release/instrumentation builds and the R8 validation build, then checks that the APK's `versionName`/`versionCode` match the tag and uploads, as a workflow artifact:

- `nimWifi-<version>-debug.apk`, `SHA256SUMS`, `output-metadata.json`
- `build-info.json`: `tag`, `sourceSha`, `versionName`, `versionCode`, `apkSha256`, `signerCertificateSha256`, `fieldKey`, `workflowRun`

**It never publishes a GitHub release.** Publishing (and attaching that exact APK, with its `build-info.json`) stays a manual decision.

## Signing identity

Android only updates an installed app with an APK signed by the same certificate.

| Builds | Signed with | Certificate SHA-256 |
|---|---|---|
| `v3.0.0-throughput.1` … `.4` | The Android debug key of the build machine (`CN=Android Debug`) | `3f527115f1fdb7d0ea5f70e16ef9700f31e8651e007dfc2468180863613e6304` |
| Field workflow with `FIELD_KEYSTORE_B64` | The dedicated field key | see `build-info.json` |
| Field workflow without it | The runner's throwaway debug key (warning in the run) | changes every run |

A debug key belongs to one machine and is lost with it, so it is not a usable identity for a field channel. Recommended:

1. Create one field key and keep a backup outside the repository:
   `keytool -genkeypair -alias wiprober-field -keyalg RSA -keysize 3072 -validity 10000 -keystore wiprober-field.p12 -storetype PKCS12`
2. Add the repository secrets `FIELD_KEYSTORE_B64` (`base64 -w0 wiprober-field.p12`), `FIELD_KEYSTORE_PASSWORD`, `FIELD_KEY_ALIAS`, `FIELD_KEY_PASSWORD`.
3. Phones with `.1`–`.4` installed must uninstall once (export their projects first) before installing the first build signed with the field key, unless that build is signed with the original debug key above.

Locally, the same variables (`FIELD_KEYSTORE_FILE` pointing at the keystore, plus the three passwords/alias) sign `assembleDebug` with the field key.

## Verification

```sh
IPERF3_BIN=/usr/bin/iperf3 ./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew assembleRelease assembleDebugAndroidTest
./gradlew -Pr8Validation assembleR8Validation assembleR8ValidationAndroidTest
```

Persisted models are `@JsonModel` classes kept by `-keep @com.mpedreira.nimwifi.JsonModel class * { *; }`; after changing them, install the R8 validation build on a device and reopen a project saved by the previous version.

Needs a physical Android device (not runnable in CI or a container): Wi-Fi network selection and binding, association callbacks on Android 11 and 12+, scans, ICMP ping on the Wi-Fi interface, cancellation of real transfers, rotation and process death during a run, the cleartext policy and user/MDM CAs, the instrumentation tests. Needs real servers or files: an iPerf3 server on the corporate network, an Ekahau AI Pro installation to open the exported ESX, a NetSpot export sample.
