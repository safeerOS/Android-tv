#!/usr/bin/env bash
# Preizkus install_safeer.sh brez omrezja in brez telefona (NALOGE-install): sintaksa, preverba SHA-256 s ponarejenimi
# SHA256SUMS, ustavitev brez SHA256SUMS ali ob napaki prenosa, pravi paket za zagon. curl in adb sta nadomestka.
set -uo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
SKRIPTA="$(dirname "$TEST_DIR")/install_safeer.sh"
D="$(mktemp -d)"
trap 'rm -rf "$D"' EXIT
napak=0
preveri() { if [ "$2" = "$3" ]; then echo "  OK   $1"; else echo "  NAPAKA $1: pricakovano $2, dobljeno $3"; napak=$((napak + 1)); fi; }

bash -n "$SKRIPTA"; preveri "bash -n" 0 $?

# shellcheck source=/dev/null
source "$SKRIPTA"
# Skripta sama vklopi »set -e«; preizkus pricakovane neuspehe preveri sam.
set +e
IME="safeer-browser-android-1.0.37.apk"
GH="https://github.com/safeerOS/Mobile-android/releases/download/v1.0.37"
printf 'ponarejen apk' > "$D/$IME"
PRAVA="$(sha256sum "$D/$IME" | awk '{print $1}')"

printf '%s  %s\n' "$PRAVA" "$IME" > "$D/prava"
preveri_sha256 "$D/$IME" "$D/prava" "$IME" > "$D/izpis" 2>&1; preveri "pravilna vsota (»  ime«) -> naprej" 0 $?
printf '%s *%s\n' "$PRAVA" "$IME" > "$D/zvezdica"
preveri_sha256 "$D/$IME" "$D/zvezdica" "$IME" > "$D/izpis" 2>&1; preveri "pravilna vsota (»*ime«) -> naprej" 0 $?
printf '%064d  %s\n' 0 "$IME" > "$D/napacna"
preveri_sha256 "$D/$IME" "$D/napacna" "$IME" > "$D/izpis" 2>&1; preveri "napacna vsota -> 1" 1 $?
grep -q "NE ujema" "$D/izpis"; preveri "napacna vsota: sporocilo" 0 $?
printf '%s  %s\n' "$PRAVA" "drug.apk" > "$D/brez"
preveri_sha256 "$D/$IME" "$D/brez" "$IME" > "$D/izpis" 2>&1; preveri "manjkajoca vrstica -> 1" 1 $?
: > "$D/prazna"
preveri_sha256 "$D/$IME" "$D/prazna" "$IME" > "$D/izpis" 2>&1; preveri "prazna SHA256SUMS -> 1" 1 $?
printf '%s  %s\n' "$PRAVA" "x-$IME" > "$D/podobna"
preveri_sha256 "$D/$IME" "$D/podobna" "$IME" > "$D/izpis" 2>&1; preveri "podobno ime ne velja -> 1" 1 $?

# Pregled Grok 7: APK po imenu paketa in samo z GitHuba - ne prvi .apk v izdaji.
VEC='{"browser_download_url": "'"$GH"'/safeer-debug.apk", "browser_download_url": "'"$GH"'/'"$IME"'", "browser_download_url": "'"$GH"'/SHA256SUMS"}'
preveri "vec APK: pravi po imenu" "$GH/$IME" "$(naslov_iz_izdaje "$VEC" "$VZOREC_APK")"
preveri "vec APK: SHA256SUMS" "$GH/SHA256SUMS" "$(naslov_iz_izdaje "$VEC" "$VZOREC_SHA")"
TUJ='{"browser_download_url": "https://x.test/'"$IME"'", "browser_download_url": "https://x.test/SHA256SUMS"}'
preveri "tuj gostitelj: ni APK" "" "$(naslov_iz_izdaje "$TUJ" "$VZOREC_APK")"
preveri "tuj gostitelj: ni SHA256SUMS" "" "$(naslov_iz_izdaje "$TUJ" "$VZOREC_SHA")"
DRUG='{"browser_download_url": "https://github.com/drug/repo/releases/download/v1/'"$IME"'"}'
preveri "drug repozitorij: ni APK" "" "$(naslov_iz_izdaje "$DRUG" "$VZOREC_APK")"
PODOBEN='{"browser_download_url": "'"$GH"'/safeer-browser-android-1.0.37-debug.apk"}'
preveri "razlicica s pripono: ni APK" "" "$(naslov_iz_izdaje "$PODOBEN" "$VZOREC_APK")"
( curl() { printf '%s' "$TUJ"; }; TEMP_APK="$D/t.apk"; TEMP_SHA="$D/ts"; glavno ) > "$D/izpis" 2>&1
preveri "tuj gostitelj -> 1" 1 $?
grep -q "nima APK z imenom safeer-browser-android" "$D/izpis"; preveri "tuj gostitelj: sporocilo o imenu APK" 0 $?
( curl() { return 22; }; TEMP_APK="$D/n.apk"; TEMP_SHA="$D/ns"; glavno ) > "$D/izpis" 2>&1
preveri "brez odgovora GitHuba -> 1" 1 $?
grep -q "GitHub ni odgovoril" "$D/izpis"; preveri "brez odgovora: sporocilo" 0 $?
[ ! -e "$D/t.apk" ]; preveri "tuj gostitelj: APK se ne prenese" 0 $?

