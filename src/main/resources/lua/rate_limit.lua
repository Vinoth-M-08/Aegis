-- Sliding-window request limiter backed by a sorted set of request timestamps.
-- KEYS[1]: the window key
-- ARGV: now (ms), window (ms), limit, unique member for this request
-- Returns {1, remaining, 0} when allowed or {0, 0, retry_after_ms} when limited.
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
local count = redis.call('ZCARD', KEYS[1])

if count >= limit then
    local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
    local retry = window
    if oldest[2] then
        retry = tonumber(oldest[2]) + window - now
    end
    return {0, 0, retry}
end

redis.call('ZADD', KEYS[1], now, ARGV[4])
redis.call('PEXPIRE', KEYS[1], window)
return {1, limit - count - 1, 0}
