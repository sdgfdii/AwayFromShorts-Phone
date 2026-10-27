#!/usr/bin/env bash
# 手工构建 AFS 守卫 APK —— 不依赖 Gradle，只用 SDK 里的 aapt2 / d8 / apksigner。
# 用法（Git Bash）：  bash build.sh
set -euo pipefail

cd "$(dirname "$0")"

# ---------- 环境（自动探测，别人的机器也能直接跑）----------
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
  echo "找不到 Android SDK。装一个，或设 AFS_SDK=/你的/sdk（也可用 ANDROID_HOME）。" >&2
  echo "只需要 build-tools 34 和 platforms/android-34（sdkmanager 里勾这两项）。" >&2
  exit 1
fi

# 取版本号最高的 build-tools，不写死 34.0.0
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
BT="${BT%/}"
[ -n "$BT" ] || { echo "找不到 build-tools：$SDK/build-tools" >&2; exit 1; }

ANDROID_JAR="$SDK/platforms/android-34/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "找不到 android.jar：$ANDROID_JAR" >&2
  echo "用 sdkmanager 装 platforms;android-34" >&2; exit 1; }

# Windows 上是 aapt2.exe / d8.bat / apksigner.bat，Unix 上无后缀；-x 在 Git Bash 下不可靠，用 -f
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

# ---------- 0. Public configuration ----------
# Credentials are configured per user in the app. Never embed tokens in an APK.
if [ "${AFS_PREFILL:-0}" != "0" ]; then
  echo "不再支持注入凭据；请在应用内配置个人同步。" >&2; exit 1
fi
mkdir -p "$OUT/gen/com/afs/guard"
cat > "$OUT/gen/com/afs/guard/BuildConfig.java" <<EOF
package com.afs.guard;
public final class BuildConfig {
    public static final String PREFILL_TOKEN = "";
}
EOF
VERSION_CODE="${AFS_VERSION_CODE:-2170}"
VERSION_NAME="${AFS_VERSION_NAME:-1.54.0}"
if [ "${AFS_RELEASE:-0}" = "1" ]; then
  : "${AFS_KEYSTORE:?Release requires an external signing keystore}"
  : "${AFS_STORE_PASS:?Release requires a private signing password}"
  : "${AFS_KEY_ALIAS:?Release requires a key alias}"
  : "${AFS_APK_OUTPUT:?Release requires an explicit APK output path}"
  [ -f "$AFS_KEYSTORE" ] || { echo "发行签名库不存在" >&2; exit 1; }
fi
echo "[0/7] 个人同步默认未配置 · 版本 $VERSION_NAME"

MANIFEST=AndroidManifest.xml
if [ "${AFS_RECOVERY:-0}" = "1" ]; then
  [ "${AFS_RELEASE:-0}" = "1" ] || { echo "Recovery requires verified release signing" >&2; exit 1; }
  MANIFEST="$OUT/recovery-manifest.xml"
  "${AFS_PYTHON:-python3}" - "$MANIFEST" <<'PYRECOVERY'
import sys, xml.etree.ElementTree as ET
ns="http://schemas.android.com/apk/res/android"
ET.register_namespace("android",ns)
tree=ET.parse("AndroidManifest.xml")
app=tree.getroot().find("application")
app.set("{"+ns+"}label","AFS Maintenance")
ET.SubElement(app,"receiver",{
    "{"+ns+"}name":".RecoveryReceiver",
    "{"+ns+"}exported":"true",
    "{"+ns+"}permission":"android.permission.DUMP"})
tree.write(sys.argv[1],encoding="utf-8",xml_declaration=True)
PYRECOVERY
fi

# ---------- 1. 编译资源 ----------
echo "[1/7] aapt2 compile"
"$AAPT2" compile --dir res -o "$OUT/flat"

