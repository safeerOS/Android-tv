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
# Pregled tv-vnos (25. 10. 2026, tocka 6): adb shell argumente zlepi v en niz za sh na napravi - zato ime paketa in
# oznako naprave preverimo z vzorcem (brez ; | $ presledkov ...), ime storitve iscemo dobesedno (grep -F), pravice pa
# dobi samo paket, podpisan s kljucem Safeer (javni odtis SHA-256 potrdila, keystore/safeer-tv-release.jks).
SAFEER_ODTIS="E7808780C6F492ADC60036BE680667CF74BDE44164B5C63179E1D0F7BDFD7E16"
# Tedenski pregled 4. 11. 2026 (S5): odtis podpisnika Safeer Browserja za telefon (apksigner verify --print-certs na
# safeer-browser-android-1.0.37.apk z Mobile-android, 4. 11. 2026). Vsota SHA-256 je iz iste izdaje kot APK in podtaknjene
# izdaje ne odkrije - podpisnik jo.
MOBILNI_ODTIS="442B4A455551887DDB7BB2F2F62CC1574EE3EF0EEBB56246A2E425C9E9D9CC55"
VZOREC_PAKETA='^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'
VZOREC_NAPRAVE='^[A-Za-z0-9.:_-]+$'

# Odtis SHA-256 podpisnika namescenega paketa (velike crke brez dvopicij); prazno, ce ga ni mogoce prebrati.
odtis_paketa() {
    local naprava="$1" paket="$2" d pot
    d="$(mktemp -d)" || return 0
    pot="$(adb -s "$naprava" shell pm path "$paket" 2>/dev/null | tr -d '\r' | grep -m1 -E '^package:/.*/base\.apk$' | cut -d: -f2-)"
    if [ -n "$pot" ] && adb -s "$naprava" pull "$pot" "$d/base.apk" >/dev/null 2>&1; then
        odtis_apk "$d/base.apk"
    fi
    rm -rf "$d"
}

