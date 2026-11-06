#!/usr/bin/env bash
# R2 plugin protocol offline tests. Does not need a device or the full host.
# Usage: bash test/run-plugin-offline.sh
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${AFS_SDK:-C:/Users/nj/.afs-android/sdk}"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "找不到 $ANDROID_JAR" >&2; exit 1; }
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(command -v javac)")")"
  export JAVA_HOME
fi
JAVAC="$JAVA_HOME/bin/javac"; [ -f "$JAVAC" ] || JAVAC=javac
JAVA="$JAVA_HOME/bin/java"; [ -f "$JAVA" ] || JAVA=java
OUT="build/plugin-test"
rm -rf "$OUT"
mkdir -p "$OUT"

cat > "$OUT/sources.txt" <<'EOF'
../plugin-api/java/com/afs/pluginapi/PluginProtocol.java
../plugin-api/java/com/afs/pluginapi/PluginStatus.java
../plugin-api/java/com/afs/pluginapi/ParcelSafe.java
../plugin-api/java/com/afs/pluginapi/DisplayUsage.java
../plugin-api/java/com/afs/pluginapi/TrendDay.java
../plugin-api/java/com/afs/pluginapi/PcPeriodSummary.java
../plugin-api/java/com/afs/pluginapi/UsageSummary.java
../plugin-api/java/com/afs/pluginapi/LaunchTicket.java
../plugin-api/java/com/afs/pluginapi/PageLease.java
java/com/afs/host/plugins/PluginFingerprint.java
java/com/afs/host/plugins/PluginManifestParser.java
java/com/afs/host/plugins/PrepareDeadline.java
test/java/com/afs/host/plugins/PluginManifestTest.java
test/java/com/afs/host/plugins/PluginSessionTest.java
test/java/com/afs/host/plugins/PluginBoundsTest.java
test/java/com/afs/host/plugins/PluginPrepareTest.java
EOF
if ! "$JAVAC" -encoding UTF-8 -nowarn --release 8 -classpath "$ANDROID_JAR" -d "$OUT" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  cat "$OUT/javac.log" >&2
  exit 1
fi
SEP=";"
case "$(uname -s)" in Linux|Darwin) SEP=":" ;; esac
"$JAVA" -Dfile.encoding=UTF-8 -cp "$ANDROID_JAR${SEP}$OUT" com.afs.host.plugins.PluginManifestTest
"$JAVA" -Dfile.encoding=UTF-8 -cp "$ANDROID_JAR${SEP}$OUT" com.afs.host.plugins.PluginSessionTest
"$JAVA" -Dfile.encoding=UTF-8 -cp "$ANDROID_JAR${SEP}$OUT" com.afs.host.plugins.PluginBoundsTest
"$JAVA" -Dfile.encoding=UTF-8 -cp "$ANDROID_JAR${SEP}$OUT" com.afs.host.plugins.PluginPrepareTest
