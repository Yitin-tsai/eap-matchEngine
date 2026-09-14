-- remove_order.lua
-- Atomically removes an order from orderbook and all related data
--
-- KEYS[1]: orderbook ZSet key (e.g., "orderbook:buy")
-- KEYS[2]: order detail key (e.g., "order:uuid")
-- KEYS[3]: user orders Set key (e.g., "user:uuid:orders")
-- KEYS[4]: CDA order-book generation sentinel
--
-- ARGV[1]: order ID (string)
-- ARGV[2]: maintain user open-order index flag ("1" or "0")
-- ARGV[3]: expected generation sentinel (empty only for isolated legacy tests)
--
-- Returns: 1 if any orderbook/detail/user reference was removed, 0 if nothing existed

local orderbook_key = KEYS[1]
local order_id_key = KEYS[2]
local user_orders_key = KEYS[3]

local order_id = ARGV[1]
local user_order_index_enabled = ARGV[2] ~= '0'

local expected_run_id = string.match(ARGV[3], '|([^|]+)$')
local actual_run_id = string.match(redis.call('INFO', 'server'), 'run_id:([^\r\n]+)')
if ARGV[3] ~= '' and (redis.call('GET', KEYS[4]) ~= ARGV[3] or actual_run_id ~= expected_run_id) then
    return -99
end

local removed = redis.call('ZREM', orderbook_key, order_id)
local deleted = redis.call('DEL', order_id_key)
local unlinked = 0
if user_order_index_enabled then
    unlinked = redis.call('SREM', user_orders_key, order_id)
end

if removed > 0 or deleted > 0 or unlinked > 0 then
    return 1
end

return 0
