-- release_reserved_order.lua
-- Releases a reserved resting order back to the visible orderbook.
--
-- KEYS[1]: orderbook ZSet key
-- KEYS[2]: order detail key
-- KEYS[3]: user orders Set key
-- KEYS[4]: reservation key
-- KEYS[5]: CDA order-book generation sentinel
--
-- ARGV[1]: order ID
-- ARGV[2]: composite orderbook score
-- ARGV[3]: order JSON
-- ARGV[4]: maintain user open-order index flag ("1" or "0")
-- ARGV[5]: expected trade ID (empty only for legacy callers)
-- ARGV[6]: expected generation sentinel (empty only for isolated legacy tests)
--
-- Returns: 1 if released, 0 if reservation does not exist, -1 if reservation does not match order ID,
--          -2 if a newer reservation owns the order

local orderbook_key = KEYS[1]
local order_id_key = KEYS[2]
local user_orders_key = KEYS[3]
local reservation_key = KEYS[4]

local order_id = ARGV[1]
local score = tonumber(ARGV[2])
local order_json = ARGV[3]
local user_order_index_enabled = ARGV[4] ~= '0'
local expected_trade_id = ARGV[5]

local expected_run_id = string.match(ARGV[6], '|([^|]+)$')
local actual_run_id = string.match(redis.call('INFO', 'server'), 'run_id:([^\r\n]+)')
if ARGV[6] ~= '' and (redis.call('GET', KEYS[5]) ~= ARGV[6] or actual_run_id ~= expected_run_id) then
    return -99
end

local reservation_json = redis.call('GET', reservation_key)
if not reservation_json then
    return 0
end

if not string.find(reservation_json, order_id, 1, true) then
    return -1
end

local reservation = cjson.decode(reservation_json)
if expected_trade_id ~= '' and reservation.tradeId and reservation.tradeId ~= expected_trade_id then
    return -2
end

redis.call('SET', order_id_key, order_json)
redis.call('ZADD', orderbook_key, score, order_id)
if user_order_index_enabled then
    redis.call('SADD', user_orders_key, order_id)
end
redis.call('DEL', reservation_key)

return 1
