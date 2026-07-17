-- complete_reserved_order.lua
-- Finalizes a reserved resting order after its TradeExecuted fact is durable.
--
-- KEYS[1]: order detail key
-- KEYS[2]: user orders Set key
-- KEYS[3]: reservation key
--
-- ARGV[1]: order ID
--
-- Returns: 1 if any Redis state was removed, 0 otherwise

local order_id_key = KEYS[1]
local user_orders_key = KEYS[2]
local reservation_key = KEYS[3]

local order_id = ARGV[1]

local deleted = redis.call('DEL', order_id_key)
local unlinked = redis.call('SREM', user_orders_key, order_id)
local reservation_deleted = redis.call('DEL', reservation_key)

if deleted > 0 or unlinked > 0 or reservation_deleted > 0 then
    return 1
end

return 0
