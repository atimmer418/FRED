#!/bin/bash
# Sources config.local.sh, authenticates via /api/dev/authenticate-as-user,
# and exports $TOKEN and $BASE_URL for use by domain test scripts.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="$SCRIPT_DIR/../config.local.sh"

if [ ! -f "$CONFIG_FILE" ]; then
  echo "ERROR: $CONFIG_FILE not found."
  echo "Run: cp test/api/config.example.sh test/api/config.local.sh"
  echo "Then set TEST_USER_EMAIL in config.local.sh"
  exit 1
fi

source "$CONFIG_FILE"

if [ -z "$TEST_USER_EMAIL" ]; then
  echo "ERROR: TEST_USER_EMAIL is not set in config.local.sh"
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"

# The dev endpoints require a shared-secret header (they are also profile-gated and never exist
# in prod). CI and local default to the same throwaway value; override with DEV_TOOLS_KEY.
DEV_TOOLS_KEY="${DEV_TOOLS_KEY:-ci-local-dev-tools}"

RESPONSE=$(curl -s -X POST "$BASE_URL/api/dev/authenticate-as-user" \
  -H "Content-Type: application/json" \
  -H "X-Dev-Tools-Key: $DEV_TOOLS_KEY" \
  -d "{\"email\": \"$TEST_USER_EMAIL\"}")

TOKEN=$(echo "$RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('jwtToken') or '')" 2>/dev/null)

if [ -z "$TOKEN" ]; then
  echo "ERROR: Failed to acquire JWT token."
  echo "Response: $RESPONSE"
  echo "Is the backend running at $BASE_URL?"
  exit 1
fi

export TOKEN
export BASE_URL
