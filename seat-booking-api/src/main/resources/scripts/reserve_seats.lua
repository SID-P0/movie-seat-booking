-- Reserve seats atomically via Redis Lua script.
-- This is THE critical atomic decision point of the entire system.
--
-- Redis is single-threaded: this entire script runs without interleaving.
-- No read-then-write race is possible.
--
-- KEYS[1]   = idemp:{idempotency_key}
-- KEYS[2]   = user_count:{show_id}:{user_id}
-- KEYS[3..] = seat:{show_id}:{seat_id} (one per requested seat)
--
-- ARGV[1]   = reservation_id
-- ARGV[2]   = user_id
-- ARGV[3]   = per_user_limit
-- ARGV[4]   = num_seats
-- ARGV[5]   = hold_ttl_seconds (TTL for seat keys & idempotency key)
-- ARGV[6]   = hold_expiry_zset_key (e.g. "hold_expiry")
-- ARGV[7]   = expiry_epoch_ms (now + hold_ttl in milliseconds)
-- ARGV[8..] = seat_ids (matching KEYS[3..], used for ZSET member names)
-- ARGV[8+num_seats..] = show_id (single value after all seat_ids)

local idemp_key = KEYS[1]
local user_count_key = KEYS[2]
local reservation_id = ARGV[1]
local user_id = ARGV[2]
local per_user_limit = tonumber(ARGV[3])
local num_seats = tonumber(ARGV[4])
local hold_ttl = tonumber(ARGV[5])
local expiry_zset_key = ARGV[6]
local expiry_epoch_ms = tonumber(ARGV[7])

-- Step 1: Idempotency check
-- If this key already exists, the request is a retry.
local existing = redis.call('GET', idemp_key)
if existing then
    return {'IDEMPOTENT_HIT', existing}
end

-- Step 2: Per-user limit check
local current_count = tonumber(redis.call('GET', user_count_key) or '0')
if current_count + num_seats > per_user_limit then
    return {'USER_LIMIT_EXCEEDED', tostring(current_count)}
end

-- Step 3: Atomic seat claim (ALL-OR-NOTHING)
-- Try to SET NX each seat key. If any fails, rollback all.
local claimed = {}
for i = 1, num_seats do
    local seat_key = KEYS[2 + i]  -- KEYS[3], KEYS[4], ...
    local result = redis.call('SET', seat_key, reservation_id, 'NX', 'EX', hold_ttl)
    if not result then
        -- Rollback: delete all seats we already claimed in this batch
        for j = 1, #claimed do
            redis.call('DEL', claimed[j])
        end
        local seat_id = ARGV[7 + i]  -- the seat_id that failed
        return {'SEAT_TAKEN', seat_id}
    end
    table.insert(claimed, seat_key)
end

-- Step 4: Commit — all seats claimed successfully
-- Set idempotency key (with TTL slightly longer than hold)
redis.call('SET', idemp_key, reservation_id, 'EX', hold_ttl + 60)

-- Increment user seat count
redis.call('INCRBY', user_count_key, num_seats)
-- Set TTL on user count key if it's new (safety: expire with the hold)
redis.call('EXPIRE', user_count_key, hold_ttl + 300)

-- Add to hold expiry ZSET for background sweeper
local show_id = ARGV[8 + num_seats]
for i = 1, num_seats do
    local seat_id = ARGV[7 + i]
    local member = show_id .. ':' .. seat_id .. ':' .. reservation_id
    redis.call('ZADD', expiry_zset_key, expiry_epoch_ms, member)
end

return {'OK', reservation_id}
