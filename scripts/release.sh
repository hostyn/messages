#!/usr/bin/env bash
# Builds the release APK and signs it with the release key.
# apksigner asks for the keystore password interactively, so it is never stored anywhere.
#
# Usage: scripts/release.sh [path/to/keystore.p12]
# Output: dist/messages-<version>.apk
set -euo pipefail

cd "$(dirname "$0")/.."

KEYSTORE="${1:-$HOME/keystores/phone-release.p12}"
# Same SDK Gradle uses: local.properties first, then the usual environment variables.
SDK="$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null || true)"
for candidate in "$SDK" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
    if [ -n "$candidate" ] && [ -d "$candidate/build-tools" ]; then SDK="$candidate"; break; fi
done
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[ -n "$BUILD_TOOLS" ] || { echo "Android SDK build-tools not found" >&2; exit 1; }
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
OUT="dist/messages-$VERSION.apk"

[ -f "$KEYSTORE" ] || { echo "Keystore not found: $KEYSTORE" >&2; exit 1; }

./gradlew assembleRelease
mkdir -p dist
"$BUILD_TOOLS/zipalign" -f -p 4 "$UNSIGNED" "$OUT.aligned"
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias phone --out "$OUT" "$OUT.aligned"
rm -f "$OUT.aligned" "$OUT.idsig"
"$BUILD_TOOLS/apksigner" verify --print-certs "$OUT" | grep -E "certificate (DN|SHA-256)"
echo "Signed: $OUT"
