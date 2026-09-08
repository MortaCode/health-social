package com.health.social.like;

import com.health.social.common.RedisKeys;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 点赞服务（写路径全部在 Redis，DB 由 MQ 异步聚合落库）。
 *
 * <h3>为什么必须用 Lua</h3>
 * <p>"是否点过（SISMEMBER user:like:set）+ 计数增减（INCR article:like:count）"是典型的
 * check-then-act 组合。拆成两条命令在并发下会重复计数；用事务（MULTI/EXEC）则没有
 * if-else 能力。Lua 脚本在 Redis 中单线程原子执行，是唯一正确的做法。
 *
 * <h3>数据一致性</h3>
 * <ul>
 *   <li><b>读路径</b>：点赞数直接读 Redis 计数器，强一致、无延迟；</li>
 *   <li><b>写路径</b>：Redis 原子更新 → 发 MQ → 消费者聚合落库，最终一致（秒级延迟）；</li>
 *   <li><b>兜底</b>：Redis 计数为绝对权威，DB 若出现漂移由定时任务从 Redis 全量回写修正。</li>
 * </ul>
 */
@Slf4j
@Service
public class LikeService {

    /** 计数器兜底 TTL：7 天（每次点赞会续期，冷门 key 自动回收） */
    private static final long COUNT_TTL_SECONDS = 7 * 24 * 3600L;
    /** 用户点赞集合 TTL：0 表示不过期（它是幂等判重的唯一依据，不能过期） */
    private static final long SET_TTL_SECONDS = 0L;

    private final StringRedisTemplate redis;
    private final RedisScript<List> likeScript;
    private final LikeProducer producer;

    @Value("${health.like.enabled:true}")
    private boolean mqEnabled;

    public LikeService(StringRedisTemplate redis, LikeProducer producer) {
        this.redis = redis;
        this.producer = producer;
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/like.lua"));
        script.setResultType(List.class);
        this.likeScript = script;
    }

    /**
     * 点赞 / 取消点赞
     *
     * @param like true 点赞，false 取消
     */
    public LikeResult toggle(long userId, long articleId, boolean like) {
        List<Long> res = redis.execute(likeScript,
                List.of(RedisKeys.articleLikeCount(articleId),
                        RedisKeys.userLikeSet(userId),
                        RedisKeys.articleLikeUsers(articleId)),
                String.valueOf(articleId),
                like ? "1" : "0",
                String.valueOf(userId),
                String.valueOf(COUNT_TTL_SECONDS),
                String.valueOf(SET_TTL_SECONDS));

        if (res == null || res.size() < 3) {
            throw new IllegalStateException("点赞脚本返回异常: " + res);
        }

        int liked = res.get(0) == null ? 0 : res.get(0).intValue();
        long count = res.get(1) == null ? 0 : res.get(1);
        int changed = res.get(2) == null ? 0 : res.get(2).intValue();

        // 只有状态真正发生变化才投递 MQ：重复点赞/重复取消直接从这里削掉一大半流量
        if (changed == 1 && mqEnabled) {
            int delta = like ? 1 : -1;
            producer.send(LikeEvent.of(userId, articleId, liked, delta));
        }
        return new LikeResult(liked == 1, count, changed == 1);
    }

    /** 文章点赞总数（读 Redis，绝对权威） */
    public long count(long articleId) {
        String v = redis.opsForValue().get(RedisKeys.articleLikeCount(articleId));
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 用户是否已点赞 */
    public boolean hasLiked(long userId, long articleId) {
        Boolean member = redis.opsForSet().isMember(RedisKeys.userLikeSet(userId), String.valueOf(articleId));
        return Boolean.TRUE.equals(member);
    }

    /**
     * 批量判断（用于 Feed 流渲染时一次判断多篇文章是否已点赞）
     */
    public Map<Long, Boolean> batchHasLiked(long userId, Collection<Long> articleIds) {
        Map<Long, Boolean> result = new HashMap<>(articleIds.size() * 2);
        String key = RedisKeys.userLikeSet(userId);
        for (Long id : articleIds) {
            Boolean m = redis.opsForSet().isMember(key, String.valueOf(id));
            result.put(id, Boolean.TRUE.equals(m));
        }
        return result;
    }

    @Data
    public static class LikeResult {

        private final boolean liked;
        private final long count;
        /** 本次操作是否真的改变了状态 */
        private final boolean changed;

        public LikeResult(boolean liked, long count, boolean changed) {
            this.liked = liked;
            this.count = count;
            this.changed = changed;
        }
    }
}
