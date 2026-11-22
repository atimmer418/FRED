#!/bin/bash
# Tests /api/beneficiaries/* endpoints (read-only).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/auth.sh" || exit 1
source "$SCRIPT_DIR/lib/helpers.sh" || { echo "ERROR: could not load lib/helpers.sh"; exit 1; }

run_curl "GET /api/beneficiaries"            GET /api/beneficiaries
run_curl "GET /api/beneficiaries/active"     GET /api/beneficiaries/active
run_curl "GET /api/beneficiaries/summary"    GET /api/beneficiaries/summary
run_curl "GET /api/beneficiaries/primary"    GET /api/beneficiaries/primary
run_curl "GET /api/beneficiaries/contingent" GET /api/beneficiaries/contingent

finish_suite "Beneficiaries"
