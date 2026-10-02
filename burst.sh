#!/usr/bin/env bash
set -euo pipefail

###############################################################################
# burst.sh — On-sale stampede simulator
#
# Usage: ./burst.sh [BASE_URL] [NUM_USERS] [TOTAL_REQUESTS]
#
# Reproduces the hot-seat storm: many users, same seats, with idempotent retries.
# Prints outcome distribution and reconciliation check.
###############################################################################

BASE_URL="${1:-http://localhost:8080}"
NUM_USERS="${2:-200}"
TOTAL_REQUESTS="${3:-5000}"
ADMIN_TOKEN="super-secret-admin-token"
SHOW_NAME="burst-test-$(date +%s)"
RESULTS_DIR="/tmp/burst-results-$$"

mkdir -p "$RESULTS_DIR"

echo "=============================================="
echo "  🎬 Seat Booking Burst Test"
echo "=============================================="
echo "  Target:    $BASE_URL"
echo "  Users:     $NUM_USERS"
echo "  Requests:  $TOTAL_REQUESTS"
echo "=============================================="
echo ""

# --- Step 1: Create test users ---
echo "📝 Step 1: Creating $NUM_USERS test users..."
TOKENS_FILE="$RESULTS_DIR/tokens.txt"
> "$TOKENS_FILE"

for i in $(seq 1 "$NUM_USERS"); do
    RESPONSE=$(curl -sf -X POST "$BASE_URL/users/register" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"email\": \"user${i}_${SHOW_NAME}@test.com\"}" 2>/dev/null || echo "FAILED")

    if [ "$RESPONSE" = "FAILED" ]; then
        echo "  ⚠ Failed to create user $i"
        continue
    fi

    TOKEN=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))" 2>/dev/null || echo "")
    if [ -n "$TOKEN" ]; then
        echo "$TOKEN" >> "$TOKENS_FILE"
    fi
done

ACTUAL_USERS=$(wc -l < "$TOKENS_FILE" | tr -d ' ')
echo "  ✅ Created $ACTUAL_USERS users"
echo ""

if [ "$ACTUAL_USERS" -lt 2 ]; then
    echo "❌ Not enough users created. Check if the service is running at $BASE_URL"
    exit 1
fi

# --- Step 2: Create a show with seats ---
echo "🎭 Step 2: Creating show with 100 seats..."

