-- add_order.lua
-- Atomically adds an order to the orderbook with all related data
--
-- KEYS[1]: orderbook ZSet key (e.g., "orderbook:buy")
-- KEYS[2]: order detail key (e.g., "order:uuid")
-- KEYS[3]: user orders Set key (e.g., "user:uuid:orders")
--
-- ARGV[1]: order ID (string)
-- ARGV[2]: price (number, used as ZSet score)
-- ARGV[3]: order JSON (string)
--
-- Returns: 1 on success

local orderbook_key = KEYS[1]
local order_id_key = KEYS[2]
local user_orders_key = KEYS[3]

local order_id = ARGV[1]
local price = tonumber(ARGV[2])
local order_json = ARGV[3]

-- All three operations are atomic within this Lua script
redis.call('ZADD', orderbook_key, price, order_id)
redis.call('SET', order_id_key, order_json)
redis.call('SADD', user_orders_key, order_id)

return 1
