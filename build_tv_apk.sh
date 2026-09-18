#!/usr/bin/env bash
# ==============================================================================
# TV BROWSER 2 — Gradle + Media3 (AndroidX) build
# Signed APK lands at TV-Browser-2.apk and Release/Artifacts/tv-browser-2-release.apk
# ==============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"

# 🧪 Testni podpisni ključ Safeer Threat Intelligence se ne sme znajti v objavljeni različici
if grep -rqs '"safeer-test-' "$DIR/src/main/kotlin" --include=SignedThreatIntel.kt && [ "${SAFEER_ALLOW_TEST_KEY:-}" != "1" ]; then
    echo "❌ SignedThreatIntel.kt vsebuje TESTNI ključ (safeer-test-*). To je testna gradnja."
    echo "👉 Za testno gradnjo: SAFEER_ALLOW_TEST_KEY=1 $0   (take različice ne objavljaj)"
    exit 1
fi
# Keep SDK inside the project. Quote every use — sdkmanager cannot live under a spaced path,
# so we unpack official platform/build-tools zips instead of running sdkmanager.
SDK_DIR="${ANDROID_SDK_ROOT:-$DIR/.android-sdk}"
RELEASE_DIR="$DIR/Release/Artifacts"
WRAPPER_JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"
PLATFORM_ZIP_URL="https://dl.google.com/android/repository/platform-34-ext7_r03.zip"
BUILD_TOOLS_ZIP_URL="https://dl.google.com/android/repository/build-tools_r34-linux.zip"
WRAPPER_JAR_URL="https://raw.githubusercontent.com/gradle/gradle/v7.6.4/gradle/wrapper/gradle-wrapper.jar"

echo "=========================================================="
echo "GRADIM TV BROWSER 2 (Media3 / Gradle)"
echo "=========================================================="

mkdir -p "$RELEASE_DIR" "$DIR/gradle/wrapper"

if [[ ! -f "$WRAPPER_JAR" ]]; then
    echo "⬇️  gradle-wrapper.jar..."
    curl -fsSL "$WRAPPER_JAR_URL" -o "$WRAPPER_JAR"
fi
chmod +x "$DIR/gradlew"

ensure_sdk() {
    if [[ -f "$SDK_DIR/platforms/android-34/android.jar" && -x "$SDK_DIR/build-tools/34.0.0/aapt2" ]]; then
        return 0
    fi
    echo "⬇️  Android SDK platform 34 + build-tools 34.0.0 (zip, no sdkmanager)..."
    mkdir -p "$SDK_DIR/tmp" "$SDK_DIR/platforms" "$SDK_DIR/build-tools"
    if [[ ! -f "$SDK_DIR/tmp/platform-34.zip" ]]; then
        curl -fL "$PLATFORM_ZIP_URL" -o "$SDK_DIR/tmp/platform-34.zip"
    fi
    if [[ ! -f "$SDK_DIR/tmp/build-tools-34.zip" ]]; then
        curl -fL "$BUILD_TOOLS_ZIP_URL" -o "$SDK_DIR/tmp/build-tools-34.zip"
    fi
    local pdir bdir
    pdir="$SDK_DIR/tmp/platform-unpack"
    bdir="$SDK_DIR/tmp/build-tools-unpack"
    rm -rf "$pdir" "$bdir"
    mkdir -p "$pdir" "$bdir"
    unzip -q "$SDK_DIR/tmp/platform-34.zip" -d "$pdir"
    unzip -q "$SDK_DIR/tmp/build-tools-34.zip" -d "$bdir"
    rm -rf "$SDK_DIR/platforms/android-34"
    if [[ -d "$pdir/android-34" ]]; then
        mv "$pdir/android-34" "$SDK_DIR/platforms/android-34"
    else
        local found
        found="$(find "$pdir" -name android.jar | head -n 1)"
        if [[ -z "$found" ]]; then
            echo "platform zip nima android.jar" >&2
            find "$pdir" | head
            exit 1
        fi
        mkdir -p "$SDK_DIR/platforms/android-34"
        cp -a "$(dirname "$found")/." "$SDK_DIR/platforms/android-34/"
    fi
    if [[ ! -f "$SDK_DIR/platforms/android-34/source.properties" ]]; then
        cat > "$SDK_DIR/platforms/android-34/source.properties" <<'PROP'
Pkg.Desc=Android SDK Platform 34
Pkg.UserSrc=false
Platform.Version=14
Pkg.Revision=3
AndroidVersion.ApiLevel=34
AndroidVersion.IsBaseSdk=true
PROP
    fi
    rm -rf "$SDK_DIR/build-tools/34.0.0"
    if [[ -d "$bdir/android-14" ]]; then
        mv "$bdir/android-14" "$SDK_DIR/build-tools/34.0.0"
    elif [[ -d "$bdir/build-tools/34.0.0" ]]; then
        mv "$bdir/build-tools/34.0.0" "$SDK_DIR/build-tools/34.0.0"
    else
        local aapt
        aapt="$(find "$bdir" -name aapt2 | head -n 1)"
        if [[ -z "$aapt" ]]; then
            echo "build-tools zip nima aapt2" >&2
            find "$bdir" | head
            exit 1
        fi
        mkdir -p "$SDK_DIR/build-tools/34.0.0"
        cp -a "$(dirname "$aapt")/." "$SDK_DIR/build-tools/34.0.0/"
    fi
    chmod +x "$SDK_DIR/build-tools/34.0.0/aapt2" "$SDK_DIR/build-tools/34.0.0/d8" "$SDK_DIR/build-tools/34.0.0/zipalign" 2>/dev/null || true
    if [[ ! -f "$SDK_DIR/build-tools/34.0.0/source.properties" ]]; then
        cat > "$SDK_DIR/build-tools/34.0.0/source.properties" <<'PROP'
Pkg.Desc=Android SDK Build-Tools 34
Pkg.Revision=34.0.0
PROP
    fi
    mkdir -p "$SDK_DIR/licenses"
    echo "24333f8a63b6825ea9c5514f83c2829b004d1fee" > "$SDK_DIR/licenses/android-sdk-license"
    echo "84831b9409646161cbf74d34dc79c6c8ff54aa51" > "$SDK_DIR/licenses/android-sdk-preview-license"
}

