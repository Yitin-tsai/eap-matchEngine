-- reserve_or_add_order_buy.lua
-- For an incoming BUY, atomically reserves the best matching SELL order or adds
-- the BUY to the visible orderbook when no match exists.
--
-- KEYS[1]: sell orderbook ZSet key
-- KEYS[2]: buy orderbook ZSet key
-- KEYS[3]: incoming order detail key
-- KEYS[4]: incoming user orders Set key
-- KEYS[5]: match-id sequence key
-- KEYS[6]: incoming-order processing state Hash key (guarded processing only)
-- KEYS[7]: incoming-order completed bitmap key (guarded processing only)
--
-- ARGV[1]: max composite score (buy order's price limit)
-- ARGV[2]: reserved timestamp epoch millis
-- ARGV[3]: incoming order ID
-- ARGV[4]: incoming order composite score
-- ARGV[5]: incoming order JSON
-- ARGV[6]: maintain user open-order index flag ("1" or "0")
-- ARGV[7]: incoming order state Hash field (guarded processing only)
-- ARGV[8]: this processing attempt's token (guarded processing only)
-- ARGV[9]: completed bitmap bit offset (guarded processing only)
--
-- Returns:
--   {'__MATCH__', resting order JSON, match ID}
--   {'__ADDED__'}
--   {'__MISSING_ORDER_DETAIL__:<orderId>'}
--   {'__RESERVATION_EXISTS__:<orderId>'}
--   {'__DUPLICATE__'}
--   {'__IN_PROGRESS__'}

local sell_orderbook_key = KEYS[1]
local buy_orderbook_key = KEYS[2]
local incoming_order_id_key = KEYS[3]
local incoming_user_orders_key = KEYS[4]
local sequence_key = KEYS[5]
local incoming_state_hash_key = KEYS[6]
local completed_bitmap_key = KEYS[7]

local max_score = tonumber(ARGV[1])
local reserved_at = tonumber(ARGV[2])
local incoming_order_id = ARGV[3]
local incoming_score = tonumber(ARGV[4])
local incoming_order_json = ARGV[5]
local user_order_index_enabled = ARGV[6] ~= '0'

if incoming_state_hash_key then
    local incoming_state_field = ARGV[7]
    local processing_token = ARGV[8]
    local completed_bit_offset = ARGV[9]
    local processing_prefix = 'PROCESSING:' .. processing_token .. ':'
    local existing_state = redis.call('HGET', incoming_state_hash_key, incoming_state_field)
    if redis.call('GETBIT', completed_bitmap_key, completed_bit_offset) == 1 then
        return {'__DUPLICATE__'}
    end
    if existing_state == 'COMPLETED' then
        redis.call('SETBIT', completed_bitmap_key, completed_bit_offset, 1)
        redis.call('HDEL', incoming_state_hash_key, incoming_state_field)
        return {'__DUPLICATE__'}
    end
    if not existing_state then
        redis.call('HSET', incoming_state_hash_key, incoming_state_field, processing_prefix .. reserved_at)
    elseif string.sub(existing_state, 1, string.len(processing_prefix)) ~= processing_prefix then
        return {'__IN_PROGRESS__'}
    else
        redis.call('HSET', incoming_state_hash_key, incoming_state_field, processing_prefix .. reserved_at)
    end
end

local orders = redis.call('ZRANGEBYSCORE', sell_orderbook_key, '-inf', max_score, 'LIMIT', 0, 1)

if #orders == 0 then
    redis.call('ZADD', buy_orderbook_key, incoming_score, incoming_order_id)
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
    redis.call('ZREM', sell_orderbook_key, resting_order_id)
    return {'__MISSING_ORDER_DETAIL__:' .. resting_order_id}
end

local match_id = redis.call('INCR', sequence_key)

redis.call('ZREM', sell_orderbook_key, resting_order_id)
local reservation_json = '{"reservedAtEpochMillis":' .. reserved_at .. ',"orderId":"' .. resting_order_id .. '"}'
local reserved = redis.call('SET', reservation_key, reservation_json, 'NX')
if not reserved then
    return {'__RESERVATION_EXISTS__:' .. resting_order_id}
end

return {'__MATCH__', resting_order_json, tostring(match_id)}
