#!/usr/bin/env bash
# 手工构建「打卡」APK —— 和 AFS 守卫同一套工具链（aapt2 / d8 / apksigner），不依赖 Gradle。
# 用法（Git Bash）：  bash build.sh
#
# token 从 token.local 读（一行，不入库）。没有就构建成"需要长按标题粘贴 token"的版本。
set -euo pipefail

cd "$(dirname "$0")"

find_sdk() {
  local c
  for c in "${AFS_SDK:-}" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
           "$HOME/.afs-android/sdk" \
           "$HOME/AppData/Local/Android/Sdk" "$HOME/Android/Sdk" \
           "$HOME/Library/Android/sdk" "/usr/lib/android-sdk" "C:/AndroidSDK"; do
    [ -n "$c" ] && [ -d "$c/platforms" ] && { echo "$c"; return 0; }
  done
  return 0
}
SDK="$(find_sdk || true)"
if [ -z "$SDK" ]; then
  echo "找不到 Android SDK。装一个，或设 AFS_SDK=/你的/sdk。" >&2
  exit 1
fi

BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
BT="${BT%/}"
[ -n "$BT" ] || { echo "找不到 build-tools：$SDK/build-tools" >&2; exit 1; }

ANDROID_JAR="$SDK/platforms/android-34/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "找不到 android.jar：$ANDROID_JAR" >&2; exit 1; }

tool() {
  local e
  for e in .exe .bat ""; do
    [ -f "$BT/$1$e" ] && { echo "$BT/$1$e"; return 0; }
  done
  echo "$BT/$1"
}
AAPT2="$(tool aapt2)"
D8="$(tool d8)"
ZIPALIGN="$(tool zipalign)"
APKSIGNER="$(tool apksigner)"

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(command -v javac)")")"
  export JAVA_HOME
fi
JAR="$JAVA_HOME/bin/jar.exe"
[ -f "$JAR" ] || JAR="$JAVA_HOME/bin/jar"

OUT="build"
rm -rf "$OUT"
mkdir -p "$OUT/flat" "$OUT/gen" "$OUT/classes" "$OUT/dex"

# ---------- 0. 生成 BuildConfig ----------
# token 不进 git：只从本机的 token.local 读。公开仓库拿到的源码里没有它。
TOKEN=""
if [ -f token.local ]; then
  TOKEN="$(head -1 token.local | tr -d '\r\n')"
fi
mkdir -p "$OUT/gen/com/afs/checkin"
cat > "$OUT/gen/com/afs/checkin/BuildConfig.java" <<EOF
package com.afs.checkin;

/** 构建期生成，勿手改。 */
public final class BuildConfig {
    /** 预置的 GitHub token（只授 gist 权限）。空串 = 首次启动要长按标题粘贴。 */
    public static final String PREFILL_TOKEN = "$TOKEN";
}
EOF
if [ -n "$TOKEN" ]; then echo "[0/6] token 已注入（${#TOKEN} 字符）"; else echo "[0/6] 没有 token.local，构建成需手动粘贴的版本"; fi

# ---------- 1. 编译资源 ----------
echo "[1/6] aapt2 compile"
"$AAPT2" compile --dir res -o "$OUT/flat"

# ---------- 2. 链接资源 ----------
echo "[2/6] aapt2 link"
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code 10000 \
  --version-name 1.0.0 \
  "$OUT"/flat/*.flat

# ---------- 3. 编译 Java ----------
echo "[3/6] javac"
find java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -nowarn -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v '^warning:' || true
[ -f "$OUT/classes/com/afs/checkin/MainActivity.class" ] || { echo "javac 失败" >&2; exit 1; }

# ---------- 4. dex ----------
echo "[4/6] d8"
"$JAR" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$D8" --lib "$ANDROID_JAR" --min-api 24 --output "$OUT/dex" "$OUT/classes.jar"

# ---------- 5. 打包 + 对齐 ----------
echo "[5/6] zip + zipalign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
PY="${AFS_PYTHON:-$(command -v python3 || command -v python || true)}"
[ -n "$PY" ] || { echo "需要 python 把 classes.dex 塞进 APK" >&2; exit 1; }
"$PY" - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PYEOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PYEOF
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# ---------- 6. 签名 ----------
echo "[6/6] apksigner"
KS="keystore/afs.keystore"
"$APKSIGNER" sign \
  --ks "$KS" --ks-pass pass:afsguard --key-pass pass:afsguard \
  --min-sdk-version 24 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/checkin.apk" "$OUT/aligned.apk"

"$APKSIGNER" verify --min-sdk-version 24 "$OUT/checkin.apk"
rm -f "$OUT/checkin.apk.idsig"
cp -f "$OUT/checkin.apk" ../checkin.apk

echo
echo "完成："
ls -l ../checkin.apk
