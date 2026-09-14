-- add_order.lua
-- Atomically adds an order to the orderbook with all related data
--
-- KEYS[1]: orderbook ZSet key (e.g., "orderbook:buy")
-- KEYS[2]: order detail key (e.g., "order:uuid")
-- KEYS[3]: user orders Set key (e.g., "user:uuid:orders")
-- KEYS[4]: CDA order-book generation sentinel
--
-- ARGV[1]: order ID (string)
-- ARGV[2]: score (number, composite price + sequence score)
-- ARGV[3]: order JSON (string)
-- ARGV[4]: maintain user open-order index flag ("1" or "0")
-- ARGV[5]: expected generation sentinel (empty only for isolated legacy tests)
--
-- Returns: 1 on success

local orderbook_key = KEYS[1]
local order_id_key = KEYS[2]
local user_orders_key = KEYS[3]

local order_id = ARGV[1]
local score = tonumber(ARGV[2])
local order_json = ARGV[3]
local user_order_index_enabled = ARGV[4] ~= '0'

local expected_run_id = string.match(ARGV[5], '|([^|]+)$')
local actual_run_id = string.match(redis.call('INFO', 'server'), 'run_id:([^\r\n]+)')
if ARGV[5] ~= '' and (redis.call('GET', KEYS[4]) ~= ARGV[5] or actual_run_id ~= expected_run_id) then
    return -99
end

-- All three operations are atomic within this Lua script
redis.call('ZADD', orderbook_key, score, order_id)
redis.call('SET', order_id_key, order_json)
if user_order_index_enabled then
    redis.call('SADD', user_orders_key, order_id)
end

return 1
