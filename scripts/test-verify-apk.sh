#!/usr/bin/env bash
# Regression tests of verify-apk.sh with stand-in aapt/apksigner (no Android SDK needed).
set -euo pipefail
cd "$(dirname "$0")"
VERIFY=./verify-apk.sh
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# The line that made the v4.0.0-rc.2 field build fail with "Package 16".
REAL="package: name='com.mpedreira.nimwifi.debug' versionCode='4000002' versionName='4.0.0-rc.2-debug' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'"
CERT=3f527115f1fdb7d0ea5f70e16ef9700f31e8651e007dfc2468180863613e6304

cat > "$WORK/aapt" <<'TOOL'
#!/usr/bin/env bash
[[ -n "${FAKE_AAPT_FAIL:-}" ]] && { echo "ERROR: dump failed" >&2; exit 1; }
printf '%s\nsdkVersion:%s\n' "$FAKE_BADGING" "'30'"
TOOL
cat > "$WORK/apksigner" <<'TOOL'
#!/usr/bin/env bash
[[ -n "${FAKE_SIGNATURE_INVALID:-}" ]] && { echo "DOES NOT VERIFY"; echo "ERROR: APK Signature Scheme v2 signer #1: digest mismatch" >&2; exit 1; }
echo "Verifies"
echo "Signer #1 certificate SHA-256 digest: $FAKE_CERT"
TOOL
chmod +x "$WORK/aapt" "$WORK/apksigner"
echo apk > "$WORK/app.apk"
export AAPT="$WORK/aapt" APKSIGNER="$WORK/apksigner" FAKE_BADGING="$REAL" FAKE_CERT="$CERT"

pass=0
expect_ok() { local what="$1"; shift; if out="$("$@" 2>/dev/null)"; then pass=$((pass + 1)); echo "ok   $what"; else echo "FAIL $what (rejected)"; exit 1; fi; }
expect_fail() { local what="$1"; shift; if "$@" >/dev/null 2>&1; then echo "FAIL $what (accepted)"; exit 1; else pass=$((pass + 1)); echo "ok   $what"; fi; }
verify() { "$VERIFY" --apk "$WORK/app.apk" --package com.mpedreira.nimwifi.debug --version-name 4.0.0-rc.2-debug --version-code 4000002 --expect-cert "$CERT" "$@"; }

parsed="$("$VERIFY" --parse-badging "$REAL")"
[[ "$(sed -n 's/^package=//p' <<<"$parsed")" == com.mpedreira.nimwifi.debug ]] || { echo "FAIL parse: $parsed"; exit 1; }
[[ "$(sed -n 's/^versionName=//p' <<<"$parsed")" == 4.0.0-rc.2-debug && "$(sed -n 's/^versionCode=//p' <<<"$parsed")" == 4000002 ]] || { echo "FAIL parse: $parsed"; exit 1; }
pass=$((pass + 1)); echo "ok   the rc.2 badging line gives com.mpedreira.nimwifi.debug"

expect_ok "the rc.2 APK identity is accepted" verify
[[ "$(sed -n 's/^apkSha256=//p' <<<"$out")" == "$(sha256sum "$WORK/app.apk" | cut -d' ' -f1)" ]] || { echo "FAIL hash"; exit 1; }
FAKE_BADGING="${REAL/com.mpedreira.nimwifi.debug/com.example.other}" expect_fail "another package is rejected" verify
FAKE_BADGING="${REAL/4.0.0-rc.2-debug/4.0.0-rc.1-debug}" expect_fail "a wrong versionName is rejected" verify
FAKE_BADGING="${REAL/4000002/4000001}" expect_fail "a wrong versionCode is rejected" verify
FAKE_SIGNATURE_INVALID=1 expect_fail "an invalid signature is rejected" verify
FAKE_CERT=66b1a85572adf5c0a1eebb356ee827bcdd0a75d8c967e9065d22ce3c36b49056 expect_fail "another signer is rejected" verify
FAKE_AAPT_FAIL=1 expect_fail "a failing aapt fails the check" verify
FAKE_BADGING="sdkVersion:'30'" expect_fail "output without a package line fails" verify
echo "$pass checks passed"
