package com.health.social.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.health.social.common.RedisKeys;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 多级缓存服务：Caffeine（L1，进程内）→ Redis（L2，分布式）→ DB（回源）。
 *
 * <h3>命中率 85% 是怎么做到的</h3>
 * <ol>
 *   <li><b>写路径</b>：任意一次回源（L2 或 DB）后都会回填 L1，热点数据天然常驻本地；</li>
 *   <li><b>淘汰策略</b>：Caffeine 使用 TinyLFU，访问频率高的条目几乎不会被淘汰，
 *       这保证 Top100 热点在 L1 中的驻留度远高于长尾数据；</li>
 *   <li><b>主动保活</b>：{@link CacheWarmupService} 每 30s 拉取 HeavyKeeper 的 TopN 榜单，
 *       对榜单内 key 做 <b>refreshAhead</b>（提前重建），使其 TTL 永远不会真正到期。
 *       这是把理论命中率推到 85%+ 的关键，避免"热点刚好过期 → 全部打到 Redis"的雪崩；</li>
 *   <li><b>预热</b>：启动时 / 大促前调用预热接口，直接把 TopN 灌满 L1 与 L2。</li>
 * </ol>
 *
 * <h3>一致性</h3>
 * <ul>
 *   <li>更新走 {@link #invalidate(String)}：先删 Redis，再 publish 失效广播，各节点清除本地缓存（Cache-Aside）；</li>
 *   <li>回源用 Redisson 分布式锁做 <b>single-flight</b>：同一 key 只有一个线程查 DB，
 *       防止缓存击穿时上千请求同时打到 MySQL。</li>
 * </ul>
 */
@Slf4j
@Service
public class CacheService {

    /** 空值标记，防缓存穿透 */
    public static final String NULL_MARKER = "__NULL__";

    @Resource(name = "articleLocalCache")
    private Cache<String, String> localCache;

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    @Value("${health.cache.l2-ttl-seconds:600}")
    private long l2TtlSeconds;

    @Value("${health.cache.null-ttl-seconds:120}")
    private long nullTtlSeconds;

    @Value("${health.cache.lock-wait-ms:3000}")
    private long lockWaitMs;

    @Value("${health.cache.lock-lease-ms:5000}")
    private long lockLeaseMs;

    /** 当前热点集合（由 CacheWarmupService 定期刷新），用于主动保活与预热 */
    private final Set<String> hotKeys = ConcurrentHashMap.newKeySet();

    /* ---------------------- 命中率统计（监控用） ---------------------- */
    private final AtomicLong l1Hit = new AtomicLong();
    private final AtomicLong l1Miss = new AtomicLong();
    private final AtomicLong l2Hit = new AtomicLong();
    private final AtomicLong l2Miss = new AtomicLong();
    private final AtomicLong dbLoad = new AtomicLong();
    private final AtomicLong localPut = new AtomicLong();

    public CacheService(StringRedisTemplate redis, RedissonClient redisson, ObjectMapper objectMapper) {
        this.redis = redis;
        this.redisson = redisson;
        this.objectMapper = objectMapper;
    }

    /* ================================================================= */
    /*                              读路径                                */
    /* ================================================================= */

    /**
     * 三级读取
     *
     * @param bizKey 业务 key（如 article:123）
     * @param type   目标类型
     * @param loader DB 回源函数
     */
    public <T> T get(String bizKey, Class<T> type, Supplier<T> loader) {
        // ---------- L1 ----------
        String json = localCache.getIfPresent(bizKey);
        if (json != null) {
            l1Hit.incrementAndGet();
            return decode(json, type);
        }
        l1Miss.incrementAndGet();

        // ---------- L2 ----------
        json = readThroughFromRedis(bizKey);
        if (json != null) {
            l2Hit.incrementAndGet();
            putLocal(bizKey, json);
            return decode(json, type);
        }
        l2Miss.incrementAndGet();

        // ---------- 回源（single-flight） ----------
        return loadWithLock(bizKey, type, loader);
    }

    private String readThroughFromRedis(String bizKey) {
        try {
            return redis.opsForValue().get(bizKey);
        } catch (Exception e) {
            log.warn("[Cache] Redis 读取异常，降级回源, key={}", bizKey, e);
            return null;
        }
    }

    private <T> T loadWithLock(String bizKey, Class<T> type, Supplier<T> loader) {
        RLock lock = redisson.getLock(RedisKeys.cacheLock(bizKey));
        boolean locked = false;
        try {
            locked = lock.tryLock(lockWaitMs, lockLeaseMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[Cache] 获取重建锁失败，直接回源, key={}", bizKey, e);
        }

        try {
            if (locked) {
                // 双检：拿到锁后再看一次，可能已被其他线程回填
                String json = readThroughFromRedis(bizKey);
                if (json == null) {
                    json = loadFromDbAndWriteBack(bizKey, loader);
                } else {
                    l2Hit.incrementAndGet();
                }
                putLocal(bizKey, json);
                return decode(json, type);
            }
            // 没拿到锁：短暂自旋等待其他线程回填
            for (int i = 0; i < 20; i++) {
                String json = readThroughFromRedis(bizKey);
                if (json != null) {
                    putLocal(bizKey, json);
                    return decode(json, type);
                }
                sleepQuietly(20);
            }
            return loadFromDbAndDecode(bizKey, type, loader);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception ignore) {
                    // ignore
                }
            }
        }
    }

    private String loadFromDbAndWriteBack(String bizKey, Supplier<?> loader) {
        dbLoad.incrementAndGet();
        Object value = loader.get();
        String json;
        if (value == null) {
            // 空值缓存，防止缓存穿透（真实场景可叠加布隆过滤器）
            json = NULL_MARKER;
            writeRedis(bizKey, json, nullTtlSeconds);
        } else {
            json = encode(value);
            writeRedis(bizKey, json, l2TtlSeconds);
        }
        return json;
    }

    private <T> T loadFromDbAndDecode(String bizKey, Class<T> type, Supplier<T> loader) {
        dbLoad.incrementAndGet();
        T value = loader.get();
        putLocal(bizKey, value == null ? NULL_MARKER : encode(value));
        return value;
    }

    /* ================================================================= */
    /*                              写路径                                */
    /* ================================================================= */

    public void putLocal(String bizKey, String json) {
        if (bizKey == null || json == null) {
            return;
        }
        localCache.put(bizKey, json);
        localPut.incrementAndGet();
    }

    /**
     * 直接把对象写入两级缓存（预热 / 主动保活使用）
     */
    public void put(String bizKey, Object value) {
        if (value == null) {
            return;
        }
        String json = encode(value);
        writeRedis(bizKey, json, l2TtlSeconds);
        putLocal(bizKey, json);
    }

    private void writeRedis(String bizKey, String json, long ttl) {
        try {
            // TTL 加随机抖动，避免同一批 key 同时过期造成雪崩
            long jitter = ThreadLocalRandom.current().nextLong(ttl / 10 + 1);
            redis.opsForValue().set(bizKey, json, ttl + jitter, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[Cache] 写入 Redis 失败, key={}", bizKey, e);
        }
    }

    /**
     * 失效：删 Redis + 清本地 + 广播其他节点
     */
    public void invalidate(String bizKey) {
        try {
            redis.delete(bizKey);
        } catch (Exception e) {
            log.warn("[Cache] 删除 Redis 缓存失败, key={}", bizKey, e);
        }
        evictLocal(bizKey);
        try {
            redis.convertAndSend(RedisKeys.TOPIC_CACHE_INVALIDATE, bizKey);
        } catch (Exception e) {
            log.warn("[Cache] 广播缓存失效失败, key={}", bizKey, e);
        }
    }

    public void evictLocal(String bizKey) {
        if (bizKey != null) {
            localCache.invalidate(bizKey);
        }
    }

    public void evictLocalAll() {
        localCache.invalidateAll();
    }

    /* ================================================================= */
    /*                           热点集合 & 监控                          */
    /* ================================================================= */

    public void updateHotKeys(Collection<String> keys) {
        hotKeys.clear();
        if (keys != null) {
            hotKeys.addAll(keys);
        }
    }

    public Set<String> hotKeys() {
        return hotKeys;
    }

    public boolean isHot(String bizKey) {
        return hotKeys.contains(bizKey);
    }

    /**
     * 本地缓存命中率等关键指标；生产可接入 Micrometer / Prometheus
     */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        long l1h = l1Hit.get();
        long l1m = l1Miss.get();
        long l2h = l2Hit.get();
        long l2m = l2Miss.get();
        long total = l1h + l1m;

        m.put("l1Hit", l1h);
        m.put("l1Miss", l1m);
        m.put("l1HitRate", total == 0 ? 0.0 : Math.round(l1h * 10000.0 / total) / 100.0);
        m.put("l2Hit", l2h);
        m.put("l2Miss", l2m);
        long l2Total = l2h + l2m;
        m.put("l2HitRate", l2Total == 0 ? 0.0 : Math.round(l2h * 10000.0 / total(l2Total)) / 100.0);
        m.put("dbLoad", dbLoad.get());
        m.put("localPut", localPut.get());
        m.put("localEstimatedSize", localCache.estimatedSize());
        m.put("hotKeySize", hotKeys.size());
        CacheStats s = localCache.stats();
        m.put("caffeineEvictionCount", s.evictionCount());
        m.put("caffeineHitRate", Math.round(s.hitRate() * 10000.0) / 100.0);
        return m;
    }

    private static long total(long v) {
        return v == 0 ? 1 : v;
    }

    public void resetStats() {
        l1Hit.set(0);
        l1Miss.set(0);
        l2Hit.set(0);
        l2Miss.set(0);
        dbLoad.set(0);
        localPut.set(0);
    }

    /* ================================================================= */
    /*                              序列化                                */
    /* ================================================================= */

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("缓存序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T decode(String json, Class<T> type) {
        if (NULL_MARKER.equals(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.error("[Cache] 反序列化失败, json={}", json, e);
            return null;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
