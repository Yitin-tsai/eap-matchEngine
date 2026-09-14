-- complete_reserved_order.lua
-- Finalizes a reserved resting order after its TradeExecuted fact is durable.
--
-- KEYS[1]: order detail key
-- KEYS[2]: user orders Set key
-- KEYS[3]: reservation key
-- KEYS[4]: CDA order-book generation sentinel
--
-- ARGV[1]: order ID
-- ARGV[2]: maintain user open-order index flag ("1" or "0")
-- ARGV[3]: expected trade ID (empty only for legacy callers)
-- ARGV[4]: expected generation sentinel (empty only for isolated legacy tests)
--
-- Returns: 1 if completed, 0 if reservation does not exist, -1 if reservation does not match order ID,
--          -2 if a newer reservation owns the order

local order_id_key = KEYS[1]
local user_orders_key = KEYS[2]
local reservation_key = KEYS[3]

local order_id = ARGV[1]
local user_order_index_enabled = ARGV[2] ~= '0'
local expected_trade_id = ARGV[3]

local expected_run_id = string.match(ARGV[4], '|([^|]+)$')
local actual_run_id = string.match(redis.call('INFO', 'server'), 'run_id:([^\r\n]+)')
if ARGV[4] ~= '' and (redis.call('GET', KEYS[4]) ~= ARGV[4] or actual_run_id ~= expected_run_id) then
    return -99
end

local reservation_json = redis.call('GET', reservation_key)
if not reservation_json then
    return 0
end

local reservation = cjson.decode(reservation_json)
if not reservation.orderId
        or reservation.orderId == cjson.null
        or tostring(reservation.orderId) ~= order_id then
    return -1
end

if expected_trade_id ~= ''
        and (not reservation.tradeId
            or reservation.tradeId == cjson.null
            or tostring(reservation.tradeId) ~= expected_trade_id) then
    return -2
end

local deleted = redis.call('DEL', order_id_key)
local unlinked = 0
if user_order_index_enabled then
    unlinked = redis.call('SREM', user_orders_key, order_id)
end
local reservation_deleted = redis.call('DEL', reservation_key)

if deleted > 0 or unlinked > 0 or reservation_deleted > 0 then
    return 1
end

return 0