# Generate seat list: A1-A25, B1-B25, C1-C25, D1-D25
SEATS=$(python3 -c "
import json
seats = []
for row in 'ABCD':
    for num in range(1, 26):
        seats.append(f'{row}{num}')
print(json.dumps(seats))
")

CREATE_RESPONSE=$(curl -sf -X POST "$BASE_URL/shows" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\": \"$SHOW_NAME\", \"seats\": $SEATS, \"price\": 25000}")

SHOW_ID=$(echo "$CREATE_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('showId',''))" 2>/dev/null)

if [ -z "$SHOW_ID" ]; then
    echo "❌ Failed to create show. Response: $CREATE_RESPONSE"
    exit 1
fi

echo "  ✅ Show created: $SHOW_ID (100 seats, ₹250 each)"
echo ""

# --- Step 3: Fire the burst ---
echo "🔥 Step 3: Firing $TOTAL_REQUESTS concurrent reservation requests..."
echo "  → Hot seats: A1, A2, A3 (heavy contention)"
echo "  → Warm seats: all others"
echo "  → ~10% idempotent retries"
echo ""

BURST_RESULTS="$RESULTS_DIR/results.txt"
> "$BURST_RESULTS"

# Generate request payload files
PAYLOADS_DIR="$RESULTS_DIR/payloads"
mkdir -p "$PAYLOADS_DIR"

TOKENS=()
while IFS= read -r line; do
    [[ -n "$line" ]] && TOKENS+=("$line")
done < "$TOKENS_FILE"

fire_request() {
    local token="$1"
    local seat="$2"
    local idemp_key="$3"
    local result_file="$4"

    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $token" \
        -d "{\"seats\": [\"$seat\"], \"idempotencyKey\": \"$idemp_key\"}" \
        --max-time 10 2>/dev/null || echo "000")

    echo "$HTTP_CODE" >> "$result_file"
}

export -f fire_request
export BASE_URL SHOW_ID

# Generate requests
REQUESTS_FILE="$RESULTS_DIR/requests.txt"
> "$REQUESTS_FILE"

python3 -c "
import random, uuid

tokens_file = '$TOKENS_FILE'
with open(tokens_file) as f:
    tokens = [t.strip() for t in f.readlines() if t.strip()]

total = $TOTAL_REQUESTS
hot_seats = ['A1', 'A2', 'A3']
all_seats = []
for row in 'ABCD':
    for num in range(1, 26):
        all_seats.append(f'{row}{num}')

warm_seats = [s for s in all_seats if s not in hot_seats]

requests = []
idemp_keys = {}  # user -> list of keys used

for i in range(total):
    token = tokens[i % len(tokens)]
    user_idx = i % len(tokens)

    # 60% hot seats, 40% warm
    if random.random() < 0.6:
        seat = random.choice(hot_seats)
    else:
        seat = random.choice(warm_seats)

    # 10% chance of idempotent retry (reuse a previous key)
    if user_idx in idemp_keys and len(idemp_keys[user_idx]) > 0 and random.random() < 0.1:
        idemp_key = random.choice(idemp_keys[user_idx])
    else:
        idemp_key = str(uuid.uuid4())
        if user_idx not in idemp_keys:
            idemp_keys[user_idx] = []
        idemp_keys[user_idx].append(idemp_key)

    print(f'{token}\t{seat}\t{idemp_key}')
" > "$REQUESTS_FILE"

# Fire requests in parallel using xargs
START_TIME=$(date +%s)

cat "$REQUESTS_FILE" | while IFS=$'\t' read -r token seat idemp_key; do
    echo "$token $seat $idemp_key $BURST_RESULTS"
done | xargs -P 50 -L 1 bash -c 'fire_request "$@"' _

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

echo ""
echo "  ⏱  Burst completed in ${DURATION}s"
echo ""

# --- Step 4: Analyze results ---
echo "📊 Step 4: Results"
echo "=============================================="

TOTAL_201=$(grep -c "^201$" "$BURST_RESULTS" || true)
TOTAL_200=$(grep -c "^200$" "$BURST_RESULTS" || true)
TOTAL_409=$(grep -c "^409$" "$BURST_RESULTS" || true)
TOTAL_400=$(grep -c "^400$" "$BURST_RESULTS" || true)
TOTAL_401=$(grep -c "^401$" "$BURST_RESULTS" || true)
TOTAL_403=$(grep -c "^403$" "$BURST_RESULTS" || true)
TOTAL_404=$(grep -c "^404$" "$BURST_RESULTS" || true)
TOTAL_5XX=$(grep -c "^5[0-9][0-9]$" "$BURST_RESULTS" || true)
TOTAL_000=$(grep -c "^000$" "$BURST_RESULTS" || true)
TOTAL_RESPONSES=$(wc -l < "$BURST_RESULTS" | tr -d ' ')

echo "  201 Confirmed:       $TOTAL_201"
echo "  200 Idempotent:      $TOTAL_200"
echo "  409 Declined:        $TOTAL_409"
echo "  400 Bad Request:     $TOTAL_400"
echo "  401 Unauthorized:    $TOTAL_401"
echo "  5xx Errors:          $TOTAL_5XX"
echo "  Timeouts/Errors:     $TOTAL_000"
echo "  Total Responses:     $TOTAL_RESPONSES"
echo ""

# --- Step 5: Reconciliation check ---
echo "🔍 Step 5: Reconciliation Check"
echo "=============================================="

sleep 2  # Wait for Kafka consumer to catch up

SHOW_STATE=$(curl -sf "$BASE_URL/shows/$SHOW_ID" \
    -H "Authorization: Bearer ${TOKENS[0]}" 2>/dev/null)

if [ -n "$SHOW_STATE" ]; then
    AVAIL=$(echo "$SHOW_STATE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['counts']['available'])")
    HELD=$(echo "$SHOW_STATE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['counts']['held'])")
    CONF=$(echo "$SHOW_STATE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['counts']['confirmed'])")
    TOTAL=$(echo "$SHOW_STATE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['counts']['total'])")

    echo "  Available:    $AVAIL"
    echo "  Held:         $HELD"
    echo "  Confirmed:    $CONF"
    echo "  Total:        $TOTAL"
    echo ""

    if [ "$TOTAL" -eq 100 ]; then
        echo "  ✅ Reconciliation PASSED (total = 100)"
    else
        echo "  ❌ Reconciliation FAILED (total = $TOTAL, expected 100)"
    fi

    if [ "$TOTAL_5XX" -eq 0 ]; then
        echo "  ✅ Zero 5xx errors"
    else
        echo "  ❌ $TOTAL_5XX server errors detected!"
    fi
else
    echo "  ⚠ Could not fetch show state for reconciliation"
fi

echo ""
echo "=============================================="
echo "  Burst test complete!"
echo "=============================================="

# Cleanup
rm -rf "$RESULTS_DIR"
