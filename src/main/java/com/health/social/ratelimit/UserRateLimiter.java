package com.health.social.ratelimit;

import com.health.social.common.RedisKeys;
import com.health.social.common.UserLevel;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分级令牌桶限流器（分布式）。
 *
 * <h3>为什么用令牌桶而不是计数器</h3>
 * <ul>
 *   <li><b>固定窗口计数器</b>：窗口切换瞬间会放过 2 倍流量（临界突刺）；</li>
 *   <li><b>滑动窗口</b>：需要存时间序列，内存与计算成本随精度上升；</li>
 *   <li><b>令牌桶</b>：以恒定速率补充令牌、桶容量控制突发，
 *       既限制平均速率又允许合理突发，且实现只需 {tokens, ts} 两个字段，
 *       一段 Lua 就能原子完成"补令牌 + 扣令牌"，非常适合 Redis 侧实现。</li>
 * </ul>
 *
 * <h3>差异化阈值</h3>
 * <pre>
 *   普通用户   rate=20/s   capacity=40
 *   认证医生   rate=100/s  capacity=200
 * </pre>
 * 认证医生是平台的内容生产者，发稿 / 回复咨询的调用频率天然更高，
 * 阈值差异化既保障体验，也把稀缺的 Redis 与 DB 资源留给真实业务。
 *
 * <p>容错：Redis 抖动时 <b>fail-open</b>（放行并记日志）。
 * 限流属于保护层，不能因为保护层自身故障导致全站不可用；
 * 同时预留本地令牌桶作为 Redis 不可用时的第二道防线（这里给出开关与埋点）。
 */
@Slf4j
@Service
public class UserRateLimiter {

    private static final int TTL_SECONDS = 600;

    private final StringRedisTemplate redis;
    private final RedisScript<List> tokenBucketScript;

    @Value("${health.ratelimit.normal-rate:20}")
    private double normalRate;

    @Value("${health.ratelimit.normal-capacity:40}")
    private double normalCapacity;

    @Value("${health.ratelimit.doctor-rate:100}")
    private double doctorRate;

    @Value("${health.ratelimit.doctor-capacity:200}")
    private double doctorCapacity;

    @Value("${health.ratelimit.fail-open:true}")
    private boolean failOpen;

    public UserRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/token_bucket.lua"));
        script.setResultType(List.class);
        this.tokenBucketScript = script;
    }

    /**
     * 尝试获取令牌
     *
     * @param userId     用户 ID
     * @param api        接口标识（不同接口独立计数）
     * @param level      用户等级
     * @param permits    本次消耗令牌数，通常 1；大页查询可按 size 折算
     */
    public RateResult tryAcquire(long userId, String api, UserLevel level, int permits) {
        double rate = (level == UserLevel.DOCTOR || level == UserLevel.STAR_DOCTOR) ? doctorRate : normalRate;
        double capacity = (level == UserLevel.DOCTOR || level == UserLevel.STAR_DOCTOR) ? doctorCapacity : normalCapacity;

        if (permits <= 0) {
            permits = 1;
        }
        try {
            List<Long> res = redis.execute(tokenBucketScript,
                    List.of(RedisKeys.rateLimitToken(userId, api)),
                    String.valueOf(rate),
                    String.valueOf(capacity),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(permits),
                    String.valueOf(TTL_SECONDS));

            if (res == null || res.size() < 3) {
                return failOpen ? RateResult.allow(Integer.MAX_VALUE) : RateResult.reject(1000);
            }
            boolean allowed = res.get(0) != null && res.get(0) == 1L;
            long remain = res.get(1) == null ? 0 : res.get(1);
            long retryAfterMs = res.get(2) == null ? 0 : res.get(2);
            return allowed ? RateResult.allow(remain) : RateResult.reject(retryAfterMs);
        } catch (Exception e) {
            log.warn("[RateLimit] Redis 异常, userId={}, api={}", userId, api, e);
            return failOpen ? RateResult.allow(Integer.MAX_VALUE) : RateResult.reject(1000);
        }
    }

    public RateResult tryAcquire(long userId, String api, UserLevel level) {
        return tryAcquire(userId, api, level, 1);
    }

    /**
     * 手工补充令牌（风控白名单 / 运营临时提额）
     */
    public void refill(long userId, String api, double capacity) {
        try {
            redis.opsForHash().put(RedisKeys.rateLimitToken(userId, api), "tokens", String.valueOf(capacity));
        } catch (Exception e) {
            log.warn("[RateLimit] 补令牌失败", e);
        }
    }

    @Data
    public static class RateResult {

        private final boolean allowed;
        private final long remaining;
        /** 被拒时建议的重试等待时间（毫秒） */
        private final long retryAfterMs;

        private RateResult(boolean allowed, long remaining, long retryAfterMs) {
            this.allowed = allowed;
            this.remaining = remaining;
            this.retryAfterMs = retryAfterMs;
        }

        public static RateResult allow(long remaining) {
            return new RateResult(true, remaining, 0);
        }

        public static RateResult reject(long retryAfterMs) {
            return new RateResult(false, 0, retryAfterMs);
        }
    }
}
