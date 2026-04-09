-- get_all_auction_bids.lua
-- Retrieves all buy and sell bids for an auction
--
-- KEYS[1]: auction:{auctionId}:bids:buy (List)
-- KEYS[2]: auction:{auctionId}:bids:sell (List)
--
-- Returns: multi-bulk reply with two arrays [buyBids, sellBids]

local buy_bids_key = KEYS[1]
local sell_bids_key = KEYS[2]

local buys = redis.call('LRANGE', buy_bids_key, 0, -1)
local sells = redis.call('LRANGE', sell_bids_key, 0, -1)

return {buys, sells}
