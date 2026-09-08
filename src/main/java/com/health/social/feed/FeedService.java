package com.health.social.feed;

import com.health.social.common.RedisKeys;
import com.health.social.entity.Article;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Feed 生成器：推拉结合（Hybrid）。
 *
 * <h3>策略</h3>
 * <table border="1">
 *   <tr><th>作者类型</th><th>写路径</th><th>读路径</th><th>理由</th></tr>
 *   <tr>
 *     <td>普通用户（粉丝 &lt; 5w）</td>
 *     <td>写作者发件箱 + <b>扇出</b>到所有粉丝收件箱</td>
 *     <td>只读自己的收件箱</td>
 *     <td>粉丝少，扇出成本低；读时零聚合，延迟最低</td>
 *   </tr>
 *   <tr>
 *     <td>大 V / 明星医生（粉丝 ≥ 5w）</td>
 *     <td>只写自己的分片发件箱（{@link BigKeySplitter}）</td>
 *     <td>读时<b>拉取</b>关注的大 V 分片并归并</td>
 *     <td>扇出 350w 次写不可接受（写放大），改为读时按需拉取（读放大可控）</td>
 *   </tr>
 * </table>
 *
 * <p>这就是业界标准的 <b>push-pull hybrid</b>：写放大与读放大之间取平衡。
 */
@Slf4j
@Service
public class FeedService {

    private final StringRedisTemplate redis;
    private final BigKeySplitter splitter;
    private final FollowService followService;
    private final com.health.social.mapper.UserFollowMapper followMapper;
    private final Executor fanoutExecutor;

    @Value("${health.feed.inbox-max-size:1000}")
    private int inboxMaxSize;

    public FeedService(StringRedisTemplate redis,
                       BigKeySplitter splitter,
                       FollowService followService,
                       com.health.social.mapper.UserFollowMapper followMapper,
                       @Qualifier("feedFanoutExecutor") Executor fanoutExecutor) {
        this.redis = redis;
        this.splitter = splitter;
        this.followService = followService;
        this.followMapper = followMapper;
        this.fanoutExecutor = fanoutExecutor;
    }

    /**
     * 发帖：写发件箱 + 按需扇出
     */
    public void publish(Article article) {
        long authorId = article.getAuthorId();
        long articleId = article.getId();
        double score = article.getCreateTime() == null
                ? System.currentTimeMillis()
                : article.getCreateTime().atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli();

        boolean bigV = followService.isBigV(authorId);

        if (bigV) {
            // ---- 拉模式：只写分片发件箱，不做扇出 ----
            splitter.add(authorId, articleId, score);
            log.info("[Feed] 大 V 发帖，走拉模式: author={}, article={}", authorId, articleId);
            return;
        }

        // ---- 普通用户：写自己的发件箱（未分桶，量小）----
        redis.opsForZSet().add(RedisKeys.feedOutbox(authorId), String.valueOf(articleId), score);
        redis.expire(RedisKeys.feedOutbox(authorId), java.time.Duration.ofDays(90));

        // ---- 推模式：异步扇出到粉丝收件箱 ----
        fanoutExecutor.execute(() -> fanoutToFollowers(authorId, articleId, score));
    }

    /**
     * 扇出：分批拉取粉丝，批量 ZADD 收件箱
     */
    private void fanoutToFollowers(long authorId, long articleId, double score) {
        long offset = 0;
        int batch = 1000;
        int total = 0;
        while (true) {
            List<Long> followers = followMapper.selectFollowerIds(authorId, offset, batch);
            if (followers == null || followers.isEmpty()) {
                break;
            }
            for (Long followerId : followers) {
                String inboxKey = RedisKeys.feedInbox(followerId);
                redis.opsForZSet().add(inboxKey, String.valueOf(articleId), score);
                trimInbox(inboxKey);
            }
            total += followers.size();
            offset += batch;
            if (followers.size() < batch) {
                break;
            }
        }
        log.info("[Feed] 扇出完成: author={}, article={}, 粉丝数={}", authorId, articleId, total);
    }

    /**
     * 收件箱裁剪：每个用户只保留最新的 inboxMaxSize 条，防止无限膨胀
     */
    private void trimInbox(String inboxKey) {
        Long size = redis.opsForZSet().size(inboxKey);
        if (size != null && size > inboxMaxSize) {
            redis.opsForZSet().removeRange(inboxKey, 0, size - inboxMaxSize - 1);
        }
    }

    /**
     * 关注某人：把对方最近的动态回填进自己的收件箱（非大 V）
     */
    public void onFollow(long followerId, long followeeId) {
        followService.evict(followerId);
        if (followService.isBigV(followeeId)) {
            // 大 V 走拉模式，无需回填
            return;
        }
        Set<ZSetOperations.TypedTuple<String>> recent = redis.opsForZSet()
                .reverseRangeWithScores(RedisKeys.feedOutbox(followeeId), 0, 49);
        if (recent == null || recent.isEmpty()) {
            return;
        }
        String inboxKey = RedisKeys.feedInbox(followerId);
        for (ZSetOperations.TypedTuple<String> t : recent) {
            if (t.getValue() != null && t.getScore() != null) {
                redis.opsForZSet().add(inboxKey, t.getValue(), t.getScore());
            }
        }
        trimInbox(inboxKey);
    }

    /**
     * 取关：清掉收件箱里来自该作者的动态
     */
    public void onUnfollow(long followerId, long followeeId) {
        followService.evict(followerId);
        Set<ZSetOperations.TypedTuple<String>> items = redis.opsForZSet()
                .reverseRangeWithScores(RedisKeys.feedOutbox(followeeId), 0, 199);
        if (items == null) {
            return;
        }
        String inboxKey = RedisKeys.feedInbox(followerId);
        for (ZSetOperations.TypedTuple<String> t : items) {
            if (t.getValue() != null) {
                redis.opsForZSet().remove(inboxKey, t.getValue());
            }
        }
    }

    /**
     * 删帖
     */
    public void delete(long authorId, long articleId, double score) {
        if (followService.isBigV(authorId)) {
            splitter.remove(authorId, articleId, score);
        } else {
            redis.opsForZSet().remove(RedisKeys.feedOutbox(authorId), String.valueOf(articleId));
            // 收件箱的清理交给异步任务（全量扫描代价高），这里给出按粉丝清理的入口
            fanoutExecutor.execute(() -> removeFromFollowersInbox(authorId, articleId));
        }
    }

    private void removeFromFollowersInbox(long authorId, long articleId) {
        long offset = 0;
        int batch = 1000;
        while (true) {
            List<Long> followers = followMapper.selectFollowerIds(authorId, offset, batch);
            if (followers == null || followers.isEmpty()) {
                break;
            }
            for (Long f : followers) {
                redis.opsForZSet().remove(RedisKeys.feedInbox(f), String.valueOf(articleId));
            }
            offset += batch;
            if (followers.size() < batch) {
                break;
            }
        }
    }
}
