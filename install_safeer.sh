#!/usr/bin/env bash
# ==============================================================================
# 🛡️ 1-KLIK NAMESTITEV: SAFEER MOBILE BROWSER (SAMSUNG GALAXY & ANDROID)
# ==============================================================================
set -e

# Zadnja javna izdaja Safeer za Android: naslova APK in kontrolnih vsot preberemo iz GitHuba,
# da skripta deluje tudi po novih izdajah, ko se ime datoteke spremeni.
IZDAJA_API="https://api.github.com/repos/safeerOS/Mobile-android/releases/latest"
IZDAJA_JSON="$(curl -L -s "$IZDAJA_API")"
APK_URL="$(printf '%s' "$IZDAJA_JSON" | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -n 1 | cut -d'"' -f4)"
SHA_URL="$(printf '%s' "$IZDAJA_JSON" | grep -o '"browser_download_url": *"[^"]*SHA256SUMS"' | head -n 1 | cut -d'"' -f4)"
if [ -z "$APK_URL" ]; then
    echo "❌ Ne najdem zadnje izdaje. Prenesi APK s https://safeer.si/browser/android/"
    exit 1
fi
TEMP_APK="/tmp/Safeer-Browser.apk"
TEMP_SHA="/tmp/Safeer-Browser.SHA256SUMS"
# Ime paketa aplikacije iz izdaje Mobile-android (ne TV brskalnika si.safeer.tv iz tega repozitorija).
PAKET="com.safeer.mobile.browser"

echo "=========================================================="
echo "🛡️ SAFEER BROWSER: 1-KLIK NAMESTITEV"
echo "=========================================================="

echo "📥 Prenašam najnovejšo različico Safeer Browser APK..."
curl -L -s -o "$TEMP_APK" "$APK_URL"

if [ ! -s "$TEMP_APK" ]; then
    echo "❌ Napaka pri prenosu APK paketa."
    exit 1
fi

echo "✅ APK uspešno prenesen ($(du -h "$TEMP_APK" | cut -f1))"

# Celovitost: SHA256SUMS izdaje navaja vsoto pod imenom datoteke v izdaji (npr. safeer-browser-android-1.0.36.apk).
APK_IME="${APK_URL##*/}"
if [ -n "$SHA_URL" ]; then
    curl -fLs -o "$TEMP_SHA" "$SHA_URL"
    PRICAKOVANO="$(awk -v ime="$APK_IME" '$2 == ime || $2 == "*" ime { print $1; exit }' "$TEMP_SHA")"
    if [ -z "$PRICAKOVANO" ]; then
        echo "❌ SHA256SUMS izdaje nima vrstice za $APK_IME. Prekinjam namestitev."
        exit 1
    fi
    DEJANSKO="$(sha256sum "$TEMP_APK" | awk '{print $1}')"
    if [ "$PRICAKOVANO" != "$DEJANSKO" ]; then
        echo "❌ SHA-256 kontrolna vsota se NE ujema!"
        echo "   Pričakovano: $PRICAKOVANO"
        echo "   Dobljeno:    $DEJANSKO"
        echo "🚨 Prekinjam namestitev zaradi varnosti."
        exit 1
    fi
    echo "🔒 SHA-256 celovitost potrjena ($DEJANSKO)"
else
    echo "⚠️ Izdaja nima datoteke SHA256SUMS; celovitosti APK ni mogoče preveriti."
fi

# Preveri prisotnost ADB
if command -v adb >/dev/null 2>&1; then
    DEVICE=$(adb devices | grep -v "List" | grep "device$" | head -n 1 | awk '{print $1}')
    if [ -n "$DEVICE" ]; then
        echo "📱 Zaznana ADB naprava: $DEVICE"
        echo "📲 Nameščam Safeer Browser..."
        adb -s "$DEVICE" install -r "$TEMP_APK"
        echo "🚀 Zaganjam Safeer Browser..."
        adb -s "$DEVICE" shell am start -n "$PAKET/.MainActivity"
        echo "=========================================================="
        echo "🎉 SAFEER BROWSER JE USPEŠNO NAMEŠČEN IN ZAGNAN!"
        echo "=========================================================="
        exit 0
    fi
fi

# Če ni zaznane ADB naprave
cp "$TEMP_APK" "./Safeer-Browser.apk"
echo "ℹ️ APK paket je shranjen v: $(pwd)/Safeer-Browser.apk"
echo "👉 Lahko ga ročno pošljete ali namestite na svoj telefon."
