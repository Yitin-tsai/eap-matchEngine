-- reserve_or_add_order_sell.lua
-- For an incoming SELL, atomically reserves the best matching BUY order or adds
-- the SELL to the visible orderbook when no match exists.
--
-- KEYS[1]: buy orderbook ZSet key
-- KEYS[2]: sell orderbook ZSet key
-- KEYS[3]: incoming order detail key
-- KEYS[4]: incoming user orders Set key
-- KEYS[5]: match-id sequence key
--
-- ARGV[1]: min composite score (sell order's price limit)
-- ARGV[2]: reserved timestamp epoch millis
-- ARGV[3]: incoming order ID
-- ARGV[4]: incoming order composite score
-- ARGV[5]: incoming order JSON
-- ARGV[6]: maintain user open-order index flag ("1" or "0")
--
-- Returns:
--   {'__MATCH__', resting order JSON, match ID}
--   {'__ADDED__'}
--   {'__MISSING_ORDER_DETAIL__:<orderId>'}
--   {'__RESERVATION_EXISTS__:<orderId>'}

local buy_orderbook_key = KEYS[1]
local sell_orderbook_key = KEYS[2]
local incoming_order_id_key = KEYS[3]
local incoming_user_orders_key = KEYS[4]
local sequence_key = KEYS[5]

local min_score = tonumber(ARGV[1])
local reserved_at = tonumber(ARGV[2])
local incoming_order_id = ARGV[3]
local incoming_score = tonumber(ARGV[4])
local incoming_order_json = ARGV[5]
local user_order_index_enabled = ARGV[6] ~= '0'

local orders = redis.call('ZREVRANGEBYSCORE', buy_orderbook_key, '+inf', min_score, 'LIMIT', 0, 1)

if #orders == 0 then
    redis.call('ZADD', sell_orderbook_key, incoming_score, incoming_order_id)
    redis.call('SET', incoming_order_id_key, incoming_order_json)
    if user_order_index_enabled then
        redis.call('SADD', incoming_user_orders_key, incoming_order_id)
    end
    return {'__ADDED__'}
end

local resting_order_id = orders[1]
local resting_order_id_key = 'order:' .. resting_order_id
local reservation_key = 'order:reservation:' .. resting_order_id

local resting_order_json = redis.call('GET', resting_order_id_key)
if not resting_order_json then
    redis.call('ZREM', buy_orderbook_key, resting_order_id)
    return {'__MISSING_ORDER_DETAIL__:' .. resting_order_id}
end

local match_id = redis.call('INCR', sequence_key)

redis.call('ZREM', buy_orderbook_key, resting_order_id)
local reservation_json = '{"reservedAtEpochMillis":' .. reserved_at .. ',"orderId":"' .. resting_order_id .. '"}'
local reserved = redis.call('SET', reservation_key, reservation_json, 'NX')
if not reserved then
    return {'__RESERVATION_EXISTS__:' .. resting_order_id}
end

return {'__MATCH__', resting_order_json, tostring(match_id)}
