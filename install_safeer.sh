#!/usr/bin/env bash
# ==============================================================================
# 🛡️ 1-KLIK NAMESTITEV: SAFEER MOBILE BROWSER (SAMSUNG GALAXY & ANDROID)
# ==============================================================================
set -e

# Zadnja javna izdaja Safeer za Android: naslova APK in kontrolnih vsot preberemo iz GitHuba,
# da skripta deluje tudi po novih izdajah, ko se ime datoteke spremeni.
IZDAJA_API="https://api.github.com/repos/safeerOS/Mobile-android/releases/latest"
# Samo https, tudi po preusmeritvah; -f: napaka strezenika (404, 500) je napaka, ne prenesena stran z napako.
CURL_VARNO=(--proto '=https' --proto-redir '=https')
# Ime paketa aplikacije iz izdaje Mobile-android (ne TV brskalnika si.safeer.tv iz tega repozitorija).
PAKET="com.safeer.mobile.browser"
# Pregled Grok 7: datoteke izdaje vzamemo samo z GitHuba (prenosi izdaj Mobile-android) in APK po imenu paketa
# (safeer-browser-android-<verzija>.apk) - ne prvi .apk v izdaji, ce jih je vec (npr. razlicica za preizkus).
PRENOSI_IZDAJE='https://github\.com/safeerOS/Mobile-android/releases/download/[^"/]+'
VZOREC_APK="$PRENOSI_IZDAJE/safeer-browser-android-[0-9]+(\.[0-9]+)*\.apk"
VZOREC_SHA="$PRENOSI_IZDAJE/SHA256SUMS"

# Naslov datoteke izdaje iz JSON odgovora GitHub API ($1) po vzorcu ($2); prazno, ce je ni.
naslov_iz_izdaje() {
    printf '%s' "$1" | grep -oE "\"browser_download_url\": *\"$2\"" | head -n 1 | cut -d'"' -f4
}

# Celovitost: SHA256SUMS izdaje navaja vsoto pod imenom datoteke v izdaji (npr. safeer-browser-android-1.0.37.apk),
# v obliki »<vsota>  ime« ali »<vsota> *ime«. Vrne 0 samo, ce se vsota ujema; sicer izpise razlog in vrne 1.
preveri_sha256() {
    local apk="$1" vsote="$2" ime="$3" pricakovano dejansko
    if [ ! -s "$vsote" ]; then
        echo "❌ Datoteka SHA256SUMS je prazna ali je ni. Prekinjam namestitev."
        return 1
    fi
    pricakovano="$(awk -v ime="$ime" '$2 == ime || $2 == "*" ime { print $1; exit }' "$vsote")"
    if [ -z "$pricakovano" ]; then
        echo "❌ SHA256SUMS izdaje nima vrstice za $ime. Prekinjam namestitev."
        return 1
    fi
    dejansko="$(sha256sum "$apk" | awk '{print $1}')"
    if [ "$pricakovano" != "$dejansko" ]; then
        echo "❌ SHA-256 kontrolna vsota se NE ujema!"
        echo "   Pričakovano: $pricakovano"
        echo "   Dobljeno:    $dejansko"
        echo "🚨 Prekinjam namestitev zaradi varnosti."
        return 1
    fi
    echo "🔒 SHA-256 celovitost potrjena ($dejansko)"
}

# Safeer Vnos z ENO privolitvijo (lastnik 25. 10. 2026): namestitev prek adb aplikaciji podeli pravico, da ob privolitvi
# v Safeer Linku (ozji krog, »Zaslon in upravljanje«) SAMA vklopi svojo storitev dostopnosti (link/VnosVklop). Skripta
# storitve NE vklopi - to stori aplikacija sele ob uporabnikovi privolitvi. Android 13+ (API 33): brez odklepa omejenih
# nastavitev sistem vpis zavrne tudi prek adb (izmerjeno WP28 S, 25. 10. 2026 10:48: »Skipping enabling service
# disallowed by device admin policy«). Paket brez Safeer Vnosa (npr. brskalnik) ne dobi nicesar.
# Samo ta korak za ze namesceno aplikacijo:  install_safeer.sh --upravljanje <naprava> <paket>
VNOS_RAZRED="si.safeer.tv.link.VnosStoritev"
omogoci_upravljanje() {
    local naprava="$1" paket="$2" sdk
    if ! adb -s "$naprava" shell dumpsys package "$paket" 2>/dev/null | grep -q "$paket/$VNOS_RAZRED"; then
        echo "ℹ️ $paket nima Safeer Vnosa – upravljanja ni treba omogočiti."
        return 0
    fi
    if ! adb -s "$naprava" shell pm grant "$paket" android.permission.WRITE_SECURE_SETTINGS; then
        echo "❌ Pravice za Safeer Vnos ni bilo mogoče podeliti ($paket)."
        return 1
    fi
    sdk="$(adb -s "$naprava" shell getprop ro.build.version.sdk | tr -d '\r')"
    case "$sdk" in ''|*[!0-9]*) sdk=0 ;; esac
    if [ "$sdk" -ge 33 ]; then
        if ! adb -s "$naprava" shell appops set "$paket" ACCESS_RESTRICTED_SETTINGS allow; then
            echo "❌ Omejenih nastavitev ni bilo mogoče odkleniti ($paket)."
            return 1
        fi
    fi
    echo "✅ Safeer Vnos se vklopi ob privolitvi v Safeer Linku (ožji krog → »Zaslon in upravljanje«)."
    echo "   Preklic: adb -s $naprava shell pm revoke $paket android.permission.WRITE_SECURE_SETTINGS"
}

