-- reserve_match_order_buy.lua
-- Atomically finds the best sell order for a buy order and moves it into a
-- known reservation state without deleting the order detail.
--
-- KEYS[1]: sell orderbook ZSet key
-- KEYS[2]: optional match-id sequence key
-- KEYS[last]: CDA order-book generation sentinel
-- ARGV[1]: max composite score (buy order's price limit)
--
-- ARGV[2]: reserved timestamp epoch millis
-- ARGV[3]: incoming user ID (self-trade prevention)
-- ARGV[4]: expected generation sentinel (empty only for isolated legacy tests)
--
-- Returns: order JSON string, [order JSON string, match ID] when KEYS[2] is present,
--          or nil if no match found

local orderbook_key = KEYS[1]
local sequence_key = nil
if #KEYS == 3 then
    sequence_key = KEYS[2]
end
local max_score = tonumber(ARGV[1])
local reserved_at = tonumber(ARGV[2])
local incoming_user_id = ARGV[3]

local expected_run_id = string.match(ARGV[4], '|([^|]+)$')
local actual_run_id = string.match(redis.call('INFO', 'server'), 'run_id:([^\r\n]+)')
if ARGV[4] ~= '' and (redis.call('GET', KEYS[#KEYS]) ~= ARGV[4] or actual_run_id ~= expected_run_id) then
    if sequence_key then
        return {'__GENERATION_MISMATCH__'}
    end
    return '__GENERATION_MISMATCH__'
end

local order_id = nil
local order_json = nil
local offset = 0
local scan_batch_size = 32
while not order_id do
    local orders = redis.call('ZRANGEBYSCORE', orderbook_key, '-inf', max_score,
        'LIMIT', offset, scan_batch_size)
    if #orders == 0 then
        return nil
    end
    for _, candidate_id in ipairs(orders) do
        local candidate_json = redis.call('GET', 'order:' .. candidate_id)
        if not candidate_json then
            redis.call('ZREM', orderbook_key, candidate_id)
            if sequence_key then
                return {'__MISSING_ORDER_DETAIL__:' .. candidate_id}
            end
            return '__MISSING_ORDER_DETAIL__:' .. candidate_id
        end
        local decode_ok, candidate = pcall(cjson.decode, candidate_json)
        if not decode_ok or type(candidate) ~= 'table' then
            if sequence_key then
                return {'__INVALID_ORDER_DETAIL__:' .. candidate_id}
            end
            return '__INVALID_ORDER_DETAIL__:' .. candidate_id
        end
        local candidate_user_id = candidate.u or candidate.userId
        if not candidate_user_id or candidate_user_id == cjson.null then
            if sequence_key then
                return {'__INVALID_ORDER_DETAIL__:' .. candidate_id}
            end
            return '__INVALID_ORDER_DETAIL__:' .. candidate_id
        end
        if tostring(candidate_user_id) ~= incoming_user_id then
            order_id = candidate_id
            order_json = candidate_json
            break
        end
    end
    if not order_id then
        if #orders < scan_batch_size then
            return nil
        end
        offset = offset + #orders
    end
end

local reservation_key = 'order:reservation:' .. order_id

local match_id = nil
if sequence_key then
    match_id = redis.call('INCR', sequence_key)
end

redis.call('ZREM', orderbook_key, order_id)
local reservation_json = '{"reservedAtEpochMillis":' .. reserved_at .. ',"orderId":"' .. order_id .. '"}'
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
