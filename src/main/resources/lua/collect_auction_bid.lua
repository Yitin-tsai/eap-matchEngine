-- collect_auction_bid.lua
-- Atomically collects a bid for a sealed-bid auction
--
-- KEYS[1]: auction:{auctionId}:bids:{side} (List - stores bid JSON entries)
-- KEYS[2]: auction:{auctionId}:participants (Set - tracks unique participants)
-- KEYS[3]: auction:{auctionId}:config (Hash - auction configuration with 'status' field)
--
-- ARGV[1]: userId (string)
-- ARGV[2]: bidJson (string - serialized bid data)
--
-- Returns:
--   1  = success
--  -1  = gate closed (auction not OPEN)
--  -2  = duplicate bid (userId already submitted)

local bids_key = KEYS[1]
local participants_key = KEYS[2]
local config_key = KEYS[3]

local user_id = ARGV[1]
local bid_json = ARGV[2]

-- Check auction status
local status = redis.call('HGET', config_key, 'status')
if status ~= 'OPEN' then
    return -1
end

-- Check for duplicate bid from same user
if redis.call('SISMEMBER', participants_key, user_id) == 1 then
    return -2
end

-- Atomically add bid and register participant
redis.call('RPUSH', bids_key, bid_json)
redis.call('SADD', participants_key, user_id)

return 1
