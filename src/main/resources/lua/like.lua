--[[
  like.lua —— 点赞 / 取消点赞 原子脚本
  ------------------------------------------------------------------
  目标：
    1) 维护 article:like:count:{articleId}  （计数器）
    2) 维护 user:like:set:{userId}          （用户点过的文章集合，幂等判重）
    3) 维护 article:like:users:{articleId}  （文章被谁点过，异步落库 / 反查用，可选）

  KEYS[1] = article:like:count:{articleId}
  KEYS[2] = user:like:set:{userId}
  KEYS[3] = article:like:users:{articleId}

  ARGV[1] = articleId
  ARGV[2] = op         '1' = 点赞   '0' = 取消点赞
  ARGV[3] = userId     （可为空串，表示匿名/不发号场景，跳过 article:like:users）
  ARGV[4] = countTtl   （计数器兜底过期时间，秒）
  ARGV[5] = setTtl     （用户点赞集合过期时间，秒；0 表示不过期）

  返回：{ liked, count, changed }
        liked   : 1=当前已点赞 0=当前未点赞
        count   : 最新点赞数
        changed : 1=状态发生变化（需要写 MQ） 0=重复操作（直接丢弃，不落库）
]]
local countKey = KEYS[1]
local userSetKey = KEYS[2]
local artUserKey = KEYS[3]

local articleId = ARGV[1]
local op = ARGV[2]
local userId = ARGV[3]
local countTtl = tonumber(ARGV[4] or '0') or 0
local setTtl = tonumber(ARGV[5] or '0') or 0

local liked = 0
local changed = 0
local delta = 0

if op == '1' then
    -- SADD 返回 1 表示新增成功，0 表示已存在（幂等：重复点赞不计数）
    local added = redis.call('SADD', userSetKey, articleId)
    if added == 1 then
        liked = 1
        delta = 1
        changed = 1
        if userId ~= '' then
            redis.call('SADD', artUserKey, userId)
        end
    else
        liked = 1
        changed = 0
    end
else
    -- SREM 返回 1 表示原本存在并删除成功
    local removed = redis.call('SREM', userSetKey, articleId)
    if removed == 1 then
        liked = 0
        delta = -1
        changed = 1
        if userId ~= '' then
            redis.call('SREM', artUserKey, userId)
        end
    else
        liked = 0
        changed = 0
    end
end

local count = tonumber(redis.call('GET', countKey) or '0')

if delta ~= 0 then
    count = redis.call('INCRBY', countKey, delta)
    -- 防御：计数被压成负数（如历史数据不一致），回滚本次操作
    if count < 0 then
        count = redis.call('INCRBY', countKey, -delta)
        changed = 0
    end
end

-- 兜底过期：防止冷门 key 长期占用内存（热点 key 会被持续续期）
if countTtl > 0 then
    redis.call('EXPIRE', countKey, countTtl)
end
if setTtl > 0 then
    redis.call('EXPIRE', userSetKey, setTtl)
    if userId ~= '' then
        redis.call('EXPIRE', artUserKey, setTtl)
    end
end

return { liked, count, changed }
