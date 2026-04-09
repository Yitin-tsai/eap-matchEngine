-- remove_order.lua
-- Atomically removes an order from orderbook and all related data
--
-- KEYS[1]: orderbook ZSet key (e.g., "orderbook:buy")
-- KEYS[2]: order detail key (e.g., "order:uuid")
-- KEYS[3]: user orders Set key (e.g., "user:uuid:orders")
--
-- ARGV[1]: order ID (string)
--
-- Returns: 1 if removed, 0 if order not found in orderbook

local orderbook_key = KEYS[1]
local order_id_key = KEYS[2]
local user_orders_key = KEYS[3]

local order_id = ARGV[1]

-- Check if order exists in orderbook
local removed = redis.call('ZREM', orderbook_key, order_id)

if removed > 0 then
    -- Order was in orderbook, remove all related data
    redis.call('DEL', order_id_key)
    redis.call('SREM', user_orders_key, order_id)
    return 1
else
    -- Order not in orderbook (might have been matched already)
    return 0
end
