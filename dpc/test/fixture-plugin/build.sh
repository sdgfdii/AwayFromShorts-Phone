#!/usr/bin/env bash
# Independent usage-stats plugin APK. Native Java 8, no Gradle/AndroidX.
# Signing parameters are supplied by the executor via AFS_KEYSTORE /
# AFS_STORE_PASS / AFS_KEY_ALIAS; no password is written to source or logs.
set -euo pipefail
cd "$(dirname "$0")"

find_sdk() {
  local c
  for c in "${AFS_SDK:-}" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
           "$HOME/.afs-android/sdk" "$HOME/AppData/Local/Android/Sdk" \
           "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "C:/AndroidSDK"; do
    [ -n "$c" ] && [ -d "$c/platforms" ] && { echo "$c"; return 0; }
  done
  return 0
}
SDK="$(find_sdk || true)"
[ -n "$SDK" ] || { echo "找不到 Android SDK（可设 AFS_SDK）" >&2; exit 1; }
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
BT="${BT%/}"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "找不到 $ANDROID_JAR" >&2; exit 1; }

tool() {
  local e
  for e in .exe .bat ""; do [ -f "$BT/$1$e" ] && { echo "$BT/$1$e"; return 0; }; done
  echo "$BT/$1"
}
AAPT2="$(tool aapt2)"
AIDL="$(tool aidl)"
D8="$(tool d8)"
ZIPALIGN="$(tool zipalign)"
APKSIGNER="$(tool apksigner)"

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(command -v javac)")")"
  export JAVA_HOME
fi
JAR="$JAVA_HOME/bin/jar.exe"
[ -f "$JAR" ] || JAR="$JAVA_HOME/bin/jar"

VERSION_CODE="${AFS_PLUGIN_VERSION_CODE:-1}"
VERSION_NAME="${AFS_PLUGIN_VERSION_NAME:-1.0.0}"
OUT="build"
rm -rf "$OUT"
mkdir -p "$OUT/flat" "$OUT/gen" "$OUT/aidlgens" "$OUT/classes" "$OUT/dex" "$OUT/res"
cp -R res/. "$OUT/res/"
if [ "${AFS_FIXTURE_READ_ONLY:-0}" = "1" ]; then sed -i '/usage.access.request/d' "$OUT/res/xml/plugin_manifest.xml"; fi
mkdir -p "$OUT/src"
for f in java/com/afs/plugin/fixture/*.java; do
  sed "s/com.afs.plugin.fixture/${AFS_FIXTURE_PACKAGE:-com.afs.plugin.fixture}/g" "$f" > "$OUT/src/$(basename "$f")"
done
sed -i "s/id=\"compat-fixture\"/id=\"${AFS_FIXTURE_ID:-compat-fixture}\"/;s/protocolMin=\"1\"/protocolMin=\"${AFS_FIXTURE_PROTOCOL:-1}\"/;s/protocolMax=\"1\"/protocolMax=\"${AFS_FIXTURE_PROTOCOL:-1}\"/;s/activity=\".FixtureActivity\"/activity=\"com.afs.plugin.fixture.FixtureActivity\"/" "$OUT/res/xml/plugin_manifest.xml"
sed "s/com.afs.plugin.fixture/${AFS_FIXTURE_PACKAGE:-com.afs.plugin.fixture}/g" AndroidManifest.xml > "$OUT/AndroidManifest.xml"
sed -i "s/com.afs.plugin.fixture.FixtureActivity/${AFS_FIXTURE_PACKAGE:-com.afs.plugin.fixture}.FixtureActivity/g" "$OUT/res/xml/plugin_manifest.xml"
sed -i "s/versionCode=\"[0-9]*\"/versionCode=\"$VERSION_CODE\"/" "$OUT/res/xml/plugin_manifest.xml"

PLUGIN_TEST_MODE=false
[ "${AFS_PLUGIN_TEST_MODE:-0}" = "1" ] && PLUGIN_TEST_MODE=true
HOST_SHA="$(tr -d '\r\n ' < ../../../dpc/release/signing-certificate.sha256)"
mkdir -p "$OUT/gen/com/afs/plugin/fixture"
cat > "$OUT/gen/com/afs/plugin/fixture/BuildFlags.java" <<EOF
package ${AFS_FIXTURE_PACKAGE:-com.afs.plugin.fixture};
public final class BuildFlags {
    public static final String HOST_PACKAGE = "com.afs.guard";
    public static final String HOST_TRUST_SHA256 = "$HOST_SHA";
    public static final boolean TEST_MODE = $PLUGIN_TEST_MODE;
    public static final int PREPARE_BEHAVIOR = ${AFS_FIXTURE_PREPARE:-0};
}
EOF

echo "[1/6] aidl"
API_AIDL="../../../plugin-api/aidl"
if command -v cygpath >/dev/null 2>&1; then
  API_ARG="$(cygpath -m "$(cd "$API_AIDL" && pwd)")"
  OUT_ARG="$(cygpath -m "$(cd "$OUT/aidlgens" && pwd)")"
  while IFS= read -r -d '' f; do
    "$AIDL" -I "$API_ARG" -o "$OUT_ARG" "$(cygpath -m "$f")"
  done < <(find "$API_AIDL" -name '*.aidl' -print0)
else
  while IFS= read -r -d '' f; do
    "$AIDL" -I "$API_AIDL" -o "$OUT/aidlgens" "$f"
  done < <(find "$API_AIDL" -name '*.aidl' -print0)
fi

echo "[2/6] aapt2"
"$AAPT2" compile --dir "$OUT/res" -o "$OUT/flat"
DEBUG_FLAG=""
[ "${AFS_RELEASE:-0}" != "1" ] && DEBUG_FLAG="--debug-mode"
"$AAPT2" link \
  -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" \
  --java "$OUT/gen" $DEBUG_FLAG --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$OUT"/flat/*.flat

echo "[3/6] javac"
SEP=";"
case "$(uname -s)" in Linux|Darwin) SEP=":" ;; esac
find "$OUT/src" "$OUT/gen" "$OUT/aidlgens" ../../../plugin-api/java -name '*.java' > "$OUT/sources.txt"
if ! javac -encoding UTF-8 -nowarn --release 8 -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2> "$OUT/javac.log"; then
  grep -v '^warning:' "$OUT/javac.log" >&2 || true
  echo "javac 失败" >&2
  exit 1
fi
grep -v '^warning:' "$OUT/javac.log" || true

echo "[4/6] d8"
"$JAR" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$D8" --lib "$ANDROID_JAR" --min-api 24 --output "$OUT/dex" "$OUT/classes.jar"

echo "[5/6] package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
pick_python() {
  for cand in ${AFS_PYTHON:-} "$(command -v python3 || true)" "$(command -v python || true)" \
              "$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe" \
              "$HOME"/.workbuddy/binaries/python/versions/*/python; do
    [ -n "$cand" ] || continue
    case "$cand" in *WindowsApps/python*) continue ;; esac
    [ -x "$cand" ] && { printf '%s' "$cand"; return 0; }
  done
  return 1
}
PY="${AFS_PYTHON:-$(pick_python || true)}"
[ -n "$PY" ] || { echo "需要 python 打包 classes.dex" >&2; exit 1; }
"$PY" - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
KS="${AFS_KEYSTORE:-../../../dpc/keystore/afs.keystore}"
ALIAS="${AFS_KEY_ALIAS:-afs}"
if [ "${AFS_RELEASE:-0}" = "1" ]; then
  : "${AFS_KEYSTORE:?Release requires an external signing keystore}"
  : "${AFS_STORE_PASS:?Release requires a private signing password}"
  : "${AFS_KEY_ALIAS:?Release requires a key alias}"
  : "${AFS_PLUGIN_APK_OUTPUT:?Release requires an explicit APK output path}"