# Neodvisni pregled 4. 11. 2026 09:10: podpisnika bere SAMO apksigner (sheme v1-v3.1). Prejsnji nadomestek
# keytool -printcert -jarfile vidi le podpis v1 (JAR): APK z ohranjenim Safeer v1 in tujim v2/v3 je sel skozi, Android
# pa ga preveri in namesti po v2/v3. Brez apksignerja namestitev zavrnemo. Pot je absolutna (pripeta build-tools v
# Android SDK ali sistemski paket apksigner), ne iz PATH.
APKSIGNER_RAZLICICE="36.0.0 35.0.0 34.0.0"
najdi_apksigner() {
    local koren r
    for koren in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${HOME:-}/Android/Sdk"; do
        case "$koren" in /*) ;; *) continue ;; esac
        for r in $APKSIGNER_RAZLICICE; do
            if [ -x "$koren/build-tools/$r/apksigner" ]; then printf '%s' "$koren/build-tools/$r/apksigner"; return 0; fi
        done
    done
    if [ -x /usr/bin/apksigner ]; then printf '%s' /usr/bin/apksigner; return 0; fi
    return 1
}
NI_APKSIGNERJA="Za preverbo podpisa je potreben apksigner (Android SDK build-tools ali paket: sudo apt install apksigner)."

# Odtis SHA-256 podpisnika APK (velike crke brez dvopicij); prazno, ce apksignerja ni, APK ne preveri ali ima vec
# podpisnikov (tudi dodaten podpisnik v3.1 za novejse Androide - ta bi na takem Androidu odlocal).
odtis_apk() {
    local apksigner izpis
    apksigner="$(najdi_apksigner)" || return 0
    izpis="$("$apksigner" verify --print-certs "$1" 2>/dev/null)" || return 0
    [ "$(printf '%s\n' "$izpis" | grep -c 'certificate SHA-256 digest:')" = 1 ] || return 0
    printf '%s\n' "$izpis" | awk '/^Signer #1 certificate SHA-256 digest:/ { print toupper($NF); exit }'
}

# Naprava za namestitev (S3): izbrana ($1) ali edina povezana. Vec naprav brez izbire -> prazno in sporocilo (nikoli
# prva v seznamu - lahko je tuja naprava ali TV namesto telefona).
izberi_napravo() {
    local izbrana="$1" naprave stevilo
    naprave="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
    if [ -n "$izbrana" ]; then
        if printf '%s\n' "$naprave" | grep -qxF "$izbrana"; then echo "$izbrana"; else echo "❌ Naprava $izbrana ni povezana prek ADB." >&2; fi
        return 0
    fi
    stevilo="$(printf '%s\n' "$naprave" | grep -c . || true)"
    if [ "$stevilo" -gt 1 ]; then
        echo "❌ Povezanih je več naprav ADB - izberi telefon: install_safeer.sh <serijska ali IP:vrata>" >&2
        printf '%s\n' "$naprave" | sed 's/^/   /' >&2
        return 0
    fi
    printf '%s' "$naprave"
}

omogoci_upravljanje() {
    local naprava="$1" paket="$2" sdk odtis
    if ! [[ "$paket" =~ $VZOREC_PAKETA ]]; then
        echo "❌ Neveljavno ime paketa: ${paket:0:80}"
        return 1
    fi
    if ! [[ "$naprava" =~ $VZOREC_NAPRAVE ]]; then
        echo "❌ Neveljavna oznaka naprave (npr. 192.168.0.10:5555 ali serijska številka)."
        return 1
    fi
    if ! adb -s "$naprava" shell dumpsys package "$paket" 2>/dev/null | grep -qF "$paket/$VNOS_RAZRED"; then
        echo "ℹ️ $paket nima Safeer Vnosa – upravljanja ni treba omogočiti."
        return 0
    fi
    odtis="$(odtis_paketa "$naprava" "$paket")"
    if [ "$odtis" != "$SAFEER_ODTIS" ]; then
        echo "❌ $paket ni podpisan s ključem Safeer (odtis: ${odtis:-ni ga bilo mogoče prebrati}) – pravic ne podelim."
        [ -n "$odtis" ] || najdi_apksigner >/dev/null || echo "   $NI_APKSIGNERJA"
        return 1
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
    if [ "$sdk" -ge 33 ]; then
        echo "            adb -s $naprava shell appops set $paket ACCESS_RESTRICTED_SETTINGS default"
    fi
}

glavno() {
    local izbrana="${1:-}"
    # Zacasne datoteke vedno v svoji mapi (mktemp -d), ne na predvidljivi poti v /tmp ali poti iz okolja (N10, 4. 11. 2026:
    # prej je TEMP_APK iz okolja dolocil, kam se APK prenese): drug uporabnik bi jo lahko vnaprej pripravil ali zamenjal
    # med prenosom in namestitvijo. Mapa izgine ob izhodu.
    MAPA_NAMESTITVE="$(mktemp -d)"
    trap 'rm -rf "$MAPA_NAMESTITVE"' EXIT
    TEMP_APK="$MAPA_NAMESTITVE/Safeer-Browser.apk"
    TEMP_SHA="$MAPA_NAMESTITVE/Safeer-Browser.SHA256SUMS"
    # Brez apksignerja podpisnika ni mogoce preveriti - povemo pred prenosom, ne po njem.
    if ! najdi_apksigner >/dev/null; then
        echo "❌ $NI_APKSIGNERJA Prekinjam namestitev."
        exit 1
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
    ODTIS="$(odtis_apk "$TEMP_APK")"
    if [ "$ODTIS" != "$MOBILNI_ODTIS" ]; then
        echo "❌ APK ni podpisan s ključem Safeer (odtis: ${ODTIS:-ni ga bilo mogoče prebrati})."
        echo "🚨 Prekinjam namestitev."
        exit 1
    fi
    echo "🔏 Podpisnik Safeer potrjen"

    # Preveri prisotnost ADB
    if command -v adb >/dev/null 2>&1; then
        DEVICE="$(izberi_napravo "$izbrana")"
        if [ -z "$DEVICE" ] && [ -n "$izbrana$(adb devices | awk 'NR > 1 && $2 == "device"')" ]; then
            # Izbrane ni ali jih je vec brez izbire: ne namestimo na nakljucno napravo.
            exit 1
        fi
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

# Ob »source« (preizkus tests/run_install_tests.sh) se namestitev ne zazene - samo funkcije.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    if [ "${1:-}" = "--upravljanje" ]; then
        omogoci_upravljanje "${2:?naprava, npr. 192.168.0.10:5555}" "${3:?paket, npr. si.safeer.os}"
        exit $?
    fi
    glavno "$@"
fi
