#!/usr/bin/env bash
# One-click download and install of Safeer TV Browser (Android TV, over ADB).
set -euo pipefail

REPO="safeerOS/Android-tv"
TEMP_APK="/tmp/safeer-browser-tv.apk"
TV="${1:-}"

echo "=========================================================="
echo "Safeer TV Browser - download and install"
echo "=========================================================="

echo "Looking up the latest release..."
APK_URL="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
    | grep -o 'https://[^"]*/safeer-browser-tv-[^"]*\.apk' | head -n 1)"

if [[ -z "$APK_URL" ]]; then
    echo "Could not find an APK in the latest release. Download it by hand:"
    echo "  https://github.com/$REPO/releases/latest"
    exit 1
fi

echo "Downloading $APK_URL"
curl -fL --retry 3 -o "$TEMP_APK" "$APK_URL"

if [[ ! -s "$TEMP_APK" ]]; then
    echo "Download failed."
    exit 1
fi

echo "APK: $(du -h "$TEMP_APK" | cut -f1)"

if ! command -v adb >/dev/null 2>&1; then
    echo "ADB is not installed. The APK is at $TEMP_APK - install it on the TV by hand."
    exit 0
fi

if [[ -n "$TV" ]]; then
    adb connect "$TV" >/dev/null 2>&1 || true
fi
DEVICE="$(adb devices | awk '/\tdevice$/{print $1; exit}')"
if [[ -z "$DEVICE" ]]; then
    echo "No ADB device. The APK is at $TEMP_APK"
    echo "For example: adb connect <TV-IP>:5555 && adb install -r $TEMP_APK"
    exit 0
fi

echo "Installing on $DEVICE ..."
adb -s "$DEVICE" install -r "$TEMP_APK"
echo "Done."
