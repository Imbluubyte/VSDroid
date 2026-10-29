#!/usr/bin/env bash
# Compiles the extension runtime (app/src/main/java/com/vsdroid/ide/core/ext) with kotlinc and runs the tests in ExtTest.kt on the JVM.
# No Android SDK needed. Requires: kotlinc, java, and a LuaJ jar (luaj-jse-3.0.x) given as the first argument or in $LUAJ_JAR.
set -euo pipefail
LUAJ="${1:-${LUAJ_JAR:-}}"
[ -f "$LUAJ" ] || { echo "usage: $0 path/to/luaj-jse.jar"; exit 2; }
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/java/com/vsdroid/ide/core"
OUT="$(mktemp -d)"
kotlinc -cp "$LUAJ" -jvm-target 17 "$SRC"/ext/*.kt "$SRC"/Languages.kt "$SRC"/Diag*.kt "$HERE/ExtTest.kt" -d "$OUT" 2>&1 | grep -v -E 'JAVA_TOOL_OPTIONS|deprecated|\^|val u = URL' || true
KOTLIN_LIB="$(dirname "$(command -v kotlinc)")/../lib"
java -Xss8m -Dexamples="$HERE/../../examples/extensions" -cp "$OUT:$LUAJ:$KOTLIN_LIB/kotlin-stdlib.jar" ExtTestKt
