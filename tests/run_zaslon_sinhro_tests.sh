#!/usr/bin/env bash
# Preizkus cistih pravil usklajenosti zvoka in slike (ZaslonSinhro) v navadnem JVM.
# Zagon: (ulimit -v 4000000; bash tests/run_zaslon_sinhro_tests.sh)
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="${SRC_DIR:-$PROJECT_DIR/src/main/kotlin}"
KOTLINC="${KOTLINC:-$HOME/android-studio/plugins/Kotlin/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}/kotlinc/bin/kotlinc"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
printf '#!/usr/bin/env bash\nexec java -XX:+UseSerialGC -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=64m -XX:CompressedClassSpaceSize=64m -Xmx512m "$@"\n' > "$OUT/java"
chmod +x "$OUT/java"
JAVACMD="$OUT/java" "$KOTLINC" "$SRC/si/safeer/tv/os/ZaslonSinhro.kt" "$TEST_DIR/ZaslonSinhroTest.kt" -include-runtime -d "$OUT/zaslon_sinhro.jar"
"$OUT/java" -Xmx256m -cp "$OUT/zaslon_sinhro.jar" si.safeer.tv.os.ZaslonSinhroTestKt
