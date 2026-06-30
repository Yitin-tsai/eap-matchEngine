-- remove_order.lua
-- Atomically removes an order from orderbook and all related data
--
-- KEYS[1]: orderbook ZSet key (e.g., "orderbook:buy")
-- KEYS[2]: order detail key (e.g., "order:uuid")
-- KEYS[3]: user orders Set key (e.g., "user:uuid:orders")
--
-- ARGV[1]: order ID (string)
--
-- Returns: 1 if any orderbook/detail/user reference was removed, 0 if nothing existed

local orderbook_key = KEYS[1]
local order_id_key = KEYS[2]
local user_orders_key = KEYS[3]

local order_id = ARGV[1]

local removed = redis.call('ZREM', orderbook_key, order_id)
local deleted = redis.call('DEL', order_id_key)
local unlinked = redis.call('SREM', user_orders_key, order_id)

if removed > 0 or deleted > 0 or unlinked > 0 then
    return 1
end

return 0
