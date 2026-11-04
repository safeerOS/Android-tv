#!/usr/bin/env bash
# SortedStringTable: pokvarjena datoteka ne sme sesuti zagona (CodeRabbit 4. 11. 2026).   KOTLINC=/pot/do/kotlinc tests/run_sorted_table_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" "$PROJECT_DIR/src/main/kotlin/com/safeer/threatfeed/SortedStringTable.kt" "$TEST_DIR/SortedStringTableTest.kt" \
    -include-runtime -d "$OUT/sst.jar"
java -Xmx512m -cp "$OUT/sst.jar" com.safeer.threatfeed.SortedStringTableTestKt
