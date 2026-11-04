#!/usr/bin/env bash
# ==============================================================================
# Umaknjeno (tedenski pregled 4. 11. 2026, S7)
# ==============================================================================
# Skripta je bila pokvarjena: Gradle ima okuse (brskalnik, os, ...), poti build/outputs/apk/release/ ni; ime paketa je
# bilo trdo 2.1.82; privzeti kljuc je bil kljuc MOBILNEGA brskalnika (../safeer-browser/keystore/safeer-release.jks),
# s katerim TV paketa ne smemo podpisati (drug podpisnik = ni posodobitve, namestitvene skripte ga zavrnejo).
#
#   APK za TV (brskalnik in Safeer OS, kljuc TV):  ./build_tv_apk.sh
#   Preverjena gradnja v oblaku (nepodpisano):      .github/workflows/gradnja.yml
#   AAB za Google Play: se ne gradi, dokler oddaja v Play ni dolocena (docs/STORE_DISTRIBUTION.md).
echo "build_store_packages.sh je umaknjen: APK za TV zgradi ./build_tv_apk.sh (kljuc TV). Glej docs/STORE_DISTRIBUTION.md." >&2
exit 1
