#!/bin/bash
# Tests /api/investments/* and /api/investment-schedule/* endpoints (read-only).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/auth.sh" || exit 1
source "$SCRIPT_DIR/lib/helpers.sh" || { echo "ERROR: could not load lib/helpers.sh"; exit 1; }

run_curl "GET /api/investments/dashboard" GET /api/investments/dashboard
run_curl "GET /api/investments/history"   GET /api/investments/history
run_curl_expect "GET /api/investment-schedule/current" 404 GET /api/investment-schedule/current
run_curl "GET /api/investment-schedule/all"     GET /api/investment-schedule/all

finish_suite "Investments"