else
  if [ ! -f "$KS" ]; then
    echo "测试签名库不存在：$KS" >&2; exit 1
  fi
fi
export AFS_STORE_PASS="${AFS_STORE_PASS:-afsguard}"
export AFS_KEY_PASS="${AFS_KEY_PASS:-$AFS_STORE_PASS}"
"$APKSIGNER" sign --ks "$KS" --ks-key-alias "$ALIAS" \
  --ks-pass env:AFS_STORE_PASS --key-pass env:AFS_KEY_PASS \
  --min-sdk-version 24 --v1-signing-enabled true --v2-signing-enabled true \
  --v3-signing-enabled true --out "$OUT/afs-plugin-fixture.apk" "$OUT/aligned.apk"
"$APKSIGNER" verify --min-sdk-version 24 "$OUT/afs-plugin-fixture.apk"
if [ "${AFS_RELEASE:-0}" = "1" ]; then
  EXPECTED="$(tr -d '\r\n ' < ../../../dpc/release/signing-certificate.sha256)"
  CERT="$("$APKSIGNER" verify --print-certs "$OUT/afs-plugin-fixture.apk" | grep -i 'certificate SHA-256 digest:' | head -1 | awk '{print $NF}')"
  [ "$CERT" = "$EXPECTED" ] || { echo "插件签名与固定发行证书不一致" >&2; exit 1; }
fi
DEST="${AFS_PLUGIN_APK_OUTPUT:-build/afs-plugin-fixture.apk}"
mkdir -p "$(dirname "$DEST")"
if [ "$DEST" != "$OUT/afs-plugin-fixture.apk" ]; then
  cp -f "$OUT/afs-plugin-fixture.apk" "$DEST"
fi
rm -f "$OUT/afs-plugin-fixture.apk.idsig"
echo "完成：$DEST"


