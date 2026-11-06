#!/usr/bin/env bash
# 离线回归：不连手机、不需要 Android 运行时。
# 用法（Git Bash）：  bash test/run.sh
#
# 时间判定已经是参数化的（星期掩码 + 起止分钟），多套配置都在 GuardTest 里跑，
# 不需要再 sed 改常量重编第二份。
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${AFS_SDK:-C:/Users/nj/.afs-android/sdk}"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "找不到 $ANDROID_JAR" >&2; exit 1; }

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(command -v javac)")")"
  export JAVA_HOME
fi
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
[ -f "$JAVAC" ] || JAVAC=javac
[ -f "$JAVA" ] || JAVA=java

SEP=";"
case "$(uname -s)" in
  Linux|Darwin) SEP=":" ;;
esac

OUT="build/test"
rm -rf "$OUT"
mkdir -p "$OUT" "$OUT/aidlgens"

# BuildConfig 是构建期生成的（真 token 只在本机 build.sh 里注入）。
# 离线回归用空 token 生成一份 —— 没有它 Gist 编译不过（token 现在归 Gist 管）。
PACKAGE_AIDL="../plugin-api/aidl"
if command -v cygpath >/dev/null 2>&1; then
  PACKAGE_AIDL_ARG="$(cygpath -m "$(cd "$PACKAGE_AIDL" && pwd)")"
  AIDL_OUT_ARG="$(cygpath -m "$(cd "$OUT/aidlgens" && pwd)")"
  while IFS= read -r -d '' f; do
    "$SDK/build-tools/34.0.0/aidl.exe" -I "$PACKAGE_AIDL_ARG" -o "$AIDL_OUT_ARG" "$(cygpath -m "$f")"
  done < <(find "$PACKAGE_AIDL" -name '*.aidl' -print0)
else
  while IFS= read -r -d '' f; do
    "$SDK/build-tools/34.0.0/aidl" -I "$PACKAGE_AIDL" -o "$OUT/aidlgens" "$f"
  done < <(find "$PACKAGE_AIDL" -name '*.aidl' -print0)
fi
mkdir -p "$OUT/gen/com/afs/guard"
cat > "$OUT/gen/com/afs/guard/BuildConfig.java" <<'EOF'
package com.afs.guard;

/** 离线回归用：空 token（测试不联网，也不该碰真凭据）。 */
public final class BuildConfig {
    public static final String PREFILL_TOKEN = "";
    public static final boolean PLUGIN_TEST_MODE = false;
    public static final String PLUGIN_TRUST_SHA256 = "0c047b4af90f7d980852fa089f5384dea14c39df8f4bcf816adb254e8e74be97";
    public static final boolean PLUGIN_STATS_LEGACY = false;
}
EOF
cat > "$OUT/gen/com/afs/guard/R.java" <<'EOF'
package com.afs.guard;

/** Minimal app-resource stub for offline source compilation; tests never inflate views. */
public final class R {
    public static final class drawable {
        public static final int startup_window = 0;
    }
}
EOF

find java test/java "$OUT/gen" "$OUT/aidlgens" ../plugin-api/java -name '*.java' > "$OUT/sources.txt"
if ! "$JAVAC" -encoding UTF-8 -nowarn --release 8 \
  -classpath "$ANDROID_JAR${SEP}libs/pdfbox-android-2.0.27.0.jar" -d "$OUT" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  cat "$OUT/javac.log" >&2
  exit 1
fi

# android.jar 里的 org.json 是编译桩（运行到就是 RuntimeException: Stub!）。
# 运行时挂真实现 jar（只含 org.json 包，不影响其它桩类）；
# 优先用仓库自带副本（test/libs），没有再找 gradle 缓存；
# 都找不到时跳过 —— 依赖 JSON 的纯函数测试会失败，原因自明。
JSON_JAR="$(ls test/libs/json-*.jar 2>/dev/null | head -1 || true)"
if [ -z "$JSON_JAR" ]; then
  JSON_JAR="$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.json/json/*/e*/json-*.jar 2>/dev/null | head -1 || true)"
fi
if [ -n "$JSON_JAR" ] && command -v cygpath >/dev/null 2>&1; then
  JSON_JAR="$(cygpath -w "$JSON_JAR")"   # java.exe 吃不下 /c/... 形式的路径
fi
RUN_CP="$ANDROID_JAR${SEP}$OUT"
if [ -n "$JSON_JAR" ]; then
  RUN_CP="$JSON_JAR${SEP}$RUN_CP"
fi

"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -cp "$RUN_CP" com.afs.guard.GuardTest

for TEST in ScreenLockTest PageRuleTest CheckinPenaltyTest RestReminderTest LockPlanTest UnlockCodeTest KeywordRuleTest KeyGateTest CapabilityTest PenaltyTimingTest AppUsageQuotaTest DailyQuotaTest PercentTextTest DayBoundaryTest TimeWindowTest NetWindowTest NetFallbackTest NetSlotTest ProxyRulesTest FocusPlanTest UpdateCheckTest CalendarDataTest FreezeRuleTest LedgerMergeTest MemoryTest MemoryFlashcardsTest MemoryStatsTest MemoryMomoModelTest MemorySwipeTest SpeechTest MemoryAuthoringTest TabOrderTest FreezeSlotTest QuizTest QuizImportTest DockSpringTest AppearanceTest MemoTest MemoEditorTest MemoChecklistTest MemoLibraryTest MemoBackupTest MemoTextFormatTest MemoBlocksTest MemoTableTest MemoSketchTest LearningPolicyTest CardDataTest SyncTargetTest LocalArchiveTest TranslationTest com.afs.host.plugins.PluginManifestTest com.afs.host.plugins.PluginSessionTest com.afs.host.plugins.PluginBoundsTest com.afs.host.plugins.PluginPrepareTest; do
  case "$TEST" in
    *.*) CLASS="$TEST" ;;
    *) CLASS="com.afs.guard.$TEST" ;;
  esac
  "$JAVA" -Dfile.encoding=UTF-8 -cp "$RUN_CP" "$CLASS"
done
