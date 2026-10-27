#!/usr/bin/env bash
# Uses a fake adb; never communicates with a phone.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
export MOCK_LOG="$TMP/calls" ADB="$TMP/adb"
touch "$TMP/app.apk"
cat > "$ADB" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$MOCK_LOG"
case "$*" in
  get-state) echo device ;;
  'shell getprop ro.product.model') echo test ;;
  'shell getprop ro.build.version.release') echo 7.0 ;;
  'shell getprop ro.build.version.sdk') echo 24 ;;
  'shell dpm list-owners') printf '%s\n' "$MOCK_OWNERS" ;;
  'shell dumpsys device_policy') printf '%s\n' "$MOCK_POLICY" ;;
  'shell pm list users') printf '%s\n' "$MOCK_USERS" ;;
  'shell dumpsys account') printf '%s\n' "$MOCK_ACCOUNTS" ;;
  'shell dpm remove-active-admin'*) printf '%s\n' "$MOCK_REMOVE" ;;
  'uninstall '*) echo Success ;;
  *) echo 'unexpected command' >&2; exit 1 ;;
esac
EOF
chmod +x "$ADB"
export MOCK_OWNERS='no owners' MOCK_POLICY='' MOCK_USERS='Users:
 UserInfo{0:Owner:13}' MOCK_ACCOUNTS='  Accounts: 0' MOCK_REMOVE=''

reject_without() {
  local label=$1 script=$2 forbidden=$3
  : > "$MOCK_LOG"
  if bash "$ROOT/$script" "$TMP/app.apk" >"$TMP/out" 2>&1; then
    echo "FAIL $label: unexpectedly succeeded"; cat "$TMP/out"; exit 1
  fi
  if grep -q "$forbidden" "$MOCK_LOG"; then
    echo "FAIL $label: attempted $forbidden"; cat "$MOCK_LOG"; exit 1
  fi
  echo "PASS $label"
}

MOCK_OWNERS='1 owner:
User 0: admin=other.app/.Admin,DeviceOwner'
reject_without 'other device owner' deploy.sh '^push '
MOCK_OWNERS='Unknown command: list-owners'
MOCK_POLICY='Permission Denial'
reject_without 'unreadable legacy policy' deploy.sh '^push '
MOCK_POLICY='Current Device Policy Manager state:
  Device Owner:
    admin=ComponentInfo{other.app/.Admin}
  Enabled Device Admins (User 0):'
reject_without 'other Android 7 owner' deploy.sh '^push '
MOCK_POLICY='Current Device Policy Manager state:'
reject_without 'truncated legacy policy' deploy.sh '^push '
MOCK_POLICY='Current Device Policy Manager state:
  Enabled Device Admins (User 0):'
MOCK_USERS='Permission Denial'
reject_without 'unreadable users' deploy.sh '^push '
MOCK_USERS='Users:
 UserInfo{0:Owner:13}'
MOCK_ACCOUNTS='Permission Denial'
reject_without 'unreadable accounts' deploy.sh '^push '
MOCK_ACCOUNTS='  Accounts: 1'
reject_without 'existing account' deploy.sh '^push '
MOCK_REMOVE='java.lang.SecurityException: Attempt to remove non-test admin'
reject_without 'failed admin removal' uninstall.sh '^uninstall '
