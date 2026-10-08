-- =====================================================================
--  推荐模块 · 兴趣分原子加减（带上下界裁剪）
-- =====================================================================
--  为什么必须用 Lua：
--    "读当前兴趣分 → 加减 → 判断是否越界 → 决定 HSET 还是 HDEL" 是典型的
--    check-then-act。拆成 HGET + HSET 两条命令，在"同一用户并发点击/点赞 +
--    定时衰减任务"同时发生时，会出现丢失更新（两个请求都读到旧值，后写的覆盖先写的），
--    以及兴趣分被减到负数、或超出上限后无法收敛。
--    Redis 单线程执行 Lua，是唯一能做到"读-改-写 + 裁剪 + 过期续期"原子的方式。
--
--  KEYS[1] = rec:interest:{userId}   (Hash: tagId -> 兴趣分)
--  ARGV[1] = tagId
--  ARGV[2] = delta        （正数=加强兴趣，负数=削弱兴趣）
--  ARGV[3] = cap          （兴趣分上限）
--  ARGV[4] = ttlSeconds   （0 表示不续期）
--
--  返回：裁剪后的兴趣分（字符串形式的浮点数）
-- =====================================================================

local key   = KEYS[1]
local tag   = ARGV[1]
local delta = tonumber(ARGV[2]) or 0
local cap   = tonumber(ARGV[3]) or 10
local ttl   = tonumber(ARGV[4]) or 0

local cur = tonumber(redis.call('HGET', key, tag) or '0')

local v = cur + delta
if v < 0 then
  v = 0
elseif v > cap then
  v = cap
end

-- 归零即删除字段：避免 Hash 里堆积大量 score=0 的僵尸标签
-- （否则兴趣召回取 TopK 时会被这些无效标签占位）
if v <= 0 then
  redis.call('HDEL', key, tag)
else
  redis.call('HSET', key, tag, tostring(v))
end

if ttl > 0 then
  redis.call('EXPIRE', key, ttl)
end

return tostring(v)
