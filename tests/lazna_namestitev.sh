#!/usr/bin/env bash
# Skupni del run_install_tests.sh in run_install_tv_tests.sh (neodvisni pregled 4. 11. 2026 09:10): namestitveno skripto
# pozenemo kot LOCEN PROCES (bash skripta, ne source) z laznimi curl, adb, keytool in apksigner. Lazna orodja so prva v
# PATH, okolje je prazno (env -i) - prava namestitev na povezano napravo ni mogoca, tudi ce preizkus tece na stari
# skripti brez varovala za source (zapisnik: dvakrat se je zgodila prava namestitev).
# Klicatelj ima preveri() in pogoj(). Ni run_*.sh, zato ga vse.sh ne pozene samostojno.
# shellcheck disable=SC2317

# $1 = mapa, $2 = JSON izdaje (curl api.github.com), $3 = ime APK v SHA256SUMS
pripravi_lazna_orodja() {
    local m="$1"
    mkdir -p "$m/bin" "$m/dom" "$m/delo" "$m/pot"
    printf '%s' "$2" > "$m/izdaja.json"
    printf 'lazen apk' > "$m/apk"
    printf '%s  %s\n' "$(sha256sum "$m/apk" | awk '{print $1}')" "$3" > "$m/SHA256SUMS"
    cat > "$m/bin/curl" <<'SH'
#!/usr/bin/env bash
o=""; url=""
while [ $# -gt 0 ]; do case "$1" in -o) o="$2"; shift ;; https://*) url="$1" ;; esac; shift; done
case "$url" in
    *api.github.com*) cat "$LAZNA_MAPA/izdaja.json" ;;
    *SHA256SUMS) cat "$LAZNA_MAPA/SHA256SUMS" > "$o" ;;
    *) cat "$LAZNA_MAPA/apk" > "$o" ;;
esac
SH
    cat > "$m/bin/adb" <<'SH'
#!/usr/bin/env bash
echo "adb $*" >> "$LAZNA_MAPA/adb.log"
if [ "${1:-}" = devices ]; then printf 'List of devices attached\nNAPRAVA\tdevice\n'; fi
exit 0
SH
    # keytool vidi samo podpis v1: tu vedno »Safeer« (napadalec ohrani v1 in doda svoj v2).
    cat > "$m/bin/keytool" <<'SH'
#!/usr/bin/env bash
printf 'Signer #1:\n\tSHA256: %s\n' "$(printf '%s' "$LAZNI_V1" | sed 's/../&:/g; s/:$//')"
SH
    cat > "$m/apksigner" <<'SH'
#!/usr/bin/env bash
cat "$LAZNA_MAPA/apksigner.izpis"
exit "$(cat "$LAZNA_MAPA/apksigner.rc")"
SH
    chmod +x "$m/bin/"* "$m/apksigner"
}

# $1 = mapa, $2 = kje je apksigner (sdk | pot | brez), $3 = izpis apksigner, $4 = izhod apksigner, $5 = skripta
pozeni_lazno() {
    local m="$1"
    rm -rf "$m/sdk" "$m/pot"/*; : > "$m/adb.log"; rm -f "$m/delo"/*
    printf '%b' "$3" > "$m/apksigner.izpis"; printf '%s' "$4" > "$m/apksigner.rc"
    case "$2" in
        sdk) mkdir -p "$m/sdk/build-tools/36.0.0"; cp "$m/apksigner" "$m/sdk/build-tools/36.0.0/apksigner" ;;
        pot) cp "$m/apksigner" "$m/pot/apksigner" ;;
    esac
    ( cd "$m/delo" && env -i PATH="$m/bin:$m/pot:/usr/bin:/bin" HOME="$m/dom" ANDROID_HOME="$m/sdk" \
        LAZNA_MAPA="$m" LAZNI_V1="$LAZNI_V1" bash "$5" ) > "$m/izpis" 2>&1
}

# $1 = skripta, $2 = JSON izdaje, $3 = ime APK, $4 = Safeerjev odtis
preizkusi_podpisnika() {
    local skripta="$1" m safeer tuj
    m="$(mktemp -d)"
    pripravi_lazna_orodja "$m" "$2" "$3"
    safeer="$(printf '%s' "$4" | tr 'A-F' 'a-f')"
    tuj="$(printf '%064d' 7)"
    LAZNI_V1="$4"
    local ok="Verifies\nSigner #1 certificate DN: CN=Safeer\nSigner #1 certificate SHA-256 digest: $safeer\n"

    pozeni_lazno "$m" sdk "$ok" 0 "$skripta"
    preveri "apksigner Safeer -> 0" 0 $?
    grep -q "adb -s NAPRAVA install -r" "$m/adb.log"; preveri "apksigner Safeer: namesti" 0 $?

    if [ -x /usr/bin/apksigner ]; then
        echo "  PRESKOCENO brez apksignerja (/usr/bin/apksigner je namescen)"
    else
        pozeni_lazno "$m" brez "" 0 "$skripta"
        preveri "brez apksignerja (keytool v1 = Safeer) -> 1" 1 $?
        ! grep -q " install " "$m/adb.log"; preveri "brez apksignerja: ni namestitve" 0 $?
        grep -q "potreben apksigner" "$m/izpis"; preveri "brez apksignerja: jasno sporocilo" 0 $?
        pozeni_lazno "$m" pot "$ok" 0 "$skripta"
        preveri "apksigner samo v PATH -> 1 (absolutna pot)" 1 $?
        ! grep -q " install " "$m/adb.log"; preveri "apksigner v PATH: ni namestitve" 0 $?
    fi
    pozeni_lazno "$m" sdk "Verifies\nSigner #1 certificate SHA-256 digest: $tuj\n" 0 "$skripta"
    preveri "v2 tuj, v1 Safeer -> 1" 1 $?
    ! grep -q " install " "$m/adb.log"; preveri "v2 tuj: ni namestitve" 0 $?
    pozeni_lazno "$m" sdk "${ok}Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: $tuj\n" 0 "$skripta"
    preveri "dodaten podpisnik v3.1 -> 1" 1 $?
    ! grep -q " install " "$m/adb.log"; preveri "dodaten podpisnik: ni namestitve" 0 $?
    pozeni_lazno "$m" sdk "DOES NOT VERIFY\n$ok" 1 "$skripta"
    preveri "apksigner ne preveri (izhod 1) -> 1" 1 $?
    ! grep -q " install " "$m/adb.log"; preveri "ne preveri: ni namestitve" 0 $?
    rm -rf "$m"
}
