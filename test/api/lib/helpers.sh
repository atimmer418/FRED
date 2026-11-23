#!/bin/bash
# Shared helpers for the domain test scripts.
# Requires $TOKEN and $BASE_URL (exported by lib/auth.sh).
#
#   run_curl        "label" METHOD /api/path [json-body]
#       Passes on any 2xx.
#
#   run_curl_expect "label" NEW_USER_STATUS METHOD /api/path [json-body]
#       For endpoints whose answer depends on who is asking. Passes on any 2xx for a set-up
#       user. When API_TEST_USER_STATE=new (a user with no brokerage account and no schedule,
#       which is what CI seeds), it passes only on exactly NEW_USER_STATUS.
#
#   run_curl_broker "label" METHOD /api/path [json-body]
#       For endpoints that need the live broker whoever is asking. Runs as run_curl, except when
#       API_TEST_BROKER=offline (CI has no broker), where it is counted as SKIPPED and shown.
#
#   finish_suite "Name"
#       Prints the totals and the RESULT line run-all.sh reads, then exits. Every suite must end
#       with it: a suite that never reaches it is treated as broken, never as passed.

PASS=0; FAIL=0; SKIP=0

# _request "label" EXPECTED METHOD path [body]   (EXPECTED is "2xx" or an exact status)
_request() {
  local label="$1" expected="$2" method="$3" path="$4" body="${5:-}"
  local tmpfile
  tmpfile=$(mktemp)

  local curl_args=(-s -o "$tmpfile" -w "%{http_code}"
    -X "$method"
    "$BASE_URL$path"
    -H "Authorization: Bearer $TOKEN"
    -H "Content-Type: application/json")

  [ -n "$body" ] && curl_args+=(-d "$body")

  local status
  status=$(curl "${curl_args[@]}")
  local resp
  resp=$(cat "$tmpfile")
  rm -f "$tmpfile"

  local ok=1
  if [ "$expected" = "2xx" ]; then
    [[ "$status" == 2* ]] && ok=0
  else
    [ "$status" = "$expected" ] && ok=0
  fi

  if [ $ok -eq 0 ]; then
    echo "[PASS] $label → HTTP $status"
    PASS=$((PASS + 1))
  else
    echo "[FAIL] $label → HTTP $status (expected $expected)"
    FAIL=$((FAIL + 1))
  fi
  echo "Response: $resp"
  echo ""
}

run_curl() {
  _request "$1" "2xx" "$2" "$3" "${4:-}"
}

run_curl_expect() {
  local label="$1" new_user_status="$2" method="$3" path="$4" body="${5:-}"
  if [ "${API_TEST_USER_STATE:-}" = "new" ]; then
    _request "$label (new user)" "$new_user_status" "$method" "$path" "$body"
  else
    _request "$label" "2xx" "$method" "$path" "$body"
  fi
}

run_curl_broker() {
  if [ "${API_TEST_BROKER:-}" = "offline" ]; then
    echo "[SKIP] $1 (needs the live broker, API_TEST_BROKER=offline)"
    echo ""
    SKIP=$((SKIP + 1))
  else
    _request "$1" "2xx" "$2" "$3" "${4:-}"
  fi
}

finish_suite() {
  echo "---"
  echo "$1 tests: $PASS passed, $FAIL failed, $SKIP skipped"
  echo "RESULT pass=$PASS fail=$FAIL skip=$SKIP"
  [ "$FAIL" -eq 0 ]
  exit $?
}
