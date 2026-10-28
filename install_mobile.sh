#!/usr/bin/env bash
# ==============================================================================
# 📲 SAFEER BROWSER ZA TELEFON (Samsung Galaxy in drugi Android telefoni)
# ==============================================================================
# Pregled 28. 10. 2026: ta skripta je na telefon namescala TV brskalnik (si.safeer.tv), ki ga je zgradil zastareli
# build_mobile_apk.sh s podpisom za razvoj. Brskalnik za telefon je com.safeer.mobile.browser iz izdaj
# github.com/safeerOS/Mobile-android; namesti ga install_safeer.sh (preverba SHA-256 po imenu datoteke izdaje).
# Telefon s Safeer OS (si.safeer.phone) dobi APK iz gradnje gradnja.yml (okus telefon), podpisan s kljucem izdaje.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$DIR/install_safeer.sh" "$@"
