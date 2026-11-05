#!/bin/bash
# Spec 13.2 paycheck_webhooks.sh (Sandbox part). Destructive half only with FRED_PAYCHECK_DESTRUCTIVE=1.
PAYCHECK_API_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$PAYCHECK_API_DIR/lib/sandbox.sh"
PASS=0; FAIL=0
fire() { dev_call POST "$PAYCHECK_DEV/sandbox/fire" "{\"code\":\"$1\"}"; [ "$DEV_STATUS" = "200" ]; }
no_failed_inbox_rows() {
  admin_call GET "/api/admin/paycheck/webhooks?status=FAILED" || return 1
  [ "$DEV_STATUS" = "200" ] && [ "$(printf '%s' "$DEV_BODY" | python3 -c 'import json, sys; print(len(json.load(sys.stdin)))')" = "0" ]
}
check "RECURRING before SYNC fired" fire RECURRING_TRANSACTIONS_UPDATE
check "SYNC_UPDATES_AVAILABLE fired" fire SYNC_UPDATES_AVAILABLE
check "SYNC_UPDATES_AVAILABLE fired again" fire SYNC_UPDATES_AVAILABLE
sleep 45
check "Item still OK" [ "$(report_field itemStatus)" = "OK" ]
if [ -n "$ADMIN_TOOLS_KEY" ]; then check "no FAILED inbox rows" no_failed_inbox_rows; else echo "[SKIP] FAILED inbox check: ADMIN_TOOLS_KEY is not set"; fi
if [ "$FRED_PAYCHECK_DESTRUCTIVE" = "1" ]; then
  dev_call POST "$PAYCHECK_DEV/sandbox/reset-login"
  check "reset_login accepted (HTTP $DEV_STATUS)" [ "$DEV_STATUS" = "200" ]
  check "ITEM ERROR gives LOGIN_REQUIRED" wait_for itemStatus 'LOGIN_REQUIRED' 90
  check "LOGIN_REPAIRED fired" fire LOGIN_REPAIRED
  check "LOGIN_REPAIRED gives OK" wait_for itemStatus 'OK' 90
  check "USER_PERMISSION_REVOKED fired" fire USER_PERMISSION_REVOKED
  check "revocation gives REVOKED" wait_for itemStatus 'REVOKED' 90
  dev_call POST "$PAYCHECK_DEV/sandbox/fire" '{"code":"LOGIN_REPAIRED"}'
  if [ "$DEV_STATUS" = "200" ]; then
    sleep 30
    check "a trailing LOGIN_REPAIRED is a no-op" [ "$(report_field itemStatus)" = "REVOKED" ]
  else
    echo "[SKIP] trailing LOGIN_REPAIRED: Plaid refused it (HTTP $DEV_STATUS), the Item is removed"
  fi
else
  echo "[SKIP] destructive half: set FRED_PAYCHECK_DESTRUCTIVE=1 on a throwaway user"
fi
echo "---"
echo "Paycheck webhooks: $PASS passed, $FAIL failed"
exit $FAIL
