#!/usr/bin/env bash
# ==============================================================================
# Zastarelo (pregled 28. 10. 2026)
# ==============================================================================
# Ta skripta je s kotlinc brez Gradla gradila TV brskalnik (si.safeer.tv) za telefon, ga podpisala s kljucem za
# razvoj (uber-apk-signer, »debugSigned«) in ga kopirala v mapo spletne strani (safeer-web/assets/mobile).
# Tak APK ne sme nikoli na spletno stran ali na telefon. Zato ne gradi vec nicesar.
#
#   Safeer OS za telefon in tablico:  .github/workflows/gradnja.yml (Gradle, okusa telefon in tablica, kljuc izdaje)
#   Safeer Browser za telefon:        repozitorij safeerOS/Mobile-android (njegov build_mobile_apk.sh in izdaje)
#   Namestitev brskalnika na telefon: install_safeer.sh (preverba SHA-256)
echo "build_mobile_apk.sh je zastarel: telefon in tablica se gradita z gradnja.yml, brskalnik za telefon v Mobile-android." >&2
exit 1
