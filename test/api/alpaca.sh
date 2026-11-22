#!/bin/bash
# Tests /api/alpaca/* endpoints (read-only — covers KYC status reads).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/auth.sh" || exit 1
source "$SCRIPT_DIR/lib/helpers.sh" || { echo "ERROR: could not load lib/helpers.sh"; exit 1; }

run_curl_expect "GET /api/alpaca/account" 404 GET /api/alpaca/account
run_curl "GET /api/alpaca/my-account-status" GET /api/alpaca/my-account-status
run_curl_broker "GET /api/alpaca/assets?status=active&asset_class=us_equity" \
         GET "/api/alpaca/assets?status=active&asset_class=us_equity"

# FRED-123 — KYC editing
run_curl_expect "GET /api/alpaca/account/kyc" 404 GET /api/alpaca/account/kyc
# Note: PATCH /api/alpaca/account/kyc is omitted here to avoid mutating Alpaca data during smoke tests.
# Verify manually when needed:
#   run_curl "PATCH /api/alpaca/account/kyc" PATCH /api/alpaca/account/kyc '{"phoneNumber":"+15551234567"}'

finish_suite "Alpaca"
