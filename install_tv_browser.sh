#!/usr/bin/env bash
# One-click download and install of Safeer TV Browser (Android TV, over ADB).
set -euo pipefail

REPO="safeerOS/Android-tv"
TV="${1:-}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
TEMP_APK="$TMP_DIR/safeer-browser-tv.apk"
TEMP_SHA="$TMP_DIR/SHA256SUMS"

echo "=========================================================="
echo "Safeer TV Browser - download and install"
echo "=========================================================="

echo "Looking up the latest release..."
API_RESP="$(curl -fsSL --proto '=https' --proto-redir '=https' "https://api.github.com/repos/$REPO/releases/latest")"

APK_URL="$(echo "$API_RESP" | grep -o 'https://[^"]*/safeer-browser-tv-[^"]*\.apk' | head -n 1)"
SHA_URL="$(echo "$API_RESP" | grep -o 'https://[^"]*/SHA256SUMS' | head -n 1)"

if [[ -z "$APK_URL" ]]; then
    echo "Could not find an APK in the latest release. Download it by hand:"
    echo "  https://github.com/$REPO/releases/latest"
    exit 1
fi

echo "Downloading $APK_URL"
curl -fL --retry 3 --proto '=https' --proto-redir '=https' -o "$TEMP_APK" "$APK_URL"

if [[ ! -s "$TEMP_APK" ]]; then
    echo "Download failed."
    exit 1
fi

if [[ -z "$SHA_URL" ]]; then
    echo "Could not find SHA256SUMS in the latest release. Prekinjam namestitev."
    exit 1
fi

echo "Downloading SHA256SUMS"
curl -fL --retry 3 --proto '=https' --proto-redir '=https' -o "$TEMP_SHA" "$SHA_URL"

if [[ ! -s "$TEMP_SHA" ]]; then
    echo "SHA256SUMS download failed."
    exit 1
fi

APK_NAME="${APK_URL##*/}"
PRICAKOVANO="$(awk -v ime="$APK_NAME" '$2 == ime || $2 == "*" ime { print $1; exit }' "$TEMP_SHA")"

if [[ -z "$PRICAKOVANO" ]]; then
    echo "❌ SHA256SUMS izdaje nima vrstice za $APK_NAME. Prekinjam namestitev."
    exit 1
fi

DEJANSKO="$(sha256sum "$TEMP_APK" | awk '{print $1}')"

if [[ "$PRICAKOVANO" != "$DEJANSKO" ]]; then
    echo "❌ SHA-256 kontrolna vsota se NE ujema!"
    echo "   Pričakovano: $PRICAKOVANO"
    echo "   Dobljeno:    $DEJANSKO"
    echo "🚨 Prekinjam namestitev zaradi varnosti."
    exit 1
fi
echo "🔒 SHA-256 celovitost potrjena ($DEJANSKO)"

echo "APK: $(du -h "$TEMP_APK" | cut -f1)"

if ! command -v adb >/dev/null 2>&1; then
    cp "$TEMP_APK" ./"$APK_NAME"
    echo "ADB is not installed. The APK has been copied to ./$APK_NAME - install it on the TV by hand."
    exit 0
fi

if [[ -n "$TV" ]]; then
    adb connect "$TV" >/dev/null 2>&1 || true
fi
DEVICE="$(adb devices | awk '/\tdevice$/{print $1; exit}')"
if [[ -z "$DEVICE" ]]; then
    cp "$TEMP_APK" ./"$APK_NAME"
    echo "No ADB device. The APK has been copied to ./$APK_NAME"
    echo "For example: adb connect <TV-IP>:5555 && adb install -r $APK_NAME"
    exit 0
fi

echo "Installing on $DEVICE ..."
adb -s "$DEVICE" install -r "$TEMP_APK"
echo "Done."