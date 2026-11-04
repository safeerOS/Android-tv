#!/usr/bin/env bash
# Preizkus install_tv_browser.sh brez omrezja in brez TV (tedenski pregled 4. 11. 2026, S3-S5): izdaja iz seznama izdaj
# (ne /releases/latest, ki je lahko predvajalnik), izbira naprave (nikoli prva), SHA-256 in odtis podpisnika pred adb.
# Nadomestki curl/adb/odtis_apk v podlupinah klice glavno() iz source - shellcheck tega ne vidi.
# shellcheck disable=SC2317
set -uo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
SKRIPTA="$(dirname "$TEST_DIR")/install_tv_browser.sh"
D="$(mktemp -d)"
trap 'rm -rf "$D"' EXIT
napak=0
preveri() { if [ "$2" = "$3" ]; then echo "  OK   $1"; else echo "  NAPAKA $1: pricakovano $2, dobljeno $3"; napak=$((napak + 1)); fi; }
pogoj() { if "$@"; then echo 0; else echo 1; fi; }

bash -n "$SKRIPTA"; preveri "bash -n" 0 $?
# shellcheck source=/dev/null
source "$SKRIPTA"
set +e
najdi_apksigner() { printf '%s' /lazni/apksigner; }   # primeri nadomestijo odtis_apk; apksigner spodaj
GH="https://github.com/safeerOS/Android-tv/releases/download"
IME="safeer-browser-tv-2.1.232.apk"
printf 'ponarejen apk' > "$D/apk"
PRAVA="$(sha256sum "$D/apk" | awk '{print $1}')"
# Seznam izdaj: najnovejsa je izdaja predvajalnika (brez APK brskalnika), nato brskalnik.
IZDAJE='[{"tag_name": "predvajalnik-v1.2", "assets": [{"browser_download_url": "'"$GH"'/predvajalnik-v1.2/safeer-predvajalnik-1.2.apk"}]},
 {"tag_name": "v2.1.232", "assets": [{"browser_download_url": "'"$GH"'/v2.1.232/'"$IME"'"}, {"browser_download_url": "'"$GH"'/v2.1.232/SHA256SUMS"}]},
 {"tag_name": "v2.1.231", "assets": [{"browser_download_url": "'"$GH"'/v2.1.231/safeer-browser-tv-2.1.231.apk"}]}]'
preveri "izdaja: prvi APK brskalnika (ne predvajalnik)" "$GH/v2.1.232/$IME" "$(naslov_iz_izdaj "$IZDAJE" "$VZOREC_APK")"
preveri "izdaja: tuj gostitelj ne" "" "$(naslov_iz_izdaj '{"browser_download_url": "https://x.test/v2/'"$IME"'"}' "$VZOREC_APK")"
preveri "izdaja: oznaka brez v<stevilka> ne" "" "$(naslov_iz_izdaj '{"browser_download_url": "'"$GH"'/beta/'"$IME"'"}' "$VZOREC_APK")"

lazni_curl() {
    case "$*" in
        *api.github.com*) printf '%s' "$IZDAJE" ;;
        *) local o=""; while [ $# -gt 0 ]; do [ "$1" = "-o" ] && o="$2"; shift; done
           case "$o" in *SHA256SUMS) printf '%s  %s\n' "${LAZNA_VSOTA:-$PRAVA}" "$IME" > "$o" ;; *) printf 'ponarejen apk' > "$o" ;; esac ;;
    esac
}
lazni_adb() {
    case "$1" in
        devices) printf 'List of devices attached\n'; for n in $LAZNE_NAPRAVE; do printf '%s\tdevice\n' "$n"; done ;;
        *) echo "adb $*" ;;
    esac
}
zazeni() {  # $1 = odtis, $2 = naprave, ostalo = argumenti glavno
    # Globalni imeni: glavno() ima svoj »local odtis«, ki bi ga nadomestek (dinamicni doseg) sicer videl prazen.
    LAZNI_ODTIS="$1"; LAZNE_NAPRAVE="$2"; shift 2
    ( cd "$D" && curl() { lazni_curl "$@"; }; odtis_apk() { printf '%s' "$LAZNI_ODTIS"; }
      adb() { lazni_adb "$@"; }; glavno "$@" ) > "$D/izpis" 2>&1
}

zazeni "$TV_ODTIS" "TV"; preveri "edina naprava -> 0" 0 $?
grep -q "adb -s TV install -r" "$D/izpis"; preveri "edina naprava: namesti" 0 $?
grep -q "Podpisnik Safeer potrjen" "$D/izpis"; preveri "podpisnik preverjen" 0 $?
zazeni "$TV_ODTIS" "TELEFON TV"; preveri "dve napravi brez izbire -> 1" 1 $?
! grep -q " install " "$D/izpis"; preveri "dve napravi brez izbire: ni namestitve" 0 $?
zazeni "$TV_ODTIS" "TELEFON 10.0.0.5:5555" 10.0.0.5:5555; preveri "izbran TV -> 0" 0 $?
grep -q "adb -s 10.0.0.5:5555 install -r" "$D/izpis"; preveri "izbran TV: namesti nanj" 0 $?
! grep -q "adb -s TELEFON" "$D/izpis"; preveri "izbran TV: telefona ne dotakne" 0 $?
zazeni "$TV_ODTIS" "TELEFON" 10.0.0.5:5555; preveri "izbran TV ni povezan -> 1" 1 $?
! grep -q " install " "$D/izpis"; preveri "izbran TV ni povezan: ni namestitve" 0 $?
rm -f "$D/$IME"   # kopija iz primera z dvema napravama (zacasna mapa preizkusa)
zazeni "0000000000000000000000000000000000000000000000000000000000000000" "TV"; preveri "tuj podpisnik -> 1" 1 $?
! grep -q " install " "$D/izpis"; preveri "tuj podpisnik: ni namestitve" 0 $?
preveri "tuj podpisnik: APK ne ostane v mapi uporabnika" 0 "$(pogoj [ ! -e "$D/$IME" ])"
zazeni "" "TV"; preveri "odtisa ni mogoce prebrati -> 1" 1 $?
LAZNA_VSOTA="$(printf '%064d' 0)" zazeni "$TV_ODTIS" "TV"; preveri "napacna vsota -> 1" 1 $?
! grep -q " install " "$D/izpis"; preveri "napacna vsota: ni namestitve" 0 $?

# Neodvisni pregled 4. 11. 2026 09:10: podpisnika bere samo apksigner z absolutne poti (ne keytool, ki vidi le v1).
echo "== podpisnik (locen proces, lazna orodja)"
# shellcheck source=tests/lazna_namestitev.sh
source "$TEST_DIR/lazna_namestitev.sh"
preizkusi_podpisnika "$SKRIPTA" "$IZDAJE" "$IME" "$TV_ODTIS"

if [ "$napak" -eq 0 ]; then echo "INSTALL TV TESTI: OK"; else echo "INSTALL TV TESTI: $napak napak"; exit 1; fi
