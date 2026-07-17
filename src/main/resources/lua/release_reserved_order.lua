-- release_reserved_order.lua
-- Releases a reserved resting order back to the visible orderbook.
--
-- KEYS[1]: orderbook ZSet key
-- KEYS[2]: order detail key
-- KEYS[3]: user orders Set key
-- KEYS[4]: reservation key
--
-- ARGV[1]: order ID
-- ARGV[2]: composite orderbook score
-- ARGV[3]: order JSON
--
-- Returns: 1 if released, 0 if reservation does not exist, -1 if reservation does not match order ID

local orderbook_key = KEYS[1]
local order_id_key = KEYS[2]
local user_orders_key = KEYS[3]
local reservation_key = KEYS[4]

local order_id = ARGV[1]
local score = tonumber(ARGV[2])
local order_json = ARGV[3]

local reservation_json = redis.call('GET', reservation_key)
if not reservation_json then
    return 0
end

if not string.find(reservation_json, order_id, 1, true) then
    return -1
end

redis.call('SET', order_id_key, order_json)
redis.call('ZADD', orderbook_key, score, order_id)
redis.call('SADD', user_orders_key, order_id)
redis.call('DEL', reservation_key)

return 1
