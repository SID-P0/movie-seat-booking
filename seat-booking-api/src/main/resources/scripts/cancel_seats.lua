-- Cancel/release seats atomically via Redis Lua script.
--
-- KEYS[1]   = idemp:{idempotency_key}
-- KEYS[2]   = user_count:{show_id}:{user_id}
-- KEYS[3..] = seat:{show_id}:{seat_id} (one per seat in the reservation)
--
-- ARGV[1]   = reservation_id (to verify ownership before deleting)
-- ARGV[2]   = num_seats
-- ARGV[3]   = expiry_zset_key
-- ARGV[4..] = ZSET member strings (show_id:seat_id:reservation_id)

local idemp_key = KEYS[1]
local user_count_key = KEYS[2]
local reservation_id = ARGV[1]
local num_seats = tonumber(ARGV[2])
local expiry_zset_key = ARGV[3]

-- Release each seat — only if the current holder matches our reservation_id
local released = 0
for i = 1, num_seats do
    local seat_key = KEYS[2 + i]
    local current_holder = redis.call('GET', seat_key)
    if current_holder == reservation_id then
        redis.call('DEL', seat_key)
        released = released + 1
    end
end

-- Decrement user count
if released > 0 then
    redis.call('DECRBY', user_count_key, released)
    -- Ensure count doesn't go negative
    local count = tonumber(redis.call('GET', user_count_key) or '0')
    if count < 0 then
        redis.call('SET', user_count_key, '0')
    end
end

-- Remove from hold expiry ZSET
for i = 1, num_seats do
    local member = ARGV[3 + i]
    redis.call('ZREM', expiry_zset_key, member)
end

-- Remove idempotency key so the user can re-book with a new key
redis.call('DEL', idemp_key)

return {'OK', tostring(released)}
