#!/usr/bin/env bash
# Vsi preizkusi, ki tecejo brez naprave in brez gradnje APK (naloga 111): vsi tests/run_*.sh, staticne preverbe v Pythonu
# in preizkusi JS (ce je node). Kar manjka (kotlinc, org.json, android.jar, node), je jasno izpisano kot PRESKOCENO z
# razlogom - nikoli tih padec. Na koncu povzetek; izhod 1, ce kaj PADE (STROGO=1: tudi, ce je kaj preskoceno).
#
#   KOTLINC=/pot/do/kotlinc JSON_JAR=/pot/do/json.jar tests/vse.sh
#   SAFEER_TOOLS_DIR=/pot/do/.tools tests/vse.sh        # kotlinc/bin/kotlinc in json-*.jar v tej mapi
#
# Ne tece tu: preizkusi na napravi (preizkus_os_na_tv.py, preizkus_zaganjalnik.py - adb), preveri_loceni_aplikaciji.py
# (zgrajena APK; tece v CI za gradnjo) in PomnilnikDomenTest.kt (meritev pomnilnika, ne preizkus).
set -uo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
STROGO="${STROGO:-0}"

# ------------------------------------------------------------------ orodja
if [ -z "${KOTLINC:-}" ]; then
    if [ -x "$TOOLS_DIR/kotlinc/bin/kotlinc" ]; then KOTLINC="$TOOLS_DIR/kotlinc/bin/kotlinc"
    elif command -v kotlinc >/dev/null 2>&1; then KOTLINC="$(command -v kotlinc)"
    else KOTLINC=""; fi
fi
if [ -z "${JSON_JAR:-}" ]; then
    JSON_JAR="$( { ls "$TOOLS_DIR"/json-*.jar "$TOOLS_DIR"/org.json/json-*.jar \
        "$HOME"/.gradle/caches/modules-2/files-2.1/org.json/json/*/*/json-*.jar 2>/dev/null || true; } | grep -v sources | head -1)"
fi
ANDROID_JAR="$( { ls "$PROJECT_DIR"/.android-sdk/platforms/android-3*/android.jar \
    "${ANDROID_HOME:-/nonexistent}"/platforms/android-3*/android.jar "${ANDROID_SDK_ROOT:-/nonexistent}"/platforms/android-3*/android.jar 2>/dev/null || true; } | head -1)"
export KOTLINC JSON_JAR

echo "== orodja"
echo "  kotlinc:  ${KOTLINC:-MANJKA}"
echo "  java:     $(command -v java || echo MANJKA)"
echo "  org.json: ${JSON_JAR:-MANJKA}"
echo "  android:  ${ANDROID_JAR:-MANJKA}"
echo "  python3:  $(command -v python3 || echo MANJKA)"
echo "  node:     $(command -v node || echo MANJKA)"

DNEVNIKI="$(mktemp -d)"
POVZETEK=()
PADLO=0
PRESKOCENO=0

zabelezi() { POVZETEK+=("$(printf '%-10s %-40s %s' "$1" "$2" "$3")"); }

pozeni() {   # ime ukaz...
    local ime="$1"; shift
    local zac; zac=$(date +%s)
    if ( cd "$PROJECT_DIR" && "$@" ) > "$DNEVNIKI/$ime.log" 2>&1; then
        zabelezi "OK" "$ime" "$(( $(date +%s) - zac )) s"
    else
        PADLO=$((PADLO + 1))
        zabelezi "PADLO" "$ime" "dnevnik: $DNEVNIKI/$ime.log"
        tail -5 "$DNEVNIKI/$ime.log" | sed 's/^/    /'
    fi
}

preskoci() { PRESKOCENO=$((PRESKOCENO + 1)); zabelezi "PRESKOCENO" "$1" "$2"; }

# ------------------------------------------------------------------ Kotlin (run_*.sh)
echo "== preizkusi Kotlin"
for skripta in "$TEST_DIR"/run_*.sh; do
    ime="$(basename "$skripta" .sh)"
    if [ -z "$KOTLINC" ] || ! command -v java >/dev/null 2>&1; then preskoci "$ime" "manjka kotlinc ali java"; continue; fi
    if grep -q 'JSON_JAR' "$skripta" && [ -z "$JSON_JAR" ]; then preskoci "$ime" "manjka org.json (JSON_JAR)"; continue; fi
    if grep -q 'android.jar' "$skripta" && [ -z "$ANDROID_JAR" ]; then preskoci "$ime" "manjka android.jar (Android SDK)"; continue; fi
    echo "  $ime"
    pozeni "$ime" bash "$skripta"
done

# ------------------------------------------------------------------ staticne preverbe (Python)
echo "== staticne preverbe"
for p in check_public_package.py preveri_dostop_v_ozadju.py preveri_naslovno_vrstico.py preveri_tv_ikone.py preveri_dnevnik.py; do
    [ -f "$TEST_DIR/$p" ] || continue
    if ! command -v python3 >/dev/null 2>&1; then preskoci "$p" "manjka python3"; continue; fi
    pozeni "$p" python3 "$TEST_DIR/$p"
done

# ------------------------------------------------------------------ JS
echo "== preizkusi JS"
for js in "$TEST_DIR"/*_test.js; do
    ime="$(basename "$js")"
    if ! command -v node >/dev/null 2>&1; then preskoci "$ime" "manjka node"; continue; fi
    pozeni "$ime" node "$js"
done

echo
echo "== povzetek"
printf '%s\n' "${POVZETEK[@]}"
echo "padlo: $PADLO, preskoceno: $PRESKOCENO"
if [ "$PADLO" -gt 0 ]; then echo "NEKAJ JE PADLO"; exit 1; fi
if [ "$STROGO" = "1" ] && [ "$PRESKOCENO" -gt 0 ]; then echo "STROGO: preskocenih ne sme biti"; exit 1; fi
echo "VSE OK (preskocenih $PRESKOCENO)"
