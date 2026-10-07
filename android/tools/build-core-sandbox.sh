#!/usr/bin/env bash
# Builds and unit-tests the NESTRA Remote Android :core module WITHOUT Gradle/Android SDK (sandbox / CI without network):
# the Kotlin 2.0.21 compiler bundled with Gradle 8.14.3 (/opt/gradle-8.14.3/lib) + JUnit 4.13.2 from the same folder.
# On the laptop use Gradle instead: gradlew :core:test   (same sources, same Kotlin version)
set -eu
A=$(cd "$(dirname "$0")/.." && pwd)
L=${GRADLE_LIB:-/opt/gradle-8.14.3/lib}
OUT=${1:-$A/build-sandbox}
rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"
KC="$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar"
STD="$L/kotlin-stdlib-2.0.21.jar"
JU="$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar"
kc(){ java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -Werror "$@" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true; }
kc -cp "$STD" -d "$OUT/main" $(find "$A/core/src/main/kotlin" -name '*.kt')
[ -f "$OUT/main/com/nestra/remote/core/session/RemoteSession.class" ] || { echo "core build FAILED"; exit 1; }
kc -cp "$STD:$OUT/main:$JU" -d "$OUT/test" $(find "$A/core/src/test/kotlin" -name '*.kt')
[ -d "$OUT/test/com" ] || { echo "core test build FAILED"; exit 1; }
(cd "$OUT/main" && jar cf ../nestra-remote-core.jar .)
CLASSES=$(cd "$OUT/test" && find . -name '*Tests.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort)
java -cp "$STD:$OUT/main:$OUT/test:$JU" org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS'
