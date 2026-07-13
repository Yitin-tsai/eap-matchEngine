-- get_and_remove_match_order_sell.lua
-- Atomically finds the best buy order for a sell order, removes it from orderbook,
-- retrieves its details, and deletes the order data
--
-- For SELL orders: finds buy orders with price >= sell price (highest buy price first)
--
-- KEYS[1]: orderbook ZSet key (e.g., "orderbook:buy")
-- ARGV[1]: min composite score (sell order's price limit)
--
-- Returns: order JSON string, or nil if no match found

local orderbook_key = KEYS[1]
local min_score = tonumber(ARGV[1])

-- Find the best matching order (highest buy price)
local orders = redis.call('ZREVRANGEBYSCORE', orderbook_key, '+inf', min_score, 'LIMIT', 0, 1)

if #orders == 0 then
    return nil
end

local order_id = orders[1]
local order_id_key = 'order:' .. order_id

-- Atomically:
-- 1. Get order details before removing the orderbook entry. If Redis evicted or
-- otherwise lost the detail key, returning nil would make the caller treat this
-- as a normal no-match and silently convert the incoming order into resting
-- liquidity.
local order_json = redis.call('GET', order_id_key)

if not order_json then
    redis.call('ZREM', orderbook_key, order_id)
    return '__MISSING_ORDER_DETAIL__:' .. order_id
end

-- 2. Remove from orderbook
redis.call('ZREM', orderbook_key, order_id)

-- 3. Delete order details (will be recreated if partially matched)
redis.call('DEL', order_id_key)

-- Note: We don't remove from user:orders here because:
-- - If fully matched: order will be removed by removeOrder()
-- - If partially matched: order will be re-added by addOrder()

return order_json
