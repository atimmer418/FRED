#!/bin/bash
# Tests /api/user/* and /api/user/pin/* endpoints (read-only).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/auth.sh" || exit 1
source "$SCRIPT_DIR/lib/helpers.sh" || { echo "ERROR: could not load lib/helpers.sh"; exit 1; }

run_curl "GET /api/user/progress" GET /api/user/progress
run_curl "GET /api/user/sessions" GET /api/user/sessions
run_curl "GET /api/user/pin/status" GET /api/user/pin/status
run_curl "GET /api/user/pin/lockout-status" GET /api/user/pin/lockout-status

# Bucket C additions
run_curl "PATCH /api/user/push-token" PATCH /api/user/push-token '{"pushToken":"test-token-bucket-c-verifier"}'
run_curl "PUT /api/user/profile (agreedToMarketing)" PUT /api/user/profile '{"agreedToMarketing":true}'
run_curl "POST /api/user/subscription/confirm" POST /api/user/subscription/confirm '{"tier":"core","billingPeriod":"monthly"}'

finish_suite "User"
