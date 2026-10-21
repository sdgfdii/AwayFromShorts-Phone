#!/usr/bin/env bash
# 离线回归：不连手机、不需要 Android 运行时。
# 用法（Git Bash）：  bash test/run.sh
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
# Guard 是纯 Java，可以离线跑；其余类只要编译得过（android.jar 当 classpath）
# ShizukuBridge / BlockerService 引用了 rikka.shizuku.*，少了 libs 里的 jar 就编不过；
# 以前这里漏了，javac 的报错又被下面 grep 吞掉，整套回归其实一直在空转。
LIBS="$(ls libs/*.jar | tr '\n' "$SEP" | sed "s/${SEP}\$//")"
CP="$ANDROID_JAR${SEP}$LIBS"
find java test/java -name '*.java' > "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 -nowarn -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" -classpath "$CP" \
  -d "$OUT" @"$OUT/sources.txt" 2>&1 | grep -vE '^(warning|注意|Note)' || true

"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -cp "$ANDROID_JAR${SEP}$LIBS${SEP}$OUT" com.afs.blocker.GuardTest
