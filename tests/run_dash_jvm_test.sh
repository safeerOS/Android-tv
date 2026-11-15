#!/usr/bin/env bash
# Popravki TV-2 (15. 11. 2026): DashPrevzem.kt na JVM z nadomestki Androida (tests/dash_jvm/stubs) in lokalnim streznikom HTTPS.
# Brez popravkov (DASH_PREVZEM=<izvorna datoteka>) preizkus PADE. Potrebuje kotlinc, java in keytool (JDK 17) v PATH.
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$TEST_DIR/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
keytool -genkeypair -alias s -keyalg RSA -keysize 2048 -dname CN=bank.test -validity 2 -storetype PKCS12 \
  -keystore "$OUT/ks.p12" -storepass geslo1 -keypass geslo1 >/dev/null 2>&1
# Imeni preizkusnih gostiteljev kazeta na lokalni streznik (brez DNS).
printf '127.0.0.1 bank.test\n127.0.0.1 napadalec.test\n' > "$OUT/hosts"
kotlinc "$TEST_DIR"/dash_jvm/stubs/*.kt \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/DashVarnostPravila.kt" \
  "${DASH_PREVZEM:-$PROJECT_DIR/src/main/kotlin/si/safeer/tv/tv/DashPrevzem.kt}" \
  "$TEST_DIR/dash_jvm/DashPrevzemJvmTest.kt" -include-runtime -d "$OUT/dash.jar" 2>&1 | grep -v '^warning' || true
java -Djdk.net.hosts.file="$OUT/hosts" -cp "$OUT/dash.jar" si.safeer.tv.DashPrevzemJvmTestKt "$OUT/ks.p12"
