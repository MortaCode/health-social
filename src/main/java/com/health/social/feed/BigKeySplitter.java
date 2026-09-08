package com.health.social.feed;

import com.health.social.common.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 大 V Feed 的 BigKey 拆分器。
 *
 * <h3>问题</h3>
 * <p>明星医生有 350w 粉丝、累计发帖数万条。如果所有帖子都塞进一个
 * {@code feed:outbox:{authorId}} 的 ZSet：
 * <ul>
 *   <li>单 key 元素数百万级，内存数十 MB，Redis 读写该 key 的延迟从 O(1) 退化；</li>
 *   <li>AOF rewrite / RDB 落盘 / 主从同步都会因为这个大 key 出现明显卡顿；</li>
 *   <li>Cluster 模式下单 key 无法拆分，会形成<b>流量倾斜的热点 slot</b>。</li>
 * </ul>
 *
 * <h3>方案：按时间分桶（ZSet 分片）</h3>
 * <pre>
 *   feed:outbox:shard:{authorId}:{bucketIndex}   ← 每个桶 6 小时，只装这段时间内的帖子
 *   feed:outbox:meta:{authorId}                  ← 桶索引表，记录该作者有哪些非空桶
 * </pre>
 * <ul>
 *   <li>写入：score 决定落在哪个桶，单桶元素量级被压到可控范围；</li>
 *   <li>读取：一次时间线拉取只涉及最近 N 个桶（通常 1~4 个），范围查询天然裁剪；</li>
 *   <li>归档：历史桶可整体设置 TTL 或转存冷存储，热桶永远是小 key；</li>
 *   <li>Cluster：分片 key <b>不带 hash tag</b>，CRC16 自然把不同桶散到不同 slot，消除热点。</li>
 * </ul>
 */
@Slf4j
@Component
public class BigKeySplitter {

    private final StringRedisTemplate redis;

    /** 单个时间桶跨度（毫秒），默认 6 小时 */
    @Value("${health.feed.bucket-ms:21600000}")
    private long bucketMs;

    /** 分片保留时长（毫秒），超时自动回收，默认 90 天 */
    @Value("${health.feed.shard-ttl-ms:7776000000}")
    private long shardTtlMs;

    public BigKeySplitter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /* ------------------------------- 写 ------------------------------- */

    /**
     * 写入一条动态
     *
     * @param authorId  作者（大 V）
     * @param articleId 文章 ID
     * @param score     排序分（发帖时间戳）
     */
    public void add(long authorId, long articleId, double score) {
        String shardKey = RedisKeys.feedOutboxShard(authorId, bucketOf((long) score));
        redis.opsForZSet().add(shardKey, String.valueOf(articleId), score);
        redis.expire(shardKey, java.time.Duration.ofMillis(shardTtlMs));

        // 维护桶索引：member = bucketIndex, score = bucketIndex（可用 ZRANGEBYSCORE 做时间范围裁剪）
        String metaKey = RedisKeys.feedOutboxMeta(authorId);
        long bucket = bucketOf((long) score);
        redis.opsForZSet().add(metaKey, String.valueOf(bucket), bucket);
        redis.expire(metaKey, java.time.Duration.ofMillis(shardTtlMs));
    }

    public void remove(long authorId, long articleId, double score) {
        String shardKey = RedisKeys.feedOutboxShard(authorId, bucketOf((long) score));
        redis.opsForZSet().remove(shardKey, String.valueOf(articleId));
    }

    /* ------------------------------- 读 ------------------------------- */

    /**
     * 拉取某个作者在时间区间内的动态（按 score 降序，即最新在前）
     *
     * @param fromMs 起始时间（含）
     * @param toMs   结束时间（含）
     * @param limit  最多返回条数
     */
    public List<FeedItem> range(long authorId, long fromMs, long toMs, int limit) {
        List<Long> buckets = bucketsBetween(authorId, fromMs, toMs);
        if (buckets.isEmpty()) {
            return Collections.emptyList();
        }
        List<FeedItem> result = new ArrayList<>(limit);
        // 从最新的桶往回扫，凑够 limit 就停，避免无效读取
        for (int i = buckets.size() - 1; i >= 0 && result.size() < limit; i--) {
            String shardKey = RedisKeys.feedOutboxShard(authorId, buckets.get(i));
            Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                    .reverseRangeByScoreWithScores(shardKey, fromMs, toMs, 0, limit);
            if (tuples == null || tuples.isEmpty()) {
                continue;
            }
            for (ZSetOperations.TypedTuple<String> t : tuples) {
                if (t.getValue() == null) {
                    continue;
                }
                long articleId;
                try {
                    articleId = Long.parseLong(t.getValue());
                } catch (NumberFormatException e) {
                    continue;
                }
                double score = t.getScore() == null ? 0D : t.getScore();
                result.add(FeedItem.of(articleId, authorId, score, FeedItem.SRC_PULL_BIG_V));
            }
        }
        result.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return result;
    }

    /**
     * 查询作者在 [fromMs, toMs] 区间内存在的桶（从元数据索引裁剪，避免空读）
     */
    public List<Long> bucketsBetween(long authorId, long fromMs, long toMs) {
        long fromBucket = bucketOf(fromMs);
        long toBucket = bucketOf(toMs);
        Set<String> members = redis.opsForZSet()
                .rangeByScore(RedisKeys.feedOutboxMeta(authorId), fromBucket, toBucket);
        if (members == null || members.isEmpty()) {
            // 元数据缺失（如刚清理）：退化为枚举桶
            List<Long> all = new ArrayList<>();
            for (long b = toBucket; b >= fromBucket; b--) {
                all.add(b);
            }
            Collections.reverse(all);
            return all;
        }
        List<Long> buckets = new ArrayList<>(members.size());
        for (String m : members) {
            try {
                buckets.add(Long.parseLong(m));
            } catch (NumberFormatException ignore) {
                // skip
            }
        }
        Collections.sort(buckets);
        return buckets;
    }

    /* ------------------------------ 工具 ------------------------------ */

    public long bucketOf(long timestampMs) {
        return timestampMs / bucketMs;
    }

    public long bucketStartMs(long bucket) {
        return bucket * bucketMs;
    }

    /**
     * 运维视角：查看某个作者的分片规模
     */
    public List<ShardStat> stats(long authorId) {
        Set<ZSetOperations.TypedTuple<String>> members = redis.opsForZSet()
                .rangeWithScores(RedisKeys.feedOutboxMeta(authorId), 0, -1);
        if (members == null) {
            return Collections.emptyList();
        }
        List<ShardStat> stats = new ArrayList<>(members.size());
        for (ZSetOperations.TypedTuple<String> m : members) {
            if (m.getValue() == null || m.getScore() == null) {
                continue;
            }
            long bucket = (long) (double) m.getScore();
            String shardKey = RedisKeys.feedOutboxShard(authorId, bucket);
            Long size = redis.opsForZSet().size(shardKey);
            stats.add(new ShardStat(shardKey, size == null ? 0 : size,
                    java.time.Instant.ofEpochMilli(bucketStartMs(bucket)).toString()));
        }
        return stats;
    }

    public record ShardStat(String key, long size, String bucketStart) {
    }
}
