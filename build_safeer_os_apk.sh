#!/usr/bin/env bash
# ==============================================================================
# SAFEER OS — televizijska lupina na Safeer Linku (modul safeer-os/, Gradle)
# Podpisan APK: safeer-os/build/signed/safeer-os-release.apk (isti kljuc kot Safeer Browser TV -
# to je pogoj, da Safeer OS od brskalnika dobi zeton Safeer Linka brez kode).
# Predpogoj: build_tv_apk.sh je bil vsaj enkrat pognan (SDK v .android-sdk, wrapper).
# ==============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
SDK_DIR="${ANDROID_SDK_ROOT:-$DIR/.android-sdk}"

if [[ ! -f "$SDK_DIR/platforms/android-34/android.jar" ]]; then
    echo "Android SDK manjka: najprej pozeni build_tv_apk.sh" >&2
    exit 1
fi
cat > "$DIR/local.properties" <<'PROP'
sdk.dir=.android-sdk
PROP
export ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_USER_HOME="$DIR/.android"
mkdir -p "$DIR/.android"

echo "☕ Gradle :safeer-os:assembleRelease ..."
cd "$DIR"
./gradlew --no-daemon :safeer-os:assembleRelease

UNSIGNED="$(find "$DIR/safeer-os/build/outputs/apk" -name '*.apk' | head -n 1)"
[[ -n "$UNSIGNED" && -f "$UNSIGNED" ]] || { echo "Gradle ni naredil APK za Safeer OS." >&2; exit 1; }

KEYSTORE_DIR="$DIR/keystore"
RELEASE_KEYSTORE="${RELEASE_KEYSTORE:-$KEYSTORE_DIR/safeer-tv-release.jks}"
RELEASE_KEY_ALIAS="${RELEASE_KEY_ALIAS:-safeer-tv}"
if [ -z "${RELEASE_KEY_PASS:-}" ] && [ -f "$KEYSTORE_DIR/.release_pass" ]; then
    RELEASE_KEY_PASS="$(cat "$KEYSTORE_DIR/.release_pass")"
fi
if [ -z "${RELEASE_STORE_PASS:-}" ] && [ -f "$KEYSTORE_DIR/.store_pass" ]; then
    RELEASE_STORE_PASS="$(cat "$KEYSTORE_DIR/.store_pass")"
fi
RELEASE_STORE_PASS="${RELEASE_STORE_PASS:-${RELEASE_KEY_PASS:-}}"
[ -n "${RELEASE_KEY_PASS:-}" ] || { echo "❌ Geslo kljuca (RELEASE_KEY_PASS ali keystore/.release_pass) ni nastavljeno." >&2; exit 1; }
[ -f "$RELEASE_KEYSTORE" ] || { echo "❌ Kljuc televizorja $RELEASE_KEYSTORE ne obstaja (naredi ga build_tv_apk.sh)." >&2; exit 1; }

OUT="$DIR/safeer-os/build/signed"
rm -rf "$OUT"; mkdir -p "$OUT"
java -jar "$TOOLS_DIR/uber-apk-signer.jar" \
    --apks "$UNSIGNED" --out "$OUT" \
    --ks "$RELEASE_KEYSTORE" --ksAlias "$RELEASE_KEY_ALIAS" \
    --ksPass "$RELEASE_STORE_PASS" --ksKeyPass "$RELEASE_KEY_PASS" --allowResign

SIGNED="$(find "$OUT" -name '*.apk' | head -n 1)"
[[ -n "$SIGNED" ]] || { echo "Podpisani APK manjka." >&2; exit 1; }
mv "$SIGNED" "$OUT/safeer-os-release.apk"
echo "=========================================================="
echo "ZGRAJEN SIGNED SAFEER OS APK: $OUT/safeer-os-release.apk"
echo "=========================================================="
ls -lh "$OUT/safeer-os-release.apk"
