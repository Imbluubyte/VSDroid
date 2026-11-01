#!/usr/bin/env bash
# Compiles VSDroid's pure-Kotlin core with kotlinc and runs SuperNova diagnostics/symbol scanning over a SuperNova checkout.
# usage: run.sh path/to/SuperNova/repo   (needs kotlinc + java; no Android SDK)
set -euo pipefail
SN="${1:?usage: $0 path/to/SuperNova}"
HERE="$(cd "$(dirname "$0")" && pwd)"
C="$HERE/../../app/src/main/java/com/vsdroid/ide/core"
OUT="$(mktemp -d)"
kotlinc -jvm-target 17 "$C"/Languages.kt "$C"/Diag*.kt "$C"/Symbols*.kt "$C"/Intelli*.kt "$C"/Completion.kt "$C"/TermErrors.kt "$HERE/SnTest.kt" -d "$OUT" 2>&1 | grep -v -E 'JAVA_TOOL_OPTIONS|warning' || true
KOTLIN_LIB="$(dirname "$(command -v kotlinc)")/../lib"
java -Xss8m -cp "$OUT:$KOTLIN_LIB/kotlin-stdlib.jar" SnTestKt "$SN/tests" "$SN/examples" "$SN/stdlib"
