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
mkdir -p "$OUT"

# BuildConfig 是构建期生成的（真 token 只在本机 build.sh 里注入）。
# 离线回归用空 token 生成一份 —— 没有它 Gist 编译不过（token 现在归 Gist 管）。
mkdir -p "$OUT/gen/com/afs/guard"
cat > "$OUT/gen/com/afs/guard/BuildConfig.java" <<'EOF'
package com.afs.guard;

/** 离线回归用：空 token（测试不联网，也不该碰真凭据）。 */
public final class BuildConfig {
    public static final String PREFILL_TOKEN = "";
}
EOF

find java test/java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 -nowarn -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
  -d "$OUT" @"$OUT/sources.txt" 2>&1 | grep -vE '^(warning|注意|Note)' || true

# android.jar 里的 org.json 是编译桩（运行到就是 RuntimeException: Stub!）。
# 运行时挂 gradle 缓存里的真实现 jar（只含 org.json 包，不影响其它桩类）；
# 找不到时跳过 —— 依赖 JSON 的纯函数测试会失败，原因自明。
JSON_JAR="$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.json/json/*/e*/json-*.jar 2>/dev/null | head -1 || true)"
if [ -n "$JSON_JAR" ] && command -v cygpath >/dev/null 2>&1; then
  JSON_JAR="$(cygpath -w "$JSON_JAR")"   # java.exe 吃不下 /c/... 形式的路径
fi
RUN_CP="$ANDROID_JAR${SEP}$OUT"
if [ -n "$JSON_JAR" ]; then
  RUN_CP="$JSON_JAR${SEP}$RUN_CP"
fi

"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -cp "$RUN_CP" com.afs.guard.GuardTest
