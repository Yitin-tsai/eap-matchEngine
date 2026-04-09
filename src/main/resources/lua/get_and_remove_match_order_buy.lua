-- get_and_remove_match_order_buy.lua
-- Atomically finds the best sell order for a buy order, removes it from orderbook,
-- retrieves its details, and deletes the order data
--
-- For BUY orders: finds sell orders with price <= buy price (lowest sell price first)
--
-- KEYS[1]: orderbook ZSet key (e.g., "orderbook:sell")
-- ARGV[1]: max price (buy order's price limit)
--
-- Returns: order JSON string, or nil if no match found

local orderbook_key = KEYS[1]
local max_price = tonumber(ARGV[1])

-- Find the best matching order (lowest sell price)
local orders = redis.call('ZRANGEBYSCORE', orderbook_key, '-inf', max_price, 'LIMIT', 0, 1)

if #orders == 0 then
    return nil
end

local order_id = orders[1]
local order_id_key = 'order:' .. order_id

-- Atomically:
-- 1. Remove from orderbook
redis.call('ZREM', orderbook_key, order_id)

-- 2. Get order details
local order_json = redis.call('GET', order_id_key)

-- 3. Delete order details (will be recreated if partially matched)
redis.call('DEL', order_id_key)

-- Note: We don't remove from user:orders here because:
-- - If fully matched: order will be removed by removeOrder()
-- - If partially matched: order will be re-added by addOrder()

return order_json
