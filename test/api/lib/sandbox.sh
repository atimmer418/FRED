#!/bin/bash
# Paycheck Autopilot Sandbox guard (spec 13.2). Prints [SKIP] and exits 0 unless FRED_API_BASE is set, the
# dev shadow report answers 200 from a non-ci profile, and FRED_PAYCHECK_USER_ID / _PUBLIC_ID are set.

# Every exit prints the RESULT line run-all.sh reads (see finish_suite in helpers.sh).
sandbox_skip() {
  echo "[SKIP] $1"
  echo "RESULT pass=${PASS:-0} fail=${FAIL:-0} skip=1"
  exit 0
}

[ -n "$FRED_API_BASE" ] || sandbox_skip "FRED_API_BASE is not set."
command -v python3 >/dev/null 2>&1 || sandbox_skip "python3 is not installed."
DEV_TOOLS_KEY="${DEV_TOOLS_KEY:-ci-local-dev-tools}"
sandbox_headers=$(mktemp)
sandbox_status=$(curl -s -o /dev/null -D "$sandbox_headers" -w "%{http_code}" \
  -H "X-Dev-Tools-Key: $DEV_TOOLS_KEY" "$FRED_API_BASE/api/dev/paycheck/shadow-report")
sandbox_profiles=$(grep -i '^x-fred-profiles:' "$sandbox_headers" | cut -d: -f2- | tr -d ' \r\n')
rm -f "$sandbox_headers"
[ "$sandbox_status" = "200" ] || sandbox_skip "dev shadow report answered HTTP $sandbox_status."
[ -n "$sandbox_profiles" ] || sandbox_skip "no X-Fred-Profiles header."
case ",$sandbox_profiles," in
  *,ci,*) sandbox_skip "ci profile: PlaidApi is the offline CiStubConfig proxy." ;;
esac
[[ "$FRED_PAYCHECK_USER_ID" =~ ^[0-9]+$ ]] || sandbox_skip "FRED_PAYCHECK_USER_ID is not set to a user id."
[ -n "$FRED_PAYCHECK_PUBLIC_ID" ] || sandbox_skip "FRED_PAYCHECK_PUBLIC_ID is not set."

PAYCHECK_DEV="/api/dev/paycheck/$FRED_PAYCHECK_USER_ID"
DETECTED_ONLY_TYPES="'PAYDAY_D1_DETECTED','PAYCHECK_FOUND','SKIP_AFFORDABILITY','SKIP_CATCHUP','SKIP_HOLD_ACH','SKIP_HOLD','SKIP_NO_AUTH','DORMANT','INTERRUPTION','WELCOME_BACK','NEW_EMPLOYER','CADENCE_CHANGE','DORMANT_120','AUTHORIZATION_NEEDED'"

dev_call() {
  local method="$1" path="$2" body="${3:-}" tmp
  tmp=$(mktemp)
  local args=(-s -o "$tmp" -w "%{http_code}" -X "$method" "$FRED_API_BASE$path" -H "X-Dev-Tools-Key: $DEV_TOOLS_KEY" -H "Content-Type: application/json")
  [ -n "$body" ] && args+=(-d "$body")
  DEV_STATUS=$(curl "${args[@]}")
  DEV_BODY=$(cat "$tmp")
  rm -f "$tmp"
}

admin_call() {
  [ -n "$ADMIN_TOOLS_KEY" ] || return 1
  local tmp
  tmp=$(mktemp)
  DEV_STATUS=$(curl -s -o "$tmp" -w "%{http_code}" -X "$1" "$FRED_API_BASE$2" \
    -H "X-Admin-Key: $ADMIN_TOOLS_KEY" -H "X-Admin-Actor: ${FRED_ADMIN_ACTOR:-sandbox-script}")
  DEV_BODY=$(cat "$tmp")
  rm -f "$tmp"
}

report_field() {
  dev_call GET "/api/dev/paycheck/shadow-report"
  printf '%s' "$DEV_BODY" | python3 -c '
import json, sys
rows = [u for u in json.load(sys.stdin).get("users", []) if u.get("publicId") == sys.argv[2]]
value = rows[0].get(sys.argv[1]) if rows else None
print(len(value) if isinstance(value, list) else ("" if value is None else value))
' "$1" "$FRED_PAYCHECK_PUBLIC_ID"
}

report_top() {
  dev_call GET "/api/dev/paycheck/shadow-report"
  printf '%s' "$DEV_BODY" | python3 -c 'import json, sys; print(str(json.load(sys.stdin).get(sys.argv[1])).lower())' "$1"
}

# wait_for NAME REGEX SECONDS [reconcile]: poll the report every 5 s; report_field prints a list's length.
wait_for() {
  local name="$1" pattern="$2" seconds="$3" nudge="${4:-}" waited=0 value=""
  while [ "$waited" -le "$seconds" ]; do
    [ "$nudge" = "reconcile" ] && dev_call POST "$PAYCHECK_DEV/reconcile"
    value=$(report_field "$name")
    [[ "$value" =~ ^($pattern)$ ]] && return 0
    sleep 5
    waited=$((waited + 5))
  done
  echo "  last $name=$value"
  return 1
}

check() {
  local label="$1"
  shift
  if "$@"; then echo "[PASS] $label"; PASS=$((PASS + 1)); else echo "[FAIL] $label"; FAIL=$((FAIL + 1)); fi
}

# mysql_scalar SQL: returns 1 without FRED_MYSQL_DB; password from MYSQL_PWD or ~/.my.cnf, never an argument.
mysql_scalar() {
  [ -n "$FRED_MYSQL_DB" ] || return 1
  mysql -N -B -u "${FRED_MYSQL_USER:-root}" -h "${FRED_MYSQL_HOST:-127.0.0.1}" "$FRED_MYSQL_DB" -e "$1"
}
