-- Atomically arbitrates cancellation against matching for one visible resting order.
-- KEYS[1]: orderbook ZSET
-- KEYS[2]: order detail key
-- KEYS[3]: user open-order set
-- KEYS[4]: cancellation marker key
-- ARGV[1]: order ID
-- ARGV[2]: maintain user open-order index (1/0)
-- ARGV[3]: cancellation ID
-- Returns {'__CANCELLED__', order JSON} for a new cancellation,
-- {'__DUPLICATE__', order JSON} for the same cancellation retry, or
-- {'__NOT_OPEN__'} when matching already removed the order from the visible ZSET.

local existing_cancellation_id = redis.call('HGET', KEYS[4], 'cancellationId')
if existing_cancellation_id == ARGV[3] then
    return {'__DUPLICATE__', redis.call('HGET', KEYS[4], 'order')}
end

local order_json = redis.call('GET', KEYS[2])
if not order_json then
    return {'__NOT_OPEN__'}
end

-- The detail key remains while a resting order is reserved for a match. Requiring
-- ZREM=1 is what makes cancellation and matching a single atomic arbitration.
if redis.call('ZREM', KEYS[1], ARGV[1]) ~= 1 then
    return {'__NOT_OPEN__'}
end
redis.call('DEL', KEYS[2])
if ARGV[2] ~= '0' then
    redis.call('SREM', KEYS[3], ARGV[1])
end
redis.call('HSET', KEYS[4], 'cancellationId', ARGV[3], 'order', order_json)
redis.call('EXPIRE', KEYS[4], 604800)
return {'__CANCELLED__', order_json}