ensure_sdk

# Public builds never package developer login credentials.
if [[ "${INCLUDE_XPLORE_AUTH:-}" == "1" ]]; then
    echo "Private login embedding is not supported by public builds." >&2
    exit 1
fi

# Relative path so spaces in the workspace directory do not break Gradle.
cat > "$DIR/local.properties" <<'EOF'
sdk.dir=.android-sdk
EOF

export ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_USER_HOME="$DIR/.android"
mkdir -p "$DIR/.android"

echo "☕ Gradle assembleRelease (Media3)..."
cd "$DIR"
python3 tests/check_public_package.py
./gradlew --no-daemon :assembleRelease

echo "✍️  Podpisujem s produkcijskim ključem za TV (keystore/safeer-tv-release.jks)..."
# Google Android Developer Console (obvezno preverjanje razvijalcev, 2027 tudi v Sloveniji) veže ime paketa na
# podpisni ključ: TV ima svoj ključ, ki nikoli ne zapusti tega računalnika (keystore/ je v .gitignore).
KEYSTORE_DIR="$DIR/keystore"
DEFAULT_KEYSTORE="$KEYSTORE_DIR/safeer-tv-release.jks"
RELEASE_KEYSTORE="${RELEASE_KEYSTORE:-$DEFAULT_KEYSTORE}"
RELEASE_KEY_ALIAS="${RELEASE_KEY_ALIAS:-safeer-tv}"
if [ -z "${RELEASE_KEY_PASS:-}" ] && [ -f "$KEYSTORE_DIR/.release_pass" ]; then
    RELEASE_KEY_PASS="$(cat "$KEYSTORE_DIR/.release_pass")"
fi
if [ -z "${RELEASE_STORE_PASS:-}" ] && [ -f "$KEYSTORE_DIR/.store_pass" ]; then
    RELEASE_STORE_PASS="$(cat "$KEYSTORE_DIR/.store_pass")"
fi
RELEASE_STORE_PASS="${RELEASE_STORE_PASS:-${RELEASE_KEY_PASS:-}}"
if [ -z "${RELEASE_KEY_PASS:-}" ]; then
    echo "❌ Geslo produkcijskega ključa (RELEASE_KEY_PASS) ni nastavljeno." >&2
    echo "👉 export RELEASE_KEY_PASS=\"...\"  ali geslo shrani v $KEYSTORE_DIR/.release_pass (v .gitignore)" >&2
    exit 1
fi
if [ ! -f "$RELEASE_KEYSTORE" ]; then
    echo "🔑 Ustvarjam produkcijski keystore za TV ($RELEASE_KEYSTORE) – shrani varnostno kopijo na USB!"
    mkdir -p "$KEYSTORE_DIR"
    keytool -genkeypair -v \
        -keystore "$RELEASE_KEYSTORE" \
        -alias "$RELEASE_KEY_ALIAS" \
        -keyalg RSA \
        -keysize 4096 \
        -validity 10000 \
        -storepass "$RELEASE_KEY_PASS" \
        -keypass "$RELEASE_KEY_PASS" \
        -dname "CN=Safeer Browser for Android TV, OU=Safeer Security, O=Safeer, L=Ljubljana, ST=Slovenia, C=SI"
fi

