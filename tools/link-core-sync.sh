#!/usr/bin/env bash
# Link Core: en sam vir za gostitelja in odjemalca Safeer Linka.
#
# Vir je brskalnik TV (src/main/kotlin/si/safeer/tv/cast). Telefon (safeer-browser) dobi kopijo
# s spremenjenim paketom; razlik v logiki NI. Kopije ne popravljaj na telefonu - popravi tu in
# pozeni ta skript. CI/preizkus pozene `--preveri`, ki pade, ce se kopija razlikuje.
#
#   tools/link-core-sync.sh [pot/do/safeer-browser]            # prepise kopijo na telefonu
#   tools/link-core-sync.sh --preveri [pot/do/safeer-browser]  # samo preveri, izhod 1 ob razliki
set -euo pipefail

PREVERI=0
if [[ "${1:-}" == "--preveri" ]]; then PREVERI=1; shift; fi
TV_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEL_DIR="${1:-$TV_DIR/../safeer-browser}"
SRC="$TV_DIR/src/main/kotlin/si/safeer/tv/cast"
DST="$TEL_DIR/src/main/kotlin/com/safeer/mobile/browser/cast"
PAKET_TV="si.safeer.tv.cast"
PAKET_TEL="com.safeer.mobile.browser.cast"

# Skupne datoteke (brez odvisnosti od aplikacije). Krmilnik, storitev, sprejemnik/odjemalec
# ostanejo v vsaki aplikaciji svoji.
DATOTEKE=(HubDiscovery.kt HubObjava.kt HubPairing.kt HubStreznik.kt HubTls.kt HubTokovi.kt
          HubUsmerjevalnik.kt JsonLahki.kt KrogNaprave.kt KrogZaupanja.kt Seznanitve.kt Spake2.kt)

GLAVA="// Preneseno iz brskalnika za televizor (si.safeer.tv.cast) brez sprememb v logiki:
// gostitelj Safeer Linka mora biti enak na vseh napravah, sicer se protokol razide.
// Ce se tu kaj spremeni, mora ista sprememba v tv-browser-2 (vir); kopijo naredi tools/link-core-sync.sh."

prevedi() {  # $1 = izvorna datoteka -> na stdout kopija za telefon
    local prva; prva="$(head -n 1 "$1")"
    if [[ "$prva" != "package $PAKET_TV" ]]; then echo "napaka: $1 se ne zacne s 'package $PAKET_TV'" >&2; exit 2; fi
    echo "package $PAKET_TEL"
    echo
    echo "$GLAVA"
    tail -n +2 "$1" | sed "s/\b$PAKET_TV\b/$PAKET_TEL/g"
}

[[ -d "$SRC" ]] || { echo "napaka: ni vira $SRC" >&2; exit 2; }
[[ -d "$DST" ]] || { echo "napaka: ni cilja $DST" >&2; exit 2; }

razlik=0
for f in "${DATOTEKE[@]}"; do
    [[ -f "$SRC/$f" ]] || { echo "napaka: manjka $SRC/$f" >&2; exit 2; }
    if (( PREVERI )); then
        if ! diff -q <(prevedi "$SRC/$f") "$DST/$f" >/dev/null 2>&1; then
            echo "RAZLIKA: $f (telefon se razlikuje od vira na TV)"; razlik=1
        fi
    else
        prevedi "$SRC/$f" > "$DST/$f.tmp" && mv "$DST/$f.tmp" "$DST/$f"
        echo "sinhronizirano: $f"
    fi
done
if (( PREVERI )); then
    (( razlik )) && { echo "Link Core na telefonu NI enak viru; pozeni tools/link-core-sync.sh"; exit 1; }
    echo "Link Core: kopija na telefonu je enaka viru (${#DATOTEKE[@]} datotek)."
fi
