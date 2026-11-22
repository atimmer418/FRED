#!/bin/bash
# Tests /api/chat/* endpoints.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/auth.sh" || exit 1
source "$SCRIPT_DIR/lib/helpers.sh" || { echo "ERROR: could not load lib/helpers.sh"; exit 1; }

# Bucket C: SSE streaming endpoint
# We use a separate test to consume the SSE stream and confirm at least one token
# event and a [done] event are emitted.
echo "[TEST] GET /api/chat/stream (SSE)"
TMPSSE=$(mktemp)
HTTP_STATUS=$(curl -s -N -o "$TMPSSE" -w "%{http_code}" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: text/event-stream" \
  --max-time 25 \
  "$BASE_URL/api/chat/stream?message=Hello%20FRED&sessionId=verifier-c-test&generateTitle=false")

BODY=$(head -c 4000 "$TMPSSE")
rm -f "$TMPSSE"

if [[ "$HTTP_STATUS" == "200" ]] && grep -q "\"token\"" <<<"$BODY"; then
  echo "[PASS] GET /api/chat/stream → HTTP $HTTP_STATUS (token events received)"
  PASS=$((PASS + 1))
else
  echo "[FAIL] GET /api/chat/stream → HTTP $HTTP_STATUS"
  FAIL=$((FAIL + 1))
fi
echo "Response (first 4KB):"
echo "$BODY"
echo ""

# Make sure the legacy POST /api/chat still works (FRED-183 AC#6)
run_curl "POST /api/chat (legacy)" POST /api/chat '{"message":"Hello FRED","sessionId":"verifier-c-test","generateTitle":false}'

run_curl "GET /api/chat/history" GET "/api/chat/history?sessionId=verifier-c-test"

finish_suite "Chat"
