-- get_and_remove_match_order_sell.lua
-- Atomically finds the best buy order for a sell order, removes it from orderbook,
-- retrieves its details, and deletes the order data
--
-- For SELL orders: finds buy orders with price >= sell price (highest buy price first)
--
-- KEYS[1]: orderbook ZSet key (e.g., "orderbook:buy")
-- ARGV[1]: min price (sell order's price limit)
--
-- Returns: order JSON string, or nil if no match found

local orderbook_key = KEYS[1]
local min_price = tonumber(ARGV[1])

-- Find the best matching order (highest buy price)
local orders = redis.call('ZREVRANGEBYSCORE', orderbook_key, '+inf', min_price, 'LIMIT', 0, 1)

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
