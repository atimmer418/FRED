#!/bin/bash
# Spec 13.2 paycheck_lost_webhook.sh: "sync":false, then only the reconcile confirms. A true lost-webhook test only without the tunnel.
PAYCHECK_API_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$PAYCHECK_API_DIR/lib/sandbox.sh"
PASS=0; FAIL=0
[ "$(report_field shadowCycles)" = "0" ] || sandbox_skip "user has shadow cycles; relink or use another user."
dev_call POST "$PAYCHECK_DEV/sandbox/paycheck" '{"amount":1900.00,"count":6,"description":"GLOBEX PAYROLL","cadenceDays":14,"endDaysAgo":14}'
check "history accepted (HTTP $DEV_STATUS)" [ "$DEV_STATUS" = "200" ]
check "a primary qualifies (PROVISIONAL or CONFIRMED)" wait_for detectionState 'PROVISIONAL|CONFIRMED' 120 reconcile
dev_call POST "$PAYCHECK_DEV/sandbox/paycheck" '{"amount":1900.00,"count":1,"description":"GLOBEX PAYROLL","cadenceDays":14,"endDaysAgo":0,"sync":false}'
check "today's deposit created without a sync" [ "$DEV_STATUS" = "200" ]
check "the response says no sync ran" [ "$(printf '%s' "$DEV_BODY" | python3 -c 'import json, sys; print(json.load(sys.stdin).get("syncStatus"))')" = "NOT_RUN" ]
check "the reconcile alone confirms one shadow cycle" wait_for shadowCycles '1' 120 reconcile
echo "---"
echo "Paycheck lost webhook: $PASS passed, $FAIL failed"
exit $FAIL
