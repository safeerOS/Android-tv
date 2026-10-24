#!/usr/bin/env bash
# Zazene primer dodatka (sdk/primer/PrimerDodatka.kt) na vratih (privzeto 7000).
#   sdk/zazeni_primer.sh 7000   -> v Safeerju: Moji viri -> dolg pritisk na Dodatki (razvojni nacin) -> Dodatki ->
#                                  http://<ip tega racunalnika>:7000/manifest.json
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/_prevedi.sh"
exec java -cp "$SDK_JAR" si.safeer.tv.sdk.PrimerDodatkaKt "${1:-7000}"
