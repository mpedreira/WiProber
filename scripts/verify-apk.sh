#!/usr/bin/env bash
# Verifies the identity of an APK: package, versionName, versionCode, signature (and optionally
# the signer certificate) and prints what was verified as key=value lines (for $GITHUB_OUTPUT).
# Every tool failure fails the script, also when its output is captured (rc.3 · 1).
#
#   verify-apk.sh --apk FILE --package ID --version-name NAME --version-code CODE \
#                 [--expect-cert SHA256] [--source-sha SHA]
#   verify-apk.sh --parse-badging "package: name='…' versionCode='…' versionName='…' …"
#
# AAPT and APKSIGNER select the tools (default: $ANDROID_HOME/build-tools/36.0.0).
set -euo pipefail

BT="${ANDROID_HOME:-/opt/android-sdk}/build-tools/36.0.0"
AAPT="${AAPT:-$BT/aapt}"
APKSIGNER="${APKSIGNER:-$BT/apksigner}"

fail() { echo "::error::$*" >&2; exit 1; }

# Fields of the `package:` line of `aapt dump badging`. Anchored: `name=` is the one right
# after `package: `, never platformBuildVersionName/compileSdkVersionCodename.
parse_badging() {
    local line="$1" package code name
    [[ "$line" =~ ^package:\ name=\'([^\']+)\' ]] || fail "No package name in: $line"
    package="${BASH_REMATCH[1]}"
    [[ "$line" =~ \ versionCode=\'([^\']*)\' ]] || fail "No versionCode in: $line"
    code="${BASH_REMATCH[1]}"
    [[ "$line" =~ \ versionName=\'([^\']*)\' ]] || fail "No versionName in: $line"
    name="${BASH_REMATCH[1]}"
    printf 'package=%s\nversionCode=%s\nversionName=%s\n' "$package" "$code" "$name"
}

if [[ "${1:-}" == "--parse-badging" ]]; then
    parse_badging "${2:?badging line}"
    exit 0
fi

APK="" PACKAGE="" NAME="" CODE="" CERT_EXPECTED="" SOURCE_SHA=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk) APK="$2"; shift 2 ;;
        --package) PACKAGE="$2"; shift 2 ;;
        --version-name) NAME="$2"; shift 2 ;;
        --version-code) CODE="$2"; shift 2 ;;
        --expect-cert) CERT_EXPECTED="$2"; shift 2 ;;
        --source-sha) SOURCE_SHA="$2"; shift 2 ;;
        *) fail "Unknown argument $1" ;;
    esac
done
[[ -f "$APK" ]] || fail "APK not found: $APK"
[[ -n "$PACKAGE" && -n "$NAME" && -n "$CODE" ]] || fail "--package, --version-name and --version-code are required"

# Captured without a pipe: a failing aapt fails here (no `| head` hiding its status).
BADGING="$("$AAPT" dump badging "$APK")" || fail "aapt could not read $APK"
LINE="${BADGING%%$'\n'*}"
echo "$LINE" >&2
FIELDS="$(parse_badging "$LINE")"
ACTUAL_PACKAGE="$(sed -n 's/^package=//p' <<<"$FIELDS")"
ACTUAL_CODE="$(sed -n 's/^versionCode=//p' <<<"$FIELDS")"
ACTUAL_NAME="$(sed -n 's/^versionName=//p' <<<"$FIELDS")"
[[ "$ACTUAL_PACKAGE" == "$PACKAGE" ]] || fail "Package $ACTUAL_PACKAGE, expected $PACKAGE"
[[ "$ACTUAL_NAME" == "$NAME" ]] || fail "versionName $ACTUAL_NAME, expected $NAME"
[[ "$ACTUAL_CODE" == "$CODE" ]] || fail "versionCode $ACTUAL_CODE, expected $CODE"

SIGNER="$("$APKSIGNER" verify --verbose --print-certs "$APK")" || fail "apksigner: the signature of $APK does not verify"
echo "$SIGNER" >&2
CERT="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$SIGNER")"
[[ "$CERT" =~ ^[0-9a-f]{64}$ ]] || fail "No signer certificate SHA-256 in the apksigner output"
if [[ -n "$CERT_EXPECTED" && "$CERT" != "$CERT_EXPECTED" ]]; then
    fail "Signer $CERT, expected $CERT_EXPECTED"
fi
APK_SHA="$(sha256sum "$APK")" || fail "Cannot hash $APK"
APK_SHA="${APK_SHA%% *}"

printf 'package=%s\nversionName=%s\nversionCode=%s\napkSha256=%s\nsignerCertificateSha256=%s\n' \
    "$ACTUAL_PACKAGE" "$ACTUAL_NAME" "$ACTUAL_CODE" "$APK_SHA" "$CERT"
[[ -z "$SOURCE_SHA" ]] || printf 'sourceSha=%s\n' "$SOURCE_SHA"
