#!/bin/bash
# Runs every domain test script and reports aggregate pass/fail.
# Usage: bash test/api/run-all.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FAILED_SUITES=()

run_suite() {
  local name="$1" script="$2"
  echo "=============================="
  echo "Suite: $name"
  echo "=============================="
  bash "$SCRIPT_DIR/$script"
  local code=$?
  [ $code -ne 0 ] && FAILED_SUITES+=("$name")
  echo ""
}

run_suite "Auth"           auth.sh
run_suite "User"           user.sh
run_suite "Portfolio"      portfolio.sh
run_suite "Investments"    investments.sh
run_suite "Alpaca"         alpaca.sh
run_suite "Trading"        trading.sh
run_suite "Beneficiaries"  beneficiaries.sh
run_suite "Paycheck shadow (Sandbox, self-skipping)"       paycheck_shadow.sh
run_suite "Paycheck webhooks (Sandbox, self-skipping)"     paycheck_webhooks.sh
run_suite "Paycheck lost webhook (Sandbox, self-skipping)" paycheck_lost_webhook.sh

echo "=============================="
if [ ${#FAILED_SUITES[@]} -eq 0 ]; then
  echo "ALL SUITES PASSED"
else
  echo "FAILED: ${FAILED_SUITES[*]}"
fi
echo "=============================="

[ ${#FAILED_SUITES[@]} -eq 0 ]
