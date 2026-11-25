#!/usr/bin/env bash
# Tedenski pregled 25. 11. 2026 (N5/N2): build_tv_apk.sh ob manjkajocem keystoru ne sme tiho ustvariti novega
# produkcijskega kljuca (nov kljuc = namescena aplikacija se pri uporabnikih ne more posodobiti); nov kljuc samo z
# --nov-kljuc, gesla nikoli v argumentih ukaza. Skripto pozenemo v zacasni kopiji projekta z laznimi orodji (gradlew,
# keytool, zipalign, apksigner, unzip, curl), ki svoje argumente zapisejo v dnevnik. Brez pravega kljuca, brez omrezja,
# brez prave gradnje. Okolje je prazno (env -i): ANDROID_SDK_ROOT ali RELEASE_KEYSTORE lastnika ne pridejo zraven.
# Uporaba: preveri_podpis_tv.sh [pot do build_tv_apk.sh]   (privzeto skripta tega repozitorija)
set -uo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
SKRIPTA="${1:-$TEST_DIR/../build_tv_apk.sh}"
GESLO_KLJUCA="Lazno-KLJUC-n5-4c1d"
GESLO_SHRAMBE="Lazno-SHRAMBA-n5-9e2b"
padci=0
preveri() {   # preveri <opis> <pricakovano> <dobljeno>
    if [ "$2" = "$3" ]; then echo "  OK  $1"; else echo "  PADEL $1 (pricakovano $2, dobljeno $3)"; padci=$((padci + 1)); fi
}

KOREN="$(mktemp -d /tmp/safeer-podpis-tv.XXXXXX)"
trap 'rm -rf "$KOREN"' EXIT

lazno() {   # lazno <pot> <telo>: lazno orodje, ki zapise svoje ime in argumente v $LOG
    mkdir -p "$(dirname "$1")"
    printf '#!/usr/bin/env bash\necho "%s $*" >> "$LOG"\n%s\n' "$1" "$2" > "$1"
    chmod +x "$1"
}

# pripravi <ime>: zacasni projekt s kopijo skripte, laznim gradlew in lazno pripeto build-tools 34.0.0
pripravi() {
    P="$KOREN/$1/projekt"; B="$KOREN/$1/bin"; LOG="$KOREN/$1/ukazi.log"; IZPIS="$KOREN/$1/izpis"
    mkdir -p "$P/tests" "$P/gradle/wrapper"
    cp "$SKRIPTA" "$P/build_tv_apk.sh"
    : > "$LOG"
    : > "$P/gradle/wrapper/gradle-wrapper.jar"   # obstaja: brez prenosa
    printf 'import sys\nsys.exit(0)\n' > "$P/tests/check_public_package.py"
    cp "$P/tests/check_public_package.py" "$P/tests/preveri_loceni_aplikaciji.py"
    lazno "$P/gradlew" 'for o in brskalnik os; do mkdir -p "build/outputs/apk/$o/release"; echo apk > "build/outputs/apk/$o/release/x-$o-release-unsigned.apk"; done'
    local sdk="$P/.android-sdk"
    mkdir -p "$sdk/platforms/android-34"; : > "$sdk/platforms/android-34/android.jar"
    lazno "$sdk/build-tools/34.0.0/aapt2" ':'
    lazno "$sdk/build-tools/34.0.0/zipalign" 'exit 0'
    lazno "$sdk/build-tools/34.0.0/apksigner" 'if [ "$1" = sign ]; then o=""; while [ $# -gt 1 ]; do [ "$1" = --out ] && o="$2"; shift; done; cp "$1" "$o"; fi; exit 0'
    lazno "$B/curl" 'exit 7'
    lazno "$B/keytool" 'while [ $# -gt 0 ]; do [ "$1" = -keystore ] && : > "$2"; shift; done; exit 0'
    # unzip: v -d zapise classes.dex z razredi Media3, ki jih skripta preveri
    lazno "$B/unzip" 'd=""; while [ $# -gt 0 ]; do [ "$1" = -d ] && d="$2"; shift; done; mkdir -p "$d"; printf "androidx/media3/exoplayer/ExoPlayer androidx/media3/exoplayer/dash/DashMediaSource" > "$d/classes.dex"'
}

pozeni() {   # pozeni <geslo shrambe ali prazno> [argumenti skripte]: gesla samo v okolju, vse ostalo okolje prazno
    local shramba="$1"; shift
    local okolje=(HOME="$KOREN" PATH="$B:/usr/bin:/bin" LOG="$LOG" RELEASE_KEY_PASS="$GESLO_KLJUCA")
    if [ -n "$shramba" ]; then okolje+=(RELEASE_STORE_PASS="$shramba"); fi
    env -i "${okolje[@]}" bash "$P/build_tv_apk.sh" "$@" > "$IZPIS" 2>&1
    local rc=$?
    if [ -n "${PODPIS_IZPIS:-}" ]; then sed 's/^/    | /' "$IZPIS"; fi   # PODPIS_IZPIS=1: izpis gradnje za razhroscevanje
    return "$rc"
}

