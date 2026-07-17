-- reserve_match_order_sell.lua
-- Atomically finds the best buy order for a sell order and moves it into a
-- known reservation state without deleting the order detail.
--
-- KEYS[1]: buy orderbook ZSet key
-- KEYS[2]: optional match-id sequence key
-- ARGV[1]: min composite score (sell order's price limit)
--
-- ARGV[2]: reserved timestamp epoch millis
--
-- Returns: order JSON string, [order JSON string, match ID] when KEYS[2] is present,
--          or nil if no match found

local orderbook_key = KEYS[1]
local sequence_key = KEYS[2]
local min_score = tonumber(ARGV[1])
local reserved_at = tonumber(ARGV[2])

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
    if sequence_key then
        return {'__MISSING_ORDER_DETAIL__:' .. order_id}
    end
    return '__MISSING_ORDER_DETAIL__:' .. order_id
end

local match_id = nil
if sequence_key then
    match_id = redis.call('INCR', sequence_key)
end

redis.call('ZREM', orderbook_key, order_id)
local reservation_json = '{"reservedAtEpochMillis":' .. reserved_at .. ',"order":' .. order_json .. '}'
local reserved = redis.call('SET', reservation_key, reservation_json, 'NX')
if not reserved then
    if sequence_key then
        return {'__RESERVATION_EXISTS__:' .. order_id}
    end
    return '__RESERVATION_EXISTS__:' .. order_id
end

if sequence_key then
    return {order_json, tostring(match_id)}
end

return order_json