# glavno() z nadomestnim curl: izdaja brez SHA256SUMS -> ustavi pred prenosom APK.
( curl() { printf '{"browser_download_url": "%s/%s"}' "$GH" "$IME"; }; TEMP_APK="$D/a.apk"; TEMP_SHA="$D/s"; glavno ) > "$D/izpis" 2>&1
preveri "izdaja brez SHA256SUMS -> 1" 1 $?
grep -q "nima datoteke SHA256SUMS" "$D/izpis"; preveri "brez SHA256SUMS: sporocilo" 0 $?
[ ! -e "$D/a.apk" ]; preveri "brez SHA256SUMS: APK se ne prenese" 0 $?

# Napaka prenosa SHA256SUMS (curl -f vrne napako) -> ustavi, adb se ne poklice.
( curl() {
      case "$*" in
          *api.github.com*) printf '{"browser_download_url": "%s/%s", "browser_download_url": "%s/SHA256SUMS"}' "$GH" "$IME" "$GH" ;;
          *-fLs*) return 22 ;;
          *) local o=""; while [ $# -gt 0 ]; do [ "$1" = "-o" ] && o="$2"; shift; done; printf 'ponarejen apk' > "$o" ;;
      esac
  }
  adb() { echo "ADB POKLICAN"; }
  TEMP_APK="$D/b.apk"; TEMP_SHA="$D/s2"; glavno ) > "$D/izpis" 2>&1
preveri "napaka prenosa SHA256SUMS -> 1" 1 $?
grep -q "Napaka pri prenosu SHA256SUMS" "$D/izpis"; preveri "napaka prenosa: sporocilo" 0 $?
! grep -q "ADB POKLICAN" "$D/izpis"; preveri "napaka prenosa: adb se ne poklice" 0 $?

# Pravilna izdaja: preveri vsoto, namesti in zazene com.safeer.mobile.browser (adb nadomestek).
( curl() {
      case "$*" in
          *api.github.com*) printf '{"browser_download_url": "%s/%s", "browser_download_url": "%s/SHA256SUMS"}' "$GH" "$IME" "$GH" ;;
          *) local o=""; while [ $# -gt 0 ]; do [ "$1" = "-o" ] && o="$2"; shift; done
             case "$o" in *SHA*|*s3) printf '%s  %s\n' "$PRAVA" "$IME" > "$o" ;; *) printf 'ponarejen apk' > "$o" ;; esac ;;
      esac
  }
  adb() { case "$1" in devices) printf 'List of devices attached\nNAPRAVA\tdevice\n' ;; *) echo "adb $*" ;; esac; }
  TEMP_APK="$D/c.apk"; TEMP_SHA="$D/s3"; glavno ) > "$D/izpis" 2>&1
preveri "pravilna izdaja -> 0" 0 $?
grep -q "celovitost potrjena" "$D/izpis"; preveri "pravilna izdaja: vsota preverjena" 0 $?
grep -q "am start -n com.safeer.mobile.browser/.MainActivity" "$D/izpis"; preveri "zagon com.safeer.mobile.browser/.MainActivity" 0 $?
! grep -q "si.safeer.tv/.MainActivity" "$D/izpis"; preveri "ne zazene TV brskalnika si.safeer.tv" 0 $?

if [ "$napak" -eq 0 ]; then echo "INSTALL TESTI: OK"; else echo "INSTALL TESTI: $napak napak"; exit 1; fi
