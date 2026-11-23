#!/bin/bash
# Tests /api/portfolio/* endpoints (read-only).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/auth.sh" || exit 1
source "$SCRIPT_DIR/lib/helpers.sh" || { echo "ERROR: could not load lib/helpers.sh"; exit 1; }

run_curl "GET /api/portfolio/current"           GET /api/portfolio/current
run_curl_expect "GET /api/portfolio/dashboard" 400         GET /api/portfolio/dashboard
run_curl_expect "GET /api/portfolio/history?period=1M" 400 GET "/api/portfolio/history?period=1M"
run_curl_expect "GET /api/portfolio/positions" 400         GET /api/portfolio/positions
run_curl_expect "GET /api/portfolio/performance" 400       GET /api/portfolio/performance

finish_suite "Portfolio"