# ---------- 2. 链接资源 ----------
echo "[2/7] aapt2 link"
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  "$OUT"/flat/*.flat

# ---------- 2b. 打包 assets（zip 里必须是正斜杠） ----------
# aapt2 在 Windows 上会把 assets 子目录写成反斜杠（assets/fonts\xiaowei.ttf），
# AssetManager 按 "fonts/xiaowei.ttf" 找不到，打卡页选内置字体会直接崩。
# 所以这里不用 aapt2 的 -A，改用 python 自己打，保证路径分隔符统一。
echo "[2b/7] pack assets"
PY="${AFS_PYTHON:-$(command -v python3 || command -v python || true)}"
if [ -z "$PY" ]; then
  echo "需要 python 来打包 assets（或用 AFS_PYTHON=/path/to/python 指定）。" >&2
  exit 1
fi
"$PY" - "$OUT/base.apk" assets <<'PYEOF'
import os, sys, zipfile
apk, root = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    existing = set(z.namelist())
    for base, dirs, files in os.walk(root):
        for name in files:
            full = os.path.join(base, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            arc = "assets/" + rel
            if arc in existing:
                continue
            z.write(full, arc)
PYEOF

# ---------- 3. 编译 Java ----------
echo "[3/7] javac"
CP_SEP=";"
case "$(uname -s)" in Linux|Darwin) CP_SEP=":" ;; esac
COMPILE_ANDROID="$ANDROID_JAR"
if command -v cygpath >/dev/null 2>&1; then COMPILE_ANDROID="$(cygpath -m "$ANDROID_JAR")"; fi
find java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# ★ javac 失败必须让构建死掉：以前管道接 grep || true, 编译错了构建照样走到
#   打包, 装上的是"没有新代码"的旧 dex（PcStats 漏 import 就这么漏装过一次）。
# --release supplies Java 8's LambdaMetafactory signatures; Android's boot stubs
# omit them. d8 below desugars lambdas for minSdk 24.
if ! javac -encoding UTF-8 -nowarn --release 8 \
  -classpath "$COMPILE_ANDROID${CP_SEP}libs/pdfbox-android-2.0.27.0.jar" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2> "$OUT/javac.log"; then
  grep -v '^warning:' "$OUT/javac.log" >&2 || true
  rm -rf "$OUT/classes"
  echo "javac 失败" >&2
  exit 1
fi
grep -v '^warning:' "$OUT/javac.log" || true

# ---------- 4. dex ----------
echo "[4/7] d8"
"$JAR" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$D8" --lib "$ANDROID_JAR" --min-api 24 --output "$OUT/dex" "$OUT/classes.jar" libs/pdfbox-android-2.0.27.0.jar

# ---------- 5. 打包 + 对齐 ----------
echo "[5/7] zip + zipalign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
if command -v zip >/dev/null 2>&1; then
  ( cd "$OUT" && zip -q -j unsigned.apk dex/classes.dex )
else
  # Git Bash 默认没有 zip，用 Python 以追加模式写入（不动已有条目）
  PY="${AFS_PYTHON:-$(command -v python3 || command -v python || true)}"
  if [ -z "$PY" ] || [ ! -x "$PY" ]; then
    echo "没有 zip 也没有 python，无法把 classes.dex 塞进 APK。" >&2
    echo "装 python3 或用 AFS_PYTHON=/path/to/python 指定。" >&2
    exit 1
  fi
  "$PY" - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PYEOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PYEOF
fi
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# ---------- 6. 签名 ----------
echo "[6/7] apksigner"
KS="${AFS_KEYSTORE:-keystore/afs.keystore}"
ALIAS="${AFS_KEY_ALIAS:-afs}"
if [ "${AFS_RELEASE:-0}" = "1" ]; then
  # An in-repository key can be accidentally published. Release signing is external.
  KEY_ABS="$(cd "$(dirname "$KS")" && pwd)/$(basename "$KS")"
  REPO_ABS="$(cd .. && pwd)"
  case "$KEY_ABS" in "$REPO_ABS"/*) echo "发行签名必须存放在仓库之外" >&2; exit 1 ;; esac
else
  mkdir -p "$(dirname "$KS")"
  if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 2048 \
      -validity 10000 -storepass afsguard -keypass afsguard \
      -dname "CN=AFS Development,O=AFS,C=CN" 2>/dev/null
  fi
  echo "  开发构建：此签名不用于公开发行"
fi
export AFS_STORE_PASS="${AFS_STORE_PASS:-afsguard}"
export AFS_KEY_PASS="${AFS_KEY_PASS:-$AFS_STORE_PASS}"
"$APKSIGNER" sign \
  --ks "$KS" --ks-key-alias "$ALIAS" --ks-pass env:AFS_STORE_PASS --key-pass env:AFS_KEY_PASS \
  --min-sdk-version 24 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/afs-guard.apk" "$OUT/aligned.apk"

"$APKSIGNER" verify --min-sdk-version 24 "$OUT/afs-guard.apk"
if [ "${AFS_RELEASE:-0}" = "1" ]; then
  EXPECTED_CERT="$(tr -d '\r\n ' < release/signing-certificate.sha256)"
  CERT_INFO="$("$APKSIGNER" verify --print-certs "$OUT/afs-guard.apk")"
  if ! printf '%s\n' "$CERT_INFO" | grep -qi "certificate SHA-256 digest: $EXPECTED_CERT$"; then
    echo "发行签名与固定证书不一致，停止输出" >&2; exit 1
  fi
fi
rm -f "$OUT/afs-guard.apk.idsig"
DEST="${AFS_APK_OUTPUT:-../afs-guard.apk}"
mkdir -p "$(dirname "$DEST")"
cp -f "$OUT/afs-guard.apk" "$DEST"

echo
echo "完成："
ls -l "$DEST"
