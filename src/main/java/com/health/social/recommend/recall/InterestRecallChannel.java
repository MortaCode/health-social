package com.health.social.recommend.recall;

import com.health.social.recommend.RecommendProperties;
import com.health.social.recommend.RecRedisKeys;
import com.health.social.recommend.model.Candidate;
import com.health.social.recommend.model.RecallContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 兴趣召回（个性化最强的一路）。
 *
 * <h3>链路</h3>
 * <pre>
 *   rec:interest:{userId}  (Hash: tagId → 兴趣分)
 *            │  取兴趣分最高的 K 个标签
 *            ▼
 *   rec:tag:articles:{tagId}  (ZSet 倒排索引: articleId, score=发布时间)
 *            │  每个标签取最近 N 篇
 *            ▼
 *        候选（score = 归一化兴趣分 × 排名衰减）
 * </pre>
 *
 * <h3>为什么用 Redis 倒排而不是 SQL JOIN</h3>
 * <p>{@code t_article_tag JOIN t_article ORDER BY create_time} 在千万级内容下必然走磁盘排序，
 * 而推荐接口是 P99 要压到几十毫秒的高 QPS 接口。把"标签 → 文章"做成 Redis ZSet 倒排后，
 * 每次召回就是 K 次 {@code ZREVRANGE}，纯内存、O(log N + limit)。
 * 代价是索引需要在文章打标时增量维护 —— 由 {@code CandidatePoolMaintainer} 承担。
 *
 * <h3>冷启动</h3>
 * <p>没有兴趣画像时直接返回空（不是返回随机内容）。让一路召回"诚实地空掉"，
 * 比塞入噪声候选更好：排序阶段会由热点/新鲜度通道把页面填满。
 */
@Slf4j
@Component
public class InterestRecallChannel implements RecallChannel {

    private final StringRedisTemplate redis;
    private final RecommendProperties props;

    public InterestRecallChannel(StringRedisTemplate redis, RecommendProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public String name() {
        return "interest";
    }

    @Override
    public void recall(RecallContext ctx, int limit, Map<Long, Candidate> sink) {
        Map<Long, Double> interests = ctx.getInterests();
        if (interests == null || interests.isEmpty()) {
            return;
        }
        try {
            List<Long> topTags = topTags(interests, Math.min(interests.size(), props.getInterestRecallMaxTags()));
            int perTag = Math.max(1, Math.min(props.getInterestRecallPerTag(), limit));
            double cap = Math.max(1e-6D, props.getInterestCap());

            for (Long tagId : topTags) {
                double tagWeight = interests.getOrDefault(tagId, 0D) / cap;
                Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                        .reverseRangeWithScores(RecRedisKeys.tagArticles(tagId), 0, perTag - 1L);
                if (tuples == null || tuples.isEmpty()) {
                    continue;
                }
                int rank = 0;
                for (ZSetOperations.TypedTuple<String> t : tuples) {
                    if (t.getValue() == null) {
                        continue;
                    }
                    try {
                        long articleId = Long.parseLong(t.getValue());
                        // 兴趣越强、在标签内越新 → 分数越高
                        put(sink, articleId, tagWeight * decayByRank(rank++, perTag));
                    } catch (NumberFormatException ignore) {
                        // 脏数据跳过
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Recall][interest] 兴趣召回失败，降级为空", e);
        }
    }

    /** 取兴趣分最高的 K 个标签 */
    private static List<Long> topTags(Map<Long, Double> interests, int k) {
        List<Map.Entry<Long, Double>> entries = new ArrayList<>(interests.entrySet());
        entries.sort(Comparator.comparingDouble((Map.Entry<Long, Double> e) -> e.getValue()).reversed());
        List<Long> tags = new ArrayList<>(Math.min(k, entries.size()));
        for (int i = 0; i < entries.size() && i < k; i++) {
            tags.add(entries.get(i).getKey());
        }
        return tags;
    }

    private static double decayByRank(int rank, int total) {
        return 1D - 0.5D * (rank / (double) Math.max(1, total));
    }
}
