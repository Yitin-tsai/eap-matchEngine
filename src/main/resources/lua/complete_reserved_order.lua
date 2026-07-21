-- complete_reserved_order.lua
-- Finalizes a reserved resting order after its TradeExecuted fact is durable.
--
-- KEYS[1]: order detail key
-- KEYS[2]: user orders Set key
-- KEYS[3]: reservation key
--
-- ARGV[1]: order ID
-- ARGV[2]: maintain user open-order index flag ("1" or "0")
--
-- Returns: 1 if completed, 0 if reservation does not exist, -1 if reservation does not match order ID

local order_id_key = KEYS[1]
local user_orders_key = KEYS[2]
local reservation_key = KEYS[3]

local order_id = ARGV[1]
local user_order_index_enabled = ARGV[2] ~= '0'

local reservation_json = redis.call('GET', reservation_key)
if not reservation_json then
    return 0
end

if not string.find(reservation_json, order_id, 1, true) then
    return -1
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
