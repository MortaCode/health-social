--[[
  token_bucket.lua —— 分布式令牌桶（RateLimiter）
  ------------------------------------------------------------------
  惰性填充：不启动后台线程，按时间差计算应补充的令牌数。

  KEYS[1] = rl:token:{userId}:{api}

  ARGV[1] = rate        每秒生成令牌数
  ARGV[2] = capacity    桶容量（突发上限）
  ARGV[3] = nowMs       当前时间戳（毫秒，由应用传入，避免多机时钟问题）
  ARGV[4] = requested   本次请求令牌数，通常 1
  ARGV[5] = ttlSeconds  key 过期时间（秒），最后一个令牌消耗后仍保留一段时间以观察

  返回：{ allowed, remain, retryAfterMs }
        allowed = 1 放行 / 0 拒绝
]]
local key = KEYS[1]

local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local nowMs = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])
local ttlSeconds = tonumber(ARGV[5])

local bucket = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts = tonumber(bucket[2])

if tokens == nil or ts == nil then
    tokens = capacity
    ts = nowMs
end

-- 时钟回拨保护
if nowMs > ts then
    local deltaMs = nowMs - ts
    local refill = deltaMs / 1000.0 * rate
    tokens = math.min(capacity, tokens + refill)
    ts = nowMs
elseif nowMs < ts then
    -- 客户端时钟回拨：不补令牌，也不回退时间戳
    ts = ts
end

local allowed = 0
local retryAfterMs = 0

if tokens >= requested then
    allowed = 1
    tokens = tokens - requested
else
    -- 还需多久才能凑够令牌
    retryAfterMs = math.ceil((requested - tokens) / rate * 1000)
end

redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(ts))
redis.call('EXPIRE', key, ttlSeconds)

return { allowed, math.floor(tokens), retryAfterMs }
