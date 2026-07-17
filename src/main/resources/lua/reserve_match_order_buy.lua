-- reserve_match_order_buy.lua
-- Atomically finds the best sell order for a buy order and moves it into a
-- known reservation state without deleting the order detail.
--
-- KEYS[1]: sell orderbook ZSet key
-- ARGV[1]: max composite score (buy order's price limit)
--
-- ARGV[2]: reserved timestamp epoch millis
--
-- Returns: order JSON string, or nil if no match found

local orderbook_key = KEYS[1]
local max_score = tonumber(ARGV[1])
local reserved_at = tonumber(ARGV[2])

local orders = redis.call('ZRANGEBYSCORE', orderbook_key, '-inf', max_score, 'LIMIT', 0, 1)

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
local reservation_json = '{"reservedAtEpochMillis":' .. reserved_at .. ',"order":' .. order_json .. '}'
local reserved = redis.call('SET', reservation_key, reservation_json, 'NX')
if not reserved then
    return '__RESERVATION_EXISTS__:' .. order_id
end

return order_json
