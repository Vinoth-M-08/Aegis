-- Atomically checks and reserves tokens against several budget counters.
-- KEYS:  counter keys (e.g. tenant daily, tenant monthly, key daily)
-- ARGV[1]: tokens to reserve
-- ARGV[2*i], ARGV[2*i+1]: limit (-1 = unlimited) and TTL in seconds for KEYS[i]
-- Returns {1, 0, remaining} on success (remaining = -1 when nothing is limited)
--      or {0, i, available} when KEYS[i] would be exceeded.
local amount = tonumber(ARGV[1])

for i, key in ipairs(KEYS) do
    local limit = tonumber(ARGV[2 * i])
    if limit >= 0 then
        local used = tonumber(redis.call('GET', key) or '0')
        if used + amount > limit then
            return {0, i, limit - used}
        end
    end
end

local remaining = -1
for i, key in ipairs(KEYS) do
    local limit = tonumber(ARGV[2 * i])
    local ttl = tonumber(ARGV[2 * i + 1])
    local used = redis.call('INCRBY', key, amount)
    if redis.call('TTL', key) < 0 then
        redis.call('EXPIRE', key, ttl)
    end
    if limit >= 0 then
        local left = limit - used
        if remaining < 0 or left < remaining then
            remaining = left
        end
    end
end

return {1, 0, remaining}
