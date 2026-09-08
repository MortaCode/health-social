package com.health.social.cache;

import com.health.social.common.RedisKeys;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * HeavyKeeper 热点探测器（Top-K Elephant Flow）。
 *
 * <h3>为什么不是 Count-Min Sketch / PFCOUNT</h3>
 * <ul>
 *   <li><b>PFCOUNT(HyperLogLog)</b>：只能算 UV 基数，无法给出"某篇文章被访问了多少次"，做不了 TopK。</li>
 *   <li><b>Count-Min Sketch</b>：只增不减。热点文章过了生命周期后，它的计数仍然躺在桶里，
 *       新热点要花很久才能挤进 TopK，且长尾key 的哈希碰撞会把冷门文章顶成"伪热点"。</li>
 *   <li><b>HeavyKeeper</b>：冲突时以概率 {@code 1/2^count} 对计数做指数衰减，计数衰减到 0 就抢占该桶。
 *       大流量 key 的计数衰减概率极低（2^-1000 ≈ 0），冷门 key 很快被挤出，
 *       在极小的内存下（d=4, w=100000 ≈ 3.2MB）就能得到高精度 TopK。</li>
 * </ul>
 *
 * <p>实现载体：Redis Hash（桶数组）+ Redis ZSet（TopK 榜单），一次 Lua 调用完成，
 * 天然支持多实例共享，无本地状态。
 */
@Slf4j
@Component
public class HeavyKeeperDetector {

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;

    /** 哈希行数 */
    @Value("${health.hot.depth:4}")
    private int depth;

    /** 每行桶数 */
    @Value("${health.hot.width:100000}")
    private int width;

    /** 榜单大小 */
    @Value("${health.hot.top-n:100}")
    private int topN;

    public HeavyKeeperDetector(StringRedisTemplate redis) {
        this.redis = redis;
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource("lua/heavy_keeper.lua"));
        s.setResultType(List.class);
        this.script = s;
    }

    /**
     * 记录一次访问
     *
     * @param type 业务类型
     * @param item 被统计对象（文章 ID）
     * @return 探测结果
     */
    public DetectResult add(String type, String item) {
        if (item == null || item.isEmpty()) {
            return DetectResult.EMPTY;
        }
        try {
            List<Long> res = redis.execute(script,
                    List.of(RedisKeys.hkTable(type), RedisKeys.hkTop(type)),
                    item, String.valueOf(depth), String.valueOf(width), String.valueOf(topN));
            if (res == null || res.size() < 2) {
                return DetectResult.EMPTY;
            }
            long count = res.get(0) == null ? 0 : res.get(0);
            long rank = res.get(1) == null ? -1 : res.get(1);
            return new DetectResult(count, rank, rank >= 0 && rank < topN);
        } catch (Exception e) {
            // 探测属于旁路逻辑，绝不能影响主流程
            log.warn("[HeavyKeeper] 统计失败, type={}, item={}", type, item, e);
            return DetectResult.EMPTY;
        }
    }

    /**
     * 采样上报：极高 QPS 下可以按 1/N 采样，统计精度下降很小但 Redis 压力大幅降低
     */
    public DetectResult addWithSample(String type, String item, int sampleRate) {
        if (sampleRate > 1 && ThreadLocalRandom.current().nextInt(sampleRate) != 0) {
            return DetectResult.EMPTY;
        }
        return add(type, item);
    }

    /**
     * 获取热点榜单（按频次降序）
     */
    public List<String> topN(String type, int n) {
        Set<ZSetOperations.TypedTuple<String>> tuples = topNWithScore(type, n);
        List<String> ids = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            if (t.getValue() != null) {
                ids.add(t.getValue());
            }
        }
        return ids;
    }

    public Set<ZSetOperations.TypedTuple<String>> topNWithScore(String type, int n) {
        try {
            Set<ZSetOperations.TypedTuple<String>> set =
                    redis.opsForZSet().reverseRangeWithScores(RedisKeys.hkTop(type), 0, n - 1L);
            return set == null ? Collections.emptySet() : set;
        } catch (Exception e) {
            log.warn("[HeavyKeeper] 获取榜单失败, type={}", type, e);
            return Collections.emptySet();
        }
    }

    /**
     * 查询某个对象的估计频次
     */
    public long estimate(String type, String item) {
        Double score = redis.opsForZSet().score(RedisKeys.hkTop(type), item);
        return score == null ? 0L : score.longValue();
    }

    /**
     * 清空（运维用）
     */
    public void reset(String type) {
        redis.delete(RedisKeys.hkTable(type));
        redis.delete(RedisKeys.hkTop(type));
    }

    /**
     * 探测结果
     */
    @Data
    public static class DetectResult {

        public static final DetectResult EMPTY = new DetectResult(0, -1, false);

        /** 估计访问频次 */
        private final long count;
        /** 榜单排名，0 起；-1 表示未进榜 */
        private final long rank;
        /** 是否属于 TopN 热点 */
        private final boolean hot;

        public DetectResult(long count, long rank, boolean hot) {
            this.count = count;
            this.rank = rank;
            this.hot = hot;
        }
    }
}
