#!/usr/bin/env bash
# Compiles and runs the JVM unit tests directly with JUnit.
# (Gradle's own test worker fails to start when the Windows user folder has non-ASCII characters.)
set -e
cd "$(dirname "$0")"
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$(pwd -W 2>/dev/null || pwd)/.gradle-tmp"
mkdir -p .gradle-tmp
./gradlew -q compileDebugUnitTestKotlin --console=plain 2>&1 | grep -v "^Picked up" || true
unset JAVA_TOOL_OPTIONS
C="$HOME/.gradle/caches/modules-2/files-2.1"
J=$(find "$C/junit/junit/4.13.2" -name "junit-4.13.2.jar" | head -1)
H=$(find "$C/org.hamcrest" -name "hamcrest-core-1.3.jar" | head -1)
K=$(find "$C/org.jetbrains.kotlin/kotlin-stdlib" -name "kotlin-stdlib-2*.jar" ! -name "*sources*" | sort | tail -1)
B=app/build/intermediates/built_in_kotlinc
SEP=":"; W() { echo "$1"; }
if command -v cygpath >/dev/null; then SEP=";"; W() { cygpath -w "$1"; }; fi
CP="$(W "$J")$SEP$(W "$H")$SEP$(W "$K")$SEP$B/debug/compileDebugKotlin/classes$SEP$B/debugUnitTest/compileDebugUnitTestKotlin/classes"
java $JAVA_OPTS -cp "$CP" org.junit.runner.JUnitCore com.playthrough.app.LogicTest com.playthrough.app.PolyPitchTest com.playthrough.app.HighNotesTest com.playthrough.app.OnsetGateTest com.playthrough.app.SynthTest "$@"
