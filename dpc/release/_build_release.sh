#!/usr/bin/env bash
# 等价于 build-release.ps1（本机执行策略禁止直接跑 .ps1）。
# 用法： bash dpc/release/_build_release.sh <version> <versionCode> <maintenanceCode>
set -euo pipefail
ver="${1:?version}"
code="${2:?versionCode}"
mcode="${3:?maintenanceVersionCode}"
step="${4:-all}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
cfg="${LOCALAPPDATA:-$HOME/AppData/Local}/AwayFromShorts/Signing/phone-release-v1.json"
[ -f "$cfg" ] || { echo "signing config missing: $cfg" >&2; exit 1; }

eval "$(python - "$cfg" <<'PY'
import json, sys, shlex
d = json.load(open(sys.argv[1], encoding='utf-8'))
print('KS=' + shlex.quote(d['keystore'].replace('\\', '/')))
print('ALIAS=' + shlex.quote(d['alias']))
print('PASS=' + shlex.quote(d['password']))
PY
)"

sdk="${AFS_SDK:-$HOME/.afs-android/sdk}"
py="$(command -v python)"
out="$root/dist/$ver"
mkdir -p "$out/maintenance"

export AFS_SDK="$sdk"
export AFS_PYTHON="$py"
export AFS_PREFILL=0
export AFS_RELEASE=1
export AFS_KEYSTORE="$KS"
export AFS_KEY_ALIAS="$ALIAS"
export AFS_STORE_PASS="$PASS"
export AFS_KEY_PASS="$PASS"
export AFS_VERSION_NAME="$ver"

echo "== release APK =="
if [ "${step}" = "all" ] || [ "${step}" = "release" ]; then
  export AFS_RECOVERY=0
  export AFS_VERSION_CODE="$code"
  export AFS_APK_OUTPUT="$out/afs-guard.apk"
  bash dpc/build.sh
fi

if [ "${step}" = "all" ] || [ "${step}" = "maint" ]; then
  echo "== maintenance APK =="
  export AFS_RECOVERY=1
  export AFS_VERSION_CODE="$mcode"
  export AFS_APK_OUTPUT="$out/maintenance/afs-recovery.apk"
  bash dpc/build.sh
fi

if [ "${step}" = "all" ] || [ "${step}" = "package" ] || [ "${step}" = "maint" ]; then
  echo "== package =="
  python dpc/release/package_release.py --sdk "$sdk" \
    --version "$ver" --version-code "$code" --maintenance-version-code "$mcode"
fi
