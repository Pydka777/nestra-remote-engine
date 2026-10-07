#!/usr/bin/env bash
# ETAP 8+9 real-backend gate (sandbox / Linux): builds the Kotlin :core + the integration client, then runs the .NET
# server test suite with AndroidCoreIntegrationTests REQUIRED (real NestraRemote.Server + AccountBridge + Parent
# login/MFA contract host, real HTTP, real laptop agent over WebSocket).
set -eu
A=$(cd "$(dirname "$0")/.." && pwd); R=$(cd "$A/.." && pwd)
L=${GRADLE_LIB:-/opt/gradle-8.14.3/lib}
OUT=${1:-$(mktemp -d)}
bash "$A/tools/build-core-sandbox.sh" "$OUT" | tail -3
KC="$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar"
mkdir -p "$OUT/it"
java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -Werror \
  -cp "$L/kotlin-stdlib-2.0.21.jar:$OUT/nestra-remote-core.jar" -d "$OUT/it" $(find "$A/integration/src/main/kotlin" -name '*.kt') 2>&1 | grep -v '^Picked up' || true
[ -f "$OUT/it/com/nestra/remote/it/CoreIntegrationKt.class" ] || { echo "integration client build FAILED"; exit 1; }
export NESTRA_ANDROID_CORE_CLASSPATH="$L/kotlin-stdlib-2.0.21.jar:$OUT/nestra-remote-core.jar:$OUT/it"
export NESTRA_ANDROID_CORE_REQUIRED=1
export DOTNET_NOLOGO=1 DOTNET_CLI_TELEMETRY_OPTOUT=1
(cd "$R/server" && dotnet build NestraRemote.sln --no-incremental -v q 2>&1 | grep -E ' error |rror\(s\)' | tail -2)
cd "$R"
dotnet test server/tests/NestraRemote.Server.Tests/NestraRemote.Server.Tests.csproj --no-build 2>&1 | grep -v '^{' | tee "$OUT/dotnet-test.log" | grep -E '^(PASS|FAIL)  |ANDROID CORE INTEGRATION|Passed!|Failed!|Failed ' 
grep -q 'ANDROID CORE INTEGRATION: GREEN' "$OUT/dotnet-test.log" && grep -q '^Passed!' "$OUT/dotnet-test.log" && echo "ETAP 8+9 ANDROID CORE REAL-BACKEND GATE: GREEN" || { echo "ETAP 8+9 ANDROID CORE REAL-BACKEND GATE: RED"; exit 1; }
