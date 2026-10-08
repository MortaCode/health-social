package com.health.social.recommend.filter;

import com.health.social.recommend.RecommendProperties;
import com.health.social.recommend.RecRedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 曝光过滤器（推荐系统"必须有"的一环）。
 *
 * <h3>不做曝光去重会怎样</h3>
 * <p>推荐是"每次请求重新算一遍"，而算法是确定性的 —— 同样的用户、同样的候选池、
 * 同样的权重，算出来的 Top20 每次都一样。用户下拉刷新 10 次，会看到同一批内容 10 次。
 * 这是推荐系统最容易被用户感知的劣化，比"推得不准"更致命。
 *
 * <h3>为什么用 ZSet 而不是 Set</h3>
 * <p>如果只记录"看过"，那用户看过一次的内容就<b>永久</b>不会再被推荐 ——
 * 但好内容其实值得过一段时间再看（比如"高血压用药误区"过一个月重看仍有价值）。
 * 用 ZSet（score = 曝光时间）+ <b>按数量裁剪</b>，天然实现"滑动窗口"：
 * 只记住最近 {@code exposureWindowSize} 条，被挤出窗口的内容自然重新进入候选。
 *
 * <h3>与推流模块的关系</h3>
 * <p>推流模块（时间线）按时间倒序，天然不会重复；推荐模块是"重排"，必须自己维护曝光状态。
 * 这也是为什么这个类只存在于推荐模块。
 */
@Slf4j
@Component
public class ExposureFilter {

    private final StringRedisTemplate redis;
    private final RecommendProperties props;

    public ExposureFilter(StringRedisTemplate redis, RecommendProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * 读取该用户最近已曝光的内容（用于过滤）。
     *
     * <p>一次性把窗口内所有已曝光 ID 读成 HashSet，而不是对每个候选各查一次
     * {@code ZSCORE} —— 后者在 800 条候选时就是 800 次网络往返。
     */
    public Set<Long> recentExposed(long userId) {
        try {
            Set<String> raw = redis.opsForZSet().reverseRange(
                    RecRedisKeys.exposed(userId), 0, Math.max(1, props.getExposureWindowSize()) - 1L);
            if (raw == null || raw.isEmpty()) {
                return Collections.emptySet();
            }
            Set<Long> ids = new HashSet<>(raw.size() * 2);
            for (String s : raw) {
                try {
                    ids.add(Long.parseLong(s));
                } catch (NumberFormatException ignore) {
                    // 脏数据跳过
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("[Exposure] 读取曝光集合失败, userId={}", userId, e);
            // 读不到曝光集合时宁可不去重，也不能让整个推荐接口失败
            return Collections.emptySet();
        }
    }

    /**
     * 记录曝光：写入曝光集合（裁剪 + TTL）+ 累加曝光计数。
     *
     * <p>曝光计数是排序特征 {@code hot} 与 {@code quality} 的输入，
     * 所以"展示"这个动作本身就是在给内容积累特征 —— 这也是为什么曝光数据必须回传。
     */
    public void markExposed(long userId, List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            String key = RecRedisKeys.exposed(userId);
            Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> tuples =
                    new HashSet<>(articleIds.size() * 2);
            for (Long id : articleIds) {
                // TypedTuple.of 的 score 形参是装箱的 Double，long 不会自动转型，必须显式转 double
                tuples.add(org.springframework.data.redis.core.ZSetOperations.TypedTuple
                        .of(String.valueOf(id), (double) now));
            }
            redis.opsForZSet().add(key, tuples);

            // 按数量裁剪：只保留最近的 windowSize 条（滑动窗口，让老内容有机会"复活"）
            int window = Math.max(1, props.getExposureWindowSize());
            Long size = redis.opsForZSet().size(key);
            if (size != null && size > window) {
                redis.opsForZSet().removeRange(key, 0, size - window - 1);
            }
            redis.expire(key, Duration.ofDays(Math.max(1, props.getExposureTtlDays())));
        } catch (Exception e) {
            log.warn("[Exposure] 写入曝光集合失败, userId={}", userId, e);
        }
        incrementImpressions(articleIds);
    }

    /**
     * 累加曝光计数。
     *
     * <p>这里是 N 次 {@code INCR}。生产环境如果 QPS 很高，应改用
     * {@code executePipelined} 把 N 次往返压成 1 次，或干脆由客户端上报埋点、
     * 服务端离线聚合（真实平台通常走后者，因为曝光量比点击量大两个数量级）。
     */
    private void incrementImpressions(Collection<Long> articleIds) {
        for (Long id : articleIds) {
            try {
                redis.opsForValue().increment(RecRedisKeys.statImp(id));
            } catch (Exception e) {
                log.debug("[Exposure] 曝光计数失败, articleId={}", id, e);
            }
        }
    }

    /**
     * 把内容加入该用户的"近期不再推荐"集合（不累加曝光计数）。
     *
     * <p>与 {@link #markExposed} 的区别：曝光是"我展示了它"（要计入 impressions，
     * 参与热度/质量分计算），而这里表达的是"用户不想再看到它"（属于过滤状态，不是展示）。
     * 混用会虚高曝光数，进而压低该内容的 Wilson 质量分 —— 结果虽然"歪打正着"，
     * 但语义是错的，所以拆成两个方法。
     */
    public void markBlocked(long userId, long articleId) {
        if (articleId <= 0L) {
            return;
        }
        try {
            String key = RecRedisKeys.exposed(userId);
            redis.opsForZSet().add(key, String.valueOf(articleId), System.currentTimeMillis());
            redis.expire(key, Duration.ofDays(Math.max(1, props.getExposureTtlDays())));
        } catch (Exception e) {
            log.warn("[Exposure] 写入屏蔽状态失败, userId={}, articleId={}", userId, articleId, e);
        }
    }

    /**
     * 只读曝光状态（运维接口用，不产生副作用）。
     */
    public long exposedCount(long userId) {
        Long size = redis.opsForZSet().size(RecRedisKeys.exposed(userId));
        return size == null ? 0L : size;
    }
}
