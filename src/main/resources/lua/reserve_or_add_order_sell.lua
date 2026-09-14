-- reserve_or_add_order_sell.lua
-- For an incoming SELL, atomically reserves the best matching BUY order or adds
-- the SELL to the visible orderbook when no match exists.
--
-- KEYS[1]: buy orderbook ZSet key
-- KEYS[2]: sell orderbook ZSet key
-- KEYS[3]: incoming order detail key
-- KEYS[4]: incoming user orders Set key
-- KEYS[5]: match-id sequence key
-- KEYS[6]: incoming-order processing state Hash key (guarded processing only)
-- KEYS[7]: incoming-order completed bitmap key (guarded processing only)
-- KEYS[8]: incoming-order cancellation intent key
-- KEYS[9]: CDA order-book generation sentinel
--
-- ARGV[1]: min composite score (sell order's price limit)
-- ARGV[2]: reserved timestamp epoch millis
-- ARGV[3]: incoming order ID
-- ARGV[4]: incoming order composite score
-- ARGV[5]: incoming order JSON
-- ARGV[6]: maintain user open-order index flag ("1" or "0")
-- ARGV[7]: incoming order state Hash field (guarded processing only)
-- ARGV[8]: this processing attempt's token (guarded processing only)
-- ARGV[9]: completed bitmap bit offset (guarded processing only)
-- ARGV[10]: market ID used to correlate the reservation with its durable trade
-- ARGV[11]: incoming user ID (self-trade prevention)
-- ARGV[12]: expected generation sentinel (empty only for isolated legacy tests)
--
-- Returns:
--   {'__MATCH__', resting order JSON, match ID}
--   {'__ADDED__'}
--   {'__ADDED_COMPLETED__'} when guarded processing completes with the add
--   {'__MISSING_ORDER_DETAIL__:<orderId>'}
--   {'__INVALID_ORDER_DETAIL__:<orderId>'}
--   {'__RESERVATION_EXISTS__:<orderId>'}
--   {'__DUPLICATE__'}
--   {'__IN_PROGRESS__'}
--   {'__CANCELLATION_PENDING__'}

local buy_orderbook_key = KEYS[1]
local sell_orderbook_key = KEYS[2]
local incoming_order_id_key = KEYS[3]
local incoming_user_orders_key = KEYS[4]
local sequence_key = KEYS[5]
local incoming_state_hash_key = KEYS[6]
local completed_bitmap_key = KEYS[7]
local cancellation_intent_key = KEYS[8]

local min_score = tonumber(ARGV[1])
local reserved_at = tonumber(ARGV[2])
local incoming_order_id = ARGV[3]
local incoming_score = tonumber(ARGV[4])
local incoming_order_json = ARGV[5]
local user_order_index_enabled = ARGV[6] ~= '0'
local incoming_user_id = ARGV[11]

local expected_run_id = string.match(ARGV[12], '|([^|]+)$')
local actual_run_id = string.match(redis.call('INFO', 'server'), 'run_id:([^\r\n]+)')
if ARGV[12] ~= '' and (redis.call('GET', KEYS[9]) ~= ARGV[12] or actual_run_id ~= expected_run_id) then
    return {'__GENERATION_MISMATCH__'}
end

local guarded = ARGV[7] ~= ''
if guarded then
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
    if redis.call('GET', cancellation_intent_key) then
        return {'__CANCELLATION_PENDING__'}
    end
    if not existing_state then
        redis.call('HSET', incoming_state_hash_key, incoming_state_field, processing_prefix .. reserved_at)
    elseif string.sub(existing_state, 1, string.len(processing_prefix)) ~= processing_prefix then
        return {'__IN_PROGRESS__'}
    else
        redis.call('HSET', incoming_state_hash_key, incoming_state_field, processing_prefix .. reserved_at)
    end
end

if not guarded and redis.call('GET', cancellation_intent_key) then
    return {'__CANCELLATION_PENDING__'}
end

local resting_order_id = nil
local resting_order_json = nil
local offset = 0
local scan_batch_size = 32
while not resting_order_id do
    local orders = redis.call('ZREVRANGEBYSCORE', buy_orderbook_key, '+inf', min_score,
        'LIMIT', offset, scan_batch_size)
    if #orders == 0 then
        break
    end
    for _, candidate_id in ipairs(orders) do
        local candidate_json = redis.call('GET', 'order:' .. candidate_id)
        if not candidate_json then
            redis.call('ZREM', buy_orderbook_key, candidate_id)
            return {'__MISSING_ORDER_DETAIL__:' .. candidate_id}
        end
        local candidate = cjson.decode(candidate_json)
        local candidate_user_id = candidate.u or candidate.userId
        if not candidate_user_id or candidate_user_id == cjson.null then
            return {'__INVALID_ORDER_DETAIL__:' .. candidate_id}
        end
        if tostring(candidate_user_id) ~= incoming_user_id then
            resting_order_id = candidate_id
            resting_order_json = candidate_json
            break
        end
    end
    if not resting_order_id then
        if #orders < scan_batch_size then
            break
        end
        offset = offset + #orders
    end
end

if not resting_order_id then
    redis.call('ZADD', sell_orderbook_key, incoming_score, incoming_order_id)
    redis.call('SET', incoming_order_id_key, incoming_order_json)
    if user_order_index_enabled then
        redis.call('SADD', incoming_user_orders_key, incoming_order_id)
    end
    if guarded then
        redis.call('SETBIT', completed_bitmap_key, ARGV[9], 1)
        redis.call('HDEL', incoming_state_hash_key, ARGV[7])
        return {'__ADDED_COMPLETED__'}
    end
    return {'__ADDED__'}
end

local reservation_key = 'order:reservation:' .. resting_order_id

local match_id = redis.call('INCR', sequence_key)

redis.call('ZREM', buy_orderbook_key, resting_order_id)
local reservation_json = cjson.encode({
    reservedAtEpochMillis = reserved_at,
    orderId = resting_order_id,
    tradeId = ARGV[10] .. '-' .. tostring(match_id)
})
local reserved = redis.call('SET', reservation_key, reservation_json, 'NX')
if not reserved then
    return {'__RESERVATION_EXISTS__:' .. resting_order_id}
end

return {'__MATCH__', resting_order_json, tostring(match_id)}
