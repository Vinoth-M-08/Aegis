-- Adjusts reserved counters by the difference between actual and reserved tokens.
-- KEYS: the counter keys used for the reservation
-- ARGV[1]: delta (actual - reserved; negative releases tokens)
-- Counters that have expired (e.g. the day rolled over) are left alone.
local delta = tonumber(ARGV[1])

for _, key in ipairs(KEYS) do
    if redis.call('EXISTS', key) == 1 then
        local value = redis.call('INCRBY', key, delta)
        if value < 0 then
            redis.call('SET', key, 0, 'KEEPTTL')
        end
    end
end

return 1