glavno() {
    # Zacasne datoteke v svoji mapi (mktemp -d), ne na predvidljivi poti v /tmp: drug uporabnik bi jo lahko vnaprej
    # pripravil ali zamenjal med prenosom in namestitvijo. Mapa izgine ob izhodu. (Preizkus poda svoje poti.)
    if [ -z "${TEMP_APK:-}" ]; then
        D="$(mktemp -d)"
        trap 'rm -rf "$D"' EXIT
        TEMP_APK="$D/Safeer-Browser.apk"
        TEMP_SHA="$D/Safeer-Browser.SHA256SUMS"
    fi

    IZDAJA_JSON="$(curl "${CURL_VARNO[@]}" -fsL "$IZDAJA_API" || true)"
    APK_URL="$(naslov_iz_izdaje "$IZDAJA_JSON" "$VZOREC_APK")"
    SHA_URL="$(naslov_iz_izdaje "$IZDAJA_JSON" "$VZOREC_SHA")"
    if [ -z "$IZDAJA_JSON" ]; then
        echo "❌ Ne najdem zadnje izdaje (GitHub ni odgovoril). Prenesi APK s https://safeer.si/browser/android/"
        exit 1
    fi
    # Neodvisni pregled 9: izdaja je, a brez APK s pricakovanim imenom (npr. preimenovan) - povemo, kaj manjka.
    if [ -z "$APK_URL" ]; then
        echo "❌ Zadnja izdaja nima APK z imenom safeer-browser-android-<verzija>.apk na github.com/safeerOS/Mobile-android."
        echo "👉 Prenesi APK s https://safeer.si/browser/android/"
        exit 1
    fi
    # Brez kontrolnih vsot APK ne namestimo (prej je bilo to samo opozorilo).
    if [ -z "$SHA_URL" ]; then
        echo "❌ Izdaja nima datoteke SHA256SUMS; celovitosti APK ni mogoče preveriti. Prekinjam namestitev."
        exit 1
    fi

    echo "=========================================================="
    echo "🛡️ SAFEER BROWSER: 1-KLIK NAMESTITEV"
    echo "=========================================================="

    echo "📥 Prenašam najnovejšo različico Safeer Browser APK..."
    if ! curl "${CURL_VARNO[@]}" -fsL -o "$TEMP_APK" "$APK_URL" || [ ! -s "$TEMP_APK" ]; then
        echo "❌ Napaka pri prenosu APK paketa."
        exit 1
    fi

    echo "✅ APK uspešno prenesen ($(du -h "$TEMP_APK" | cut -f1))"

    if ! curl "${CURL_VARNO[@]}" -fLs -o "$TEMP_SHA" "$SHA_URL"; then
        echo "❌ Napaka pri prenosu SHA256SUMS. Prekinjam namestitev."
        exit 1
    fi
    preveri_sha256 "$TEMP_APK" "$TEMP_SHA" "${APK_URL##*/}" || exit 1

    # Preveri prisotnost ADB
    if command -v adb >/dev/null 2>&1; then
        DEVICE=$(adb devices | grep -v "List" | grep "device$" | head -n 1 | awk '{print $1}')
        if [ -n "$DEVICE" ]; then
            echo "📱 Zaznana ADB naprava: $DEVICE"
            echo "📲 Nameščam Safeer Browser..."
            adb -s "$DEVICE" install -r "$TEMP_APK"
            omogoci_upravljanje "$DEVICE" "$PAKET" || echo "⚠️ Safeer Vnos bo treba vklopiti v aplikaciji (en korak)."
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
}

# Ob »source« (preizkus tests/preveri_install.sh) se namestitev ne zazene - samo funkcije.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    if [ "${1:-}" = "--upravljanje" ]; then
        omogoci_upravljanje "${2:?naprava, npr. 192.168.0.10:5555}" "${3:?paket, npr. si.safeer.os}"
        exit $?
    fi
    glavno "$@"
fi