# Dve aplikaciji, dva APK-ja: brskalnik (si.safeer.tv) in Safeer OS (si.safeer.os).
podpisi() {   # podpisi <okus> <cilj.apk>
    local okus="$1" cilj="$2" nepodpisan
    nepodpisan="$(find "$DIR/build/outputs/apk/$okus/release" -name '*.apk' | head -n 1)"
    if [[ -z "$nepodpisan" || ! -f "$nepodpisan" ]]; then
        echo "Gradle ni naredil APK za okus $okus." >&2
        exit 1
    fi
    rm -rf "$DIR/build/signed/$okus"
    mkdir -p "$DIR/build/signed/$okus"
    java -jar "$TOOLS_DIR/uber-apk-signer.jar" \
        --apks "$nepodpisan" \
        --out "$DIR/build/signed/$okus" \
        --ks "$RELEASE_KEYSTORE" \
        --ksAlias "$RELEASE_KEY_ALIAS" \
        --ksPass "$RELEASE_STORE_PASS" \
        --ksKeyPass "$RELEASE_KEY_PASS" \
        --allowResign
    local podpisan
    podpisan="$(find "$DIR/build/signed/$okus" -name '*.apk' | head -n 1)"
    if [[ -z "$podpisan" ]]; then
        echo "Podpisani APK okusa $okus manjka." >&2
        exit 1
    fi
    cp "$podpisan" "$cilj"
}

rm -rf "$DIR/build/signed"
podpisi brskalnik "$RELEASE_DIR/tv-browser-2-release.apk"
podpisi os "$RELEASE_DIR/safeer-os-release.apk"

FINAL_APK="$RELEASE_DIR/tv-browser-2-release.apk"
cp "$FINAL_APK" "$DIR/TV-Browser-2.apk"
cp "$FINAL_APK" "$DIR/Safeer-Browser.apk"
cp "$FINAL_APK" "$RELEASE_DIR/safeer-browser-release.apk"
cp "$RELEASE_DIR/safeer-os-release.apk" "$DIR/Safeer-OS.apk"

echo "🔎 Preverjam, da je Media3 v dex..."
VERIFY_DIR="$DIR/build/dexcheck"
rm -rf "$VERIFY_DIR"
mkdir -p "$VERIFY_DIR"
unzip -qo "$DIR/TV-Browser-2.apk" "*.dex" -d "$VERIFY_DIR"
if ! grep -a -q "androidx/media3/exoplayer/ExoPlayer" "$VERIFY_DIR"/classes*.dex; then
    echo "NAPAKA: androidx.media3.exoplayer.ExoPlayer ni v APK dex. SurfaceView ne more zaživeti." >&2
    echo "Ne debugiraj Widevine, dokler ta test ne gre skozi." >&2
    ls -la "$VERIFY_DIR"
    exit 1
fi
if ! grep -a -q "androidx/media3/exoplayer/dash/DashMediaSource" "$VERIFY_DIR"/classes*.dex; then
    echo "NAPAKA: media3-exoplayer-dash ni v APK dex." >&2
    exit 1
fi
echo "OK: Media3 ExoPlayer + DashMediaSource sta v dex."

echo "🔎 Preverjam, da javni APK nima Xplore prijave..."
python3 "$DIR/tests/check_public_package.py" "$DIR/TV-Browser-2.apk"
python3 "$DIR/tests/check_public_package.py" "$DIR/Safeer-OS.apk"
echo "OK: no authentication assets in APK."

echo "🔎 Preverjam, da sta aplikaciji loceni..."
python3 "$DIR/tests/preveri_loceni_aplikaciji.py" "$DIR/TV-Browser-2.apk" "$DIR/Safeer-OS.apk"

# Kontrolne vsote: zapisemo jih tu, ob vsaki gradnji, za vse tri datoteke. Prej je SHA256SUMS
# ostajal iz stare izdaje in je za nove APK-je navajal napacno vsoto - to uporabniku sporoca, da je
# paket spremenjen ali pokvarjen, kar ni bilo res.
echo "🔎 Zapisujem kontrolne vsote (SHA256SUMS)..."
( cd "$DIR" && sha256sum TV-Browser-2.apk Safeer-Browser.apk Safeer-OS.apk > SHA256SUMS )
( cd "$DIR" && sha256sum -c --quiet SHA256SUMS )
echo "OK: SHA256SUMS ustreza zgrajenim APK-jem."

echo ""
echo "=========================================================="
echo "ZGRAJENA SIGNED APK-JA:"
echo "  brskalnik: $DIR/TV-Browser-2.apk"
echo "  Safeer OS: $DIR/Safeer-OS.apk"
echo "=========================================================="
ls -lh "$DIR/TV-Browser-2.apk" "$DIR/Safeer-OS.apk"
