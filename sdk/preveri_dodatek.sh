#!/usr/bin/env bash
# Preveri dodatek po protokolu Safeer v1 (sdk/orodja/PreveriDodatek.kt).
#   sdk/preveri_dodatek.sh https://moj-dodatek.example/manifest.json [--vsebina movie:tt0111161] [--dejanja]
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/_prevedi.sh"
exec java -cp "$SDK_JAR" si.safeer.tv.sdk.PreveriDodatekKt "$@"
