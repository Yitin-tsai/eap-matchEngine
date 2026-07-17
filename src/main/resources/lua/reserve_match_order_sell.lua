-- reserve_match_order_sell.lua
-- Atomically finds the best buy order for a sell order and moves it into a
-- known reservation state without deleting the order detail.
--
-- KEYS[1]: buy orderbook ZSet key
-- ARGV[1]: min composite score (sell order's price limit)
--
-- Returns: order JSON string, or nil if no match found

local orderbook_key = KEYS[1]
local min_score = tonumber(ARGV[1])

local orders = redis.call('ZREVRANGEBYSCORE', orderbook_key, '+inf', min_score, 'LIMIT', 0, 1)

if #orders == 0 then
    return nil
end

local order_id = orders[1]
local order_id_key = 'order:' .. order_id
local reservation_key = 'order:reservation:' .. order_id

local order_json = redis.call('GET', order_id_key)

if not order_json then
    redis.call('ZREM', orderbook_key, order_id)
    return '__MISSING_ORDER_DETAIL__:' .. order_id
end

redis.call('ZREM', orderbook_key, order_id)
redis.call('SET', reservation_key, order_json)

return order_json
