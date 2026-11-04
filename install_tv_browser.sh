#!/usr/bin/env bash
# One-click download and install of Safeer TV Browser (Android TV, over ADB).
#   install_tv_browser.sh [TV-IP:5555]
# Tedenski pregled 4. 11. 2026: izdaja iz seznama izdaj (prva v<stevilka> z APK brskalnika - /releases/latest je lahko
# izdaja predvajalnika), naprava izbrana izrecno (nikoli prva v adb), pred namestitvijo SHA-256 IN odtis podpisnika.
set -euo pipefail

REPO="safeerOS/Android-tv"
CURL_VARNO=(--proto '=https' --proto-redir '=https')
# Datoteke izdaje samo z GitHuba tega repozitorija; APK po imenu (safeer-browser-tv-<verzija>.apk), oznaka v<stevilka>.
PRENOSI_IZDAJE='https://github\.com/safeerOS/Android-tv/releases/download/v[0-9][^"/]*'
VZOREC_APK="$PRENOSI_IZDAJE/safeer-browser-tv-[0-9]+(\.[0-9]+)*\.apk"
# Odtis SHA-256 potrdila, s katerim so podpisane izdaje TV Browserja (apksigner verify --print-certs na
# safeer-browser-tv-2.1.232.apk, 4. 11. 2026; enak SAFEER_ODTIS v install_safeer.sh).
TV_ODTIS="E7808780C6F492ADC60036BE680667CF74BDE44164B5C63179E1D0F7BDFD7E16"

# Prvi naslov po vzorcu ($2) iz JSON seznama izdaj ($1); GitHub vrne izdaje od najnovejse naprej.
# CodeRabbit 4. 11. 2026: brez zadetka grep vrne 1 in pod set -euo pipefail je prirejanje koncalo skripto pred
# razlago (»Download it by hand«, »Could not find SHA256SUMS«). Brez zadetka je izid prazen niz.
naslov_iz_izdaj() {
    local zadetek
    zadetek="$(printf '%s' "$1" | grep -oE "\"browser_download_url\": *\"$2\"" | head -n 1)" || true
    printf '%s' "$zadetek" | cut -d'"' -f4
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

# Naprava za namestitev: izbrana ($1) ali edina povezana; vec naprav brez izbire -> prazno in sporocilo (nikoli prva).
izberi_napravo() {
    local izbrana="$1" naprave stevilo
    naprave="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
    if [[ -n "$izbrana" ]]; then
        if printf '%s\n' "$naprave" | grep -qxF "$izbrana"; then echo "$izbrana"; else echo "TV $izbrana ni povezan prek ADB." >&2; fi
        return 0
    fi
    stevilo="$(printf '%s\n' "$naprave" | grep -c . || true)"
    if [[ "$stevilo" -gt 1 ]]; then
        echo "More ADB devices are connected - choose the TV: install_tv_browser.sh <TV-IP>:5555" >&2
        printf '%s\n' "$naprave" | sed 's/^/  /' >&2
        return 0
    fi
    printf '%s' "$naprave"
}

glavno() {
    local tv="${1:-}"
    TMP_DIR="$(mktemp -d)"
    trap 'rm -rf "$TMP_DIR"' EXIT
    local apk="$TMP_DIR/safeer-browser-tv.apk" sha="$TMP_DIR/SHA256SUMS"
    # Brez apksignerja podpisnika ni mogoce preveriti - povemo pred prenosom, ne po njem.
    if ! najdi_apksigner >/dev/null; then
        echo "❌ $NI_APKSIGNERJA Prekinjam namestitev."
        exit 1
    fi

    echo "=========================================================="
    echo "Safeer TV Browser - download and install"
    echo "=========================================================="

    echo "Looking up the latest release..."
    local izdaje apk_url sha_url oznaka
    izdaje="$(curl -fsSL "${CURL_VARNO[@]}" "https://api.github.com/repos/$REPO/releases?per_page=20")"
    apk_url="$(naslov_iz_izdaj "$izdaje" "$VZOREC_APK")"
    if [[ -z "$apk_url" ]]; then
        echo "Could not find a TV Browser APK in the releases. Download it by hand:"
        echo "  https://github.com/$REPO/releases"
        exit 1
    fi
    # SHA256SUMS iz ISTE izdaje kot APK.
    oznaka="${apk_url%/*}"; oznaka="${oznaka##*/}"
    sha_url="$(naslov_iz_izdaj "$izdaje" "https://github\.com/safeerOS/Android-tv/releases/download/$oznaka/SHA256SUMS")"

    echo "Downloading $apk_url"
    curl -fL --retry 3 "${CURL_VARNO[@]}" -o "$apk" "$apk_url"
    [[ -s "$apk" ]] || { echo "Download failed."; exit 1; }
    if [[ -z "$sha_url" ]]; then
        echo "Could not find SHA256SUMS in release $oznaka. Prekinjam namestitev."
        exit 1
    fi
    echo "Downloading SHA256SUMS"
    curl -fL --retry 3 "${CURL_VARNO[@]}" -o "$sha" "$sha_url"
    [[ -s "$sha" ]] || { echo "SHA256SUMS download failed."; exit 1; }

    local ime pricakovano dejansko odtis
    ime="${apk_url##*/}"
    pricakovano="$(awk -v ime="$ime" '$2 == ime || $2 == "*" ime { print $1; exit }' "$sha")"
    if [[ -z "$pricakovano" ]]; then
        echo "❌ SHA256SUMS izdaje nima vrstice za $ime. Prekinjam namestitev."
        exit 1
    fi
    dejansko="$(sha256sum "$apk" | awk '{print $1}')"
    if [[ "$pricakovano" != "$dejansko" ]]; then
        echo "❌ SHA-256 kontrolna vsota se NE ujema!"
        echo "   Pričakovano: $pricakovano"
        echo "   Dobljeno:    $dejansko"
        echo "🚨 Prekinjam namestitev zaradi varnosti."
        exit 1
    fi
    echo "🔒 SHA-256 celovitost potrjena ($dejansko)"
    # Vsota in APK sta iz iste izdaje: ce bi bila izdaja podtaknjena, vsota ne pomaga - podpisnik pa mora biti Safeer.
    odtis="$(odtis_apk "$apk")"
    if [[ "$odtis" != "$TV_ODTIS" ]]; then
        echo "❌ APK ni podpisan s ključem Safeer (odtis: ${odtis:-ni ga bilo mogoče prebrati})."
        echo "🚨 Prekinjam namestitev."
        exit 1
    fi
    echo "🔏 Podpisnik Safeer potrjen"
    echo "APK: $(du -h "$apk" | cut -f1)"

    if ! command -v adb >/dev/null 2>&1; then
        cp "$apk" ./"$ime"
        echo "ADB is not installed. The APK has been copied to ./$ime - install it on the TV by hand."
        exit 0
    fi
    if [[ -n "$tv" ]]; then
        adb connect "$tv" >/dev/null 2>&1 || true
    fi
    local naprava
    naprava="$(izberi_napravo "$tv")"
    if [[ -z "$naprava" ]]; then
        cp "$apk" ./"$ime"
        echo "No ADB device chosen. The APK has been copied to ./$ime"
        echo "For example: install_tv_browser.sh <TV-IP>:5555"
        exit 1
    fi
    echo "Installing on $naprava ..."
    adb -s "$naprava" install -r "$apk"
    echo "Done."
}

# Ob »source« (tests/run_install_tests.sh) samo funkcije.
if [[ "${BASH_SOURCE[0]}" = "$0" ]]; then
    glavno "$@"
fi
