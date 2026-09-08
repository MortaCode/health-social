--[[
  heavy_keeper.lua —— HeavyKeeper（Heavy Hitters with exponential decay）
  ------------------------------------------------------------------
  论文：HeavyKeeper: An Accurate Algorithm for Finding Top-k Elephant Flows (IEEE/ACM ToN 2019)

  相比 Count-Min Sketch 的优势：
    - Count-Min 只增不减，冷数据长期霸占 TopK；
    - HeavyKeeper 对"不匹配的桶"以概率 1/2^count 做指数衰减，
      真正的大象流（热点文章）能快速顶上来，且误判率低。

  存储结构：
    Hash  hk:table:{hot}   field = "row:col"  value = count * 2^32 + fingerprint
    ZSet  hk:top:{hot}     member = itemId    score  = 估计频次
    （两个 key 使用相同 hash tag {hot}，Redis Cluster 下落在同一 slot，可单脚本原子操作）

  KEYS[1] = hk:table:{hot}
  KEYS[2] = hk:top:{hot}

  ARGV[1] = item       （文章 ID 字符串）
  ARGV[2] = depth      d，行数
  ARGV[3] = width      w，每行桶数
  ARGV[4] = topN       热点榜单大小

  返回：{ estimatedCount, rank }
]]
local tableKey = KEYS[1]
local topKey = KEYS[2]

local item = ARGV[1]
local d = tonumber(ARGV[2])
local w = tonumber(ARGV[3])
local topN = tonumber(ARGV[4])

local FP_BITS = 4294967296  -- 2^32
local MAX_CARRY = 2097152   -- 2^21，避免 Lua double 精度溢出（2^53 / 2^32）

-- 32 位指纹：sha1 前 8 位 hex
local function fingerprint(s)
    return tonumber(string.sub(redis.sha1hex(s), 1, 8), 16)
end

-- 列下标：不同行使用不同盐值
local function column(s, row)
    return (tonumber(string.sub(redis.sha1hex(s .. '#' .. row), 1, 8), 16) % w) + 1
end

local fp = fingerprint(item)
local maxCount = 0

for row = 0, d - 1 do
    local field = row .. ':' .. column(item, row)
    local raw = redis.call('HGET', tableKey, field)

    local count = 0
    local curFp = 0
    if raw then
        local packed = tonumber(raw)
        count = math.floor(packed / FP_BITS)
        curFp = packed - count * FP_BITS
    end

    if count == 0 or curFp == fp then
        -- 空桶 或 命中：计数 +1
        count = count + 1
    else
        -- 冲突：以概率 1 / 2^count 衰减；衰减到 0 则抢占该桶
        local p = 0
        if count < 31 then
            p = 1 / (2 ^ count)
        end
        if math.random() < p then
            count = count - 1
            if count <= 0 then
                count = 1
                curFp = fp
            end
        end
    end

    if count > MAX_CARRY then
        count = MAX_CARRY
    end

    redis.call('HSET', tableKey, field, count * FP_BITS + curFp)

    if count > maxCount then
        maxCount = count
    end
end

-- 维护 TopN 榜单（Redis 6.2+ 支持 GT，仅在分数变大时更新；低版本降级为普通 ZADD）
if topN > 0 and maxCount > 0 then
    local ok = pcall(function()
        redis.call('ZADD', topKey, 'GT', maxCount, item)
    end)
    if not ok then
        redis.call('ZADD', topKey, maxCount, item)
    end

    -- 榜单只保留 2 * topN，避免 ZSET 无限膨胀
    local size = redis.call('ZCARD', topKey)
    local keep = topN * 2
    if size > keep then
        redis.call('ZREMRANGEBYRANK', topKey, 0, size - keep - 1)
    end
end

-- rank：0 表示未进榜
local rank = -1
if topN > 0 then
    local r = redis.call('ZREVRANK', topKey, item)
    if r then
        rank = r
    end
end

return { maxCount, rank }
