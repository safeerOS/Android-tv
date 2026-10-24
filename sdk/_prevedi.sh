#!/usr/bin/env bash
# Skupno za skripte SDK: prevede orodje in primer z isto kodo protokola, kot jo uporablja aplikacija.
# KOTLINC (pot do kotlinc) ali kotlinc v PATH; potrebna Java 17.
set -euo pipefail
SDK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SDK_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
KOTLINC="${KOTLINC:-kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || { echo "Manjka kotlinc: nastavi KOTLINC=/pot/do/kotlinc" >&2; exit 2; }
# Prevedeno orodje v uporabnikovem predpomnilniku (mapa samo zanj), ne na predvidljivi poti v /tmp, kjer bi ga lahko
# drug uporabnik vnaprej podtaknil in bi se izvedel kot nase orodje.
PREDPOMNILNIK="${XDG_CACHE_HOME:-$HOME/.cache}/safeer"
SDK_JAR="${SDK_JAR:-$PREDPOMNILNIK/safeer-sdk.jar}"
mkdir -p "$(dirname "$SDK_JAR")"
[ "$(dirname "$SDK_JAR")" = "$PREDPOMNILNIK" ] && chmod 700 "$PREDPOMNILNIK"
VIRI=("$SRC/si/safeer/tv/cast/JsonLahki.kt" "$SRC/si/safeer/tv/os/DodatkiProtokol.kt" "$SRC/si/safeer/tv/os/OsPravila.kt"
      "$SRC/si/safeer/tv/os/NedelujocePravila.kt" "$SRC/si/safeer/tv/os/JezikiVsebine.kt"
      "$SDK_DIR/primer/PrimerDodatka.kt" "$SDK_DIR/orodja/PreveriDodatek.kt")
# Prevede samo, ce je kateri vir novejsi od jar.
NOVEJSI=0
for v in "${VIRI[@]}"; do [ "$v" -nt "$SDK_JAR" ] && NOVEJSI=1; done
if [ ! -f "$SDK_JAR" ] || [ "$NOVEJSI" = 1 ]; then
    # Prevede v novo datoteko in jo zamenja sele ob uspehu: neuspelo prevajanje ne pusti starega jar kot novega.
    NOV_JAR="$SDK_JAR.novo.$$.jar"
    "$KOTLINC" -J-Xmx1g "${VIRI[@]}" -include-runtime -d "$NOV_JAR" 2>&1 | grep -v '^warning:' >&2 || true
    [ -f "$NOV_JAR" ] || { echo "Prevajanje SDK ni uspelo" >&2; exit 1; }
    mv -f "$NOV_JAR" "$SDK_JAR"
fi
