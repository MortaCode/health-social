package com.health.social.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.health.social.common.RedisKeys;
import com.health.social.entity.Article;
import com.health.social.mapper.ArticleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 缓存预热 + 热点主动保活。
 *
 * <h3>三档预热能力</h3>
 * <ol>
 *   <li><b>接口预热</b>：{@code POST /cache/warmup?topN=100}，运营/发布系统调用，
 *       从热点榜单或指定 ID 列表把数据灌入 L2 + L1，并通过 Pub/Sub 通知集群内所有节点一起预热；</li>
 *   <li><b>启动预热</b>：应用启动后延迟 10s 自动预热一次 TopN；</li>
 *   <li><b>周期保活</b>：每 30s 刷新热点集合，对 TopN 做 refreshAhead，
 *       让热点数据的一级缓存 TTL 永不真正到期 —— 这是本地命中率稳定在 85%+ 的核心手段。</li>
 * </ol>
 */
@Slf4j
@Service
public class CacheWarmupService {

    private final CacheService cacheService;
    private final HeavyKeeperDetector detector;
    private final ArticleMapper articleMapper;
    private final StringRedisTemplate redis;
    private final Executor executor;

    @Value("${health.hot.top-n:100}")
    private int defaultTopN;

    public CacheWarmupService(CacheService cacheService,
                              HeavyKeeperDetector detector,
                              ArticleMapper articleMapper,
                              StringRedisTemplate redis,
                              @org.springframework.beans.factory.annotation.Qualifier("commonExecutor") Executor executor) {
        this.cacheService = cacheService;
        this.detector = detector;
        this.articleMapper = articleMapper;
        this.redis = redis;
        this.executor = executor;
    }

    /* ================================================================= */
    /*                            对外预热接口                            */
    /* ================================================================= */

    /**
     * 预热热点文章榜 TopN
     *
     * @param topN     预热条数
     * @param broadcast 是否通知集群内其他节点一起预热
     * @return 实际预热条数
     */
    public int warmupHotArticles(int topN, boolean broadcast) {
        if (topN <= 0) {
            topN = defaultTopN;
        }
        List<Long> ids = parseIds(detector.topN("article", topN));
        if (ids.isEmpty()) {
            log.info("[Warmup] 热点榜单为空，跳过预热");
            return 0;
        }
        int n = warmupByIds(ids, true);

        if (broadcast) {
            try {
                redis.convertAndSend(RedisKeys.TOPIC_CACHE_WARMUP, "WARMUP:" + topN);
            } catch (Exception e) {
                log.warn("[Warmup] 广播预热事件失败", e);
            }
        }
        return n;
    }

    /**
     * 按指定文章 ID 预热（支持指定榜单，比如运营配置的置顶健康科普）
     */
    public int warmupByIds(List<Long> ids, boolean fillLocal) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        // 大批量预热分批，避免一次 SQL 过大
        int batch = 500;
        int success = 0;
        for (int i = 0; i < ids.size(); i += batch) {
            List<Long> sub = ids.subList(i, Math.min(i + batch, ids.size()));
            List<Article> articles = articleMapper.selectList(
                    new LambdaQueryWrapper<Article>().in(Article::getId, sub));
            for (Article a : articles) {
                String bizKey = RedisKeys.articleCache(a.getId());
                cacheService.put(bizKey, a);
                success++;
            }
            if (fillLocal) {
                Set<String> hotKeys = new HashSet<>();
                for (Article a : articles) {
                    hotKeys.add(RedisKeys.articleCache(a.getId()));
                }
                cacheService.updateHotKeys(hotKeys);
            }
        }
        log.info("[Warmup] 完成预热 {} 条, 本地缓存条目数 = {}", success, cacheService.hotKeys().size());
        return success;
    }

    /* ================================================================= */
    /*                          周期保活 & 启动预热                        */
    /* ================================================================= */

    /**
     * 周期刷新热点集合，并对 TopN 做 refreshAhead。
     * 固定延迟 30s；生产环境建议多节点错开（可用 Redisson 分布式锁选主执行）。
     */
    @Scheduled(initialDelay = 15_000, fixedDelayString = "${health.hot.refresh-interval-ms:30000}")
    public void refreshHotKeys() {
        try {
            List<String> topIds = detector.topN("article", defaultTopN);
            List<Long> ids = parseIds(topIds);
            if (ids.isEmpty()) {
                return;
            }
            Set<String> hotBizKeys = new HashSet<>(ids.size() * 2);
            for (Long id : ids) {
                hotBizKeys.add(RedisKeys.articleCache(id));
            }
            cacheService.updateHotKeys(hotBizKeys);

            // refreshAhead：对榜单内 key 重新查库并回填，使 TTL 重置
            // （异步执行，避免定时任务阻塞）
            List<Long> finalIds = ids;
            executor.execute(() -> {
                for (Long id : finalIds) {
                    String bizKey = RedisKeys.articleCache(id);
                    Article a = articleMapper.selectById(id);
                    if (a != null) {
                        cacheService.put(bizKey, a);
                    }
                }
            });
        } catch (Exception e) {
            log.warn("[Warmup] 周期刷新热点失败", e);
        }
    }

    /**
     * 启动预热：容器就绪后延迟 10s 执行，避免与启动期的其他初始化抢占资源
     */
    @Scheduled(initialDelay = 10_000, fixedDelay = Long.MAX_VALUE / 2)
    public void warmupOnStartup() {
        try {
            log.info("[Warmup] 启动预热开始, topN={}", defaultTopN);
            warmupHotArticles(defaultTopN, false);
        } catch (Exception e) {
            log.warn("[Warmup] 启动预热失败（不影响启动）", e);
        }
    }

    /* ================================================================= */

    private List<Long> parseIds(List<String> raw) {
        List<Long> ids = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignore) {
                // HeavyKeeper 的 member 理论上就是 ID 字符串，异常数据直接跳过
            }
        }
        return ids;
    }
}
