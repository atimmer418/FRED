#!/bin/bash
# Spec 13.2 paycheck_shadow.sh: MANUAL kept, one SHADOW_CONFIRMED cycle, no DETECTED-only notice. Fresh user.
PAYCHECK_API_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$PAYCHECK_API_DIR/lib/sandbox.sh"
PASS=0; FAIL=0
[ "$(report_field shadowCycles)" = "0" ] || sandbox_skip "user has shadow cycles; relink or use another user."
check "report says shadow=true" [ "$(report_top shadow)" = "true" ]
dev_call POST "$PAYCHECK_DEV/sandbox/paycheck" '{"amount":2450.00,"count":6,"description":"ACME CORP PAYROLL DIR DEP","cadenceDays":14,"endDaysAgo":14}'
check "history accepted (HTTP $DEV_STATUS)" [ "$DEV_STATUS" = "200" ]
check "a primary qualifies (PROVISIONAL or CONFIRMED)" wait_for detectionState 'PROVISIONAL|CONFIRMED' 120 reconcile
dev_call POST "$PAYCHECK_DEV/sandbox/paycheck" '{"amount":2450.00,"count":1,"description":"ACME CORP PAYROLL DIR DEP","cadenceDays":14,"endDaysAgo":0}'
check "today's deposit accepted (HTTP $DEV_STATUS)" [ "$DEV_STATUS" = "200" ]
check "exactly one SHADOW_CONFIRMED cycle" wait_for shadowCycles '1' 120 reconcile
if mode=$(mysql_scalar "SELECT mode FROM investment_schedules WHERE user_id = $FRED_PAYCHECK_USER_ID"); then
  check "schedule stays MANUAL (or has no row)" [ -z "$mode" -o "$mode" = "MANUAL" ]
  notices=$(mysql_scalar "SELECT COUNT(*) FROM email_log WHERE user_id = $FRED_PAYCHECK_USER_ID AND email_type IN ($DETECTED_ONLY_TYPES)")
  check "zero DETECTED-only email_log rows" [ "$notices" = "0" ]
else
  echo "[SKIP] mode and email_log checks: FRED_MYSQL_DB is not set"
fi
echo "---"
echo "Paycheck shadow: $PASS passed, $FAIL failed"
echo "RESULT pass=$PASS fail=$FAIL skip=0"
exit $FAIL
