#!/bin/bash
# Tests /api/trading/* endpoints (read-only).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/auth.sh" || exit 1
source "$SCRIPT_DIR/lib/helpers.sh" || { echo "ERROR: could not load lib/helpers.sh"; exit 1; }

run_curl_expect "GET /api/trading/positions" 404 GET /api/trading/positions
run_curl_expect "GET /api/trading/account/balance" 404 GET /api/trading/account/balance

finish_suite "Trading"