echo "Podpis APK TV (N5):"

# (b0) kontrola: obstojec keystore, razlicni gesli -> lazna gradnja pride do konca in podpise
pripravi b0; mkdir -p "$P/keystore"; : > "$P/keystore/safeer-tv-release.jks"; pozeni "$GESLO_SHRAMBE"; rc=$?
preveri "kontrola: obstojec keystore -> 0" 0 "$rc"
[ -f "$P/TV-Browser-2.apk" ] && [ -f "$P/Safeer-OS.apk" ]; preveri "kontrola: podpisana APK-ja nastala" 0 $?
grep -q "^$P/.android-sdk/build-tools/34.0.0/apksigner sign .*--ks-pass env:RELEASE_STORE_PASS --key-pass env:RELEASE_KEY_PASS" "$LOG"
preveri "apksigner z absolutne poti, gesli prek env:" 0 $?
grep -q -- '-genkeypair' "$LOG"; preveri "obstojec keystore: brez keytool -genkeypair" 1 $?
grep -q "^$B/curl" "$LOG"; preveri "brez omrezja (curl ni klican)" 1 $?
grep -qF -e "$GESLO_KLJUCA" -e "$GESLO_SHRAMBE" "$LOG"; preveri "obstojec keystore: gesli nista v argumentih" 1 $?

# (a) manjkajoc keystore brez zastavice: ustavi, brez keytool -genkeypair (enaki gesli - stara skripta bi kljuc ustvarila)
pripravi a; pozeni ""; rc=$?
grep -q 'Podpisujem s produkcijskim' "$IZPIS"; preveri "kontrola: lazna gradnja pride do podpisa" 0 $?
[ "$rc" -ne 0 ]; preveri "manjkajoc keystore brez --nov-kljuc -> izhod != 0" 0 $?
grep -q -- '-genkeypair' "$LOG"; preveri "manjkajoc keystore: brez keytool -genkeypair" 1 $?
[ -e "$P/keystore/safeer-tv-release.jks" ]; preveri "manjkajoc keystore: nov kljuc ni nastal" 1 $?
grep -q -- '--nov-kljuc' "$IZPIS"; preveri "manjkajoc keystore: sporocilo omeni --nov-kljuc" 0 $?

# (b) --nov-kljuc: keytool z gesli iz okolja, nato podpis
pripravi b; pozeni "" --nov-kljuc; rc=$?
preveri "--nov-kljuc -> 0" 0 "$rc"
grep -q -- "^$B/keytool -genkeypair .*-storepass:env RELEASE_STORE_PASS -keypass:env RELEASE_KEY_PASS" "$LOG"
preveri "--nov-kljuc: keytool -genkeypair s -storepass:env/-keypass:env" 0 $?
grep -qF -- "$GESLO_KLJUCA" "$LOG"; preveri "--nov-kljuc: geslo ni v argumentih" 1 $?

# (b2) --nov-kljuc z razlicnima gesloma: PKCS12 ima eno geslo -> zavrnjeno, brez keytool
pripravi b2; pozeni "$GESLO_SHRAMBE" --nov-kljuc; rc=$?
[ "$rc" -ne 0 ]; preveri "--nov-kljuc z razlicnima gesloma -> izhod != 0" 0 $?
grep -q -- '-genkeypair' "$LOG"; preveri "--nov-kljuc z razlicnima gesloma: brez keytool" 1 $?

# (c) skladnja in shellcheck
bash -n "$SKRIPTA"; preveri "bash -n" 0 $?
if command -v shellcheck > /dev/null; then
    shellcheck -S warning "$SKRIPTA"; preveri "shellcheck -S warning" 0 $?
else
    # Testni PC nima shellcheck (25. 11. 2026); CI (STROGO=1) ga ima in tam manjkajoc pade.
    if [ "${STROGO:-0}" = "1" ]; then
        echo "  PADEL shellcheck ni namescen (STROGO=1)"; padci=$((padci + 1))
    else
        echo "  PRESKOCENO shellcheck ni namescen (v CI STROGO=1 obvezen)"
    fi
fi

if [ "$padci" -ne 0 ]; then echo "Podpis APK TV (N5): PADEL ($padci)"; exit 1; fi
echo "Podpis APK TV (N5): OK"
