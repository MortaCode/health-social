package com.health.social.controller;

import com.health.social.cache.CacheService;
import com.health.social.cache.CacheWarmupService;
import com.health.social.cache.HeavyKeeperDetector;
import com.health.social.common.RedisKeys;
import com.health.social.common.Result;
import com.health.social.like.LikeConsumer;
import lombok.Data;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 缓存与热点运维接口。
 */
@RestController
@RequestMapping("/cache")
public class CacheController {

    private final CacheService cacheService;
    private final CacheWarmupService warmupService;
    private final HeavyKeeperDetector detector;
    private final LikeConsumer likeConsumer;
    private final StringRedisTemplate redis;

    public CacheController(CacheService cacheService,
                           CacheWarmupService warmupService,
                           HeavyKeeperDetector detector,
                           LikeConsumer likeConsumer,
                           StringRedisTemplate redis) {
        this.cacheService = cacheService;
        this.warmupService = warmupService;
        this.detector = detector;
        this.likeConsumer = likeConsumer;
        this.redis = redis;
    }

    /**
     * 缓存预热：把热点榜 TopN 灌入 Redis + 本地缓存，并广播给集群其他节点
     */
    @PostMapping("/warmup")
    public Result<Integer> warmup(@RequestParam(required = false) Integer topN,
                                  @RequestParam(defaultValue = "true") boolean broadcast) {
        int n = warmupService.warmupHotArticles(topN == null ? 100 : topN, broadcast);
        return Result.ok(n);
    }

    /**
     * 按指定文章 ID 预热
     */
    @PostMapping("/warmup/ids")
    public Result<Integer> warmupByIds(@RequestBody(required = false) List<Long> ids) {
        return Result.ok(warmupService.warmupByIds(ids, true));
    }

    /**
     * 缓存指标：本地命中率是关键 KPI
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        Map<String, Object> m = cacheService.stats();
        m.put("likeFlushedRows", likeConsumer.getFlushedRows());
        m.put("likeFlushedBatches", likeConsumer.getFlushedBatches());
        return Result.ok(m);
    }

    @PostMapping("/stats/reset")
    public Result<String> resetStats() {
        cacheService.resetStats();
        return Result.ok("ok");
    }

    /**
     * 热点榜
     */
    @GetMapping("/hot")
    public Result<List<HotItem>> hot(@RequestParam(defaultValue = "100") int topN) {
        Set<ZSetOperations.TypedTuple<String>> tuples = detector.topNWithScore("article", topN);
        List<HotItem> items = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            HotItem i = new HotItem();
            i.setItem(t.getValue());
            i.setScore(t.getScore() == null ? 0 : t.getScore().longValue());
            items.add(i);
        }
        return Result.ok(items);
    }

    /**
     * 手动失效（跨节点广播）
     */
    @PostMapping("/evict/{articleId}")
    public Result<String> evict(@PathVariable Long articleId) {
        cacheService.invalidate(RedisKeys.articleCache(articleId));
        return Result.ok("ok");
    }

    /**
     * 强制刷盘（观察聚合效果 / 优雅停机前调用）
     */
    @PostMapping("/like/flush")
    public Result<Integer> flushLike() {
        return Result.ok(likeConsumer.flush());
    }

    @GetMapping("/ping")
    public Result<String> ping() {
        redis.hasKey("__ping__");
        return Result.ok("pong");
    }

    @Data
    public static class HotItem {

        private String item;
        private long score;
    }
}
