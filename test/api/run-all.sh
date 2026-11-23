#!/bin/bash
# Runs every domain test script and reports aggregate pass/fail.
# Usage: bash test/api/run-all.sh
#
# A suite counts as passed only if it exits 0, prints its RESULT line (see finish_suite in
# lib/helpers.sh) and ran at least one request. This runner once printed "ALL SUITES PASSED" for
# months while every suite failed to load its helpers and sent nothing, because "0 failed" looked
# the same as "0 run". Zero requests is now a failure, for a suite and for the whole run.
#
# One exception: a suite that exits 0 and reports skip>0 with no requests has said, in its own
# RESULT line, that it chose not to run (the Paycheck Sandbox suites do this when FRED_API_BASE or
# the Sandbox fixtures are absent, and in CI, whose backend runs the offline ci profile). That is a
# declared skip and is reported as such; a suite that is silent about why it sent nothing is still
# broken.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FAILED_SUITES=()
TOTAL_PASS=0; TOTAL_FAIL=0; TOTAL_SKIP=0

run_suite() {
  local name="$1" script="$2"
  echo "=============================="
  echo "Suite: $name"
  echo "=============================="
  local out code
  out=$(bash "$SCRIPT_DIR/$script" 2>&1)
  code=$?
  echo "$out"

  local result pass fail skip
  result=$(echo "$out" | grep -E "^RESULT pass=[0-9]+ fail=[0-9]+ skip=[0-9]+$" | tail -1)
  if [ -z "$result" ]; then
    echo "SUITE BROKEN: $name did not finish (no RESULT line, exit code $code)."
    FAILED_SUITES+=("$name (did not finish)")
    echo ""
    return
  fi
  pass=$(echo "$result" | sed -E "s/.*pass=([0-9]+).*/\1/")
  fail=$(echo "$result" | sed -E "s/.*fail=([0-9]+).*/\1/")
  skip=$(echo "$result" | sed -E "s/.*skip=([0-9]+).*/\1/")
  TOTAL_PASS=$((TOTAL_PASS + pass)); TOTAL_FAIL=$((TOTAL_FAIL + fail)); TOTAL_SKIP=$((TOTAL_SKIP + skip))

  if [ $((pass + fail)) -eq 0 ] && [ "$skip" -gt 0 ] && [ "$code" -eq 0 ]; then
    echo "SUITE SKIPPED: $name sent no requests and said why (skip=$skip)."
  elif [ $((pass + fail)) -eq 0 ]; then
    echo "SUITE BROKEN: $name ran zero requests."
    FAILED_SUITES+=("$name (zero requests)")
  elif [ "$code" -ne 0 ] || [ "$fail" -ne 0 ]; then
    FAILED_SUITES+=("$name")
  fi
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
echo "Requests run: $((TOTAL_PASS + TOTAL_FAIL))  passed: $TOTAL_PASS  failed: $TOTAL_FAIL  skipped: $TOTAL_SKIP"
if [ $((TOTAL_PASS + TOTAL_FAIL)) -eq 0 ]; then
  echo "FAILED: no requests were run at all"
  FAILED_SUITES+=("(no requests)")
elif [ ${#FAILED_SUITES[@]} -eq 0 ]; then
  echo "ALL SUITES PASSED"
else
  echo "FAILED: ${FAILED_SUITES[*]}"
fi
echo "=============================="

[ ${#FAILED_SUITES[@]} -eq 0 ]
