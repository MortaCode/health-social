package com.health.social.recommend.recall;

import com.health.social.recommend.RecommendProperties;
import com.health.social.recommend.RecRedisKeys;
import com.health.social.recommend.model.Candidate;
import com.health.social.recommend.model.RecallContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 相似召回（Item-CF：喜欢 A 的人也喜欢 B）。
 *
 * <h3>链路</h3>
 * <pre>
 *   rec:like:recent:{userId}   取我最近点赞的 N 篇作为"种子"
 *            │
 *            ▼
 *   rec:sim:{seedArticleId}    ZSet: 相似文章 → 相似度
 *            │  按相似度取 TopK
 *            ▼
 *         候选（score = 批内归一化相似度 × 排名衰减）
 * </pre>
 *
 * <h3>相似度是怎么来的（见 ItemSimilarityService）</h3>
 * <ol>
 *   <li><b>实时共现</b>：用户点赞文章 A 时，把它和他最近点赞过的其它文章互相 +1
 *       （{@code ZINCRBY rec:sim:{other} 1 A}）。这是"喜欢 A 的人"最直接的证据；</li>
 *   <li><b>近线标签相似</b>：定时任务用标签 Jaccard 相似度重建，补足共现稀疏的长尾内容。</li>
 * </ol>
 *
 * <h3>与推流模块的关系</h3>
 * <p>完全无关。推流是"关注关系"驱动（社交图谱），相似召回是"行为/内容"驱动（兴趣图谱），
 * 两者互补：关注召回解决"我在意的人发了什么"，相似召回解决"和我口味相近的人在看什么"。
 */
@Slf4j
@Component
public class CoLikeRecallChannel implements RecallChannel {

    private final StringRedisTemplate redis;
    private final RecommendProperties props;

    public CoLikeRecallChannel(StringRedisTemplate redis, RecommendProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public String name() {
        return "sim";
    }

    @Override
    public void recall(RecallContext ctx, int limit, Map<Long, Candidate> sink) {
        List<Long> seeds = ctx.getRecentLiked();
        if (seeds == null || seeds.isEmpty()) {
            return;
        }
        try {
            int maxSeeds = Math.min(seeds.size(), props.getSimRecallMaxSeeds());
            int perSeed = Math.max(1, Math.min(props.getSimRecallPerSeed(), limit));
            for (int s = 0; s < maxSeeds; s++) {
                long seedId = seeds.get(s);
                Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                        .reverseRangeWithScores(RecRedisKeys.sim(seedId), 0, perSeed - 1L);
                if (tuples == null || tuples.isEmpty()) {
                    continue;
                }
                // 相似度可能是"共现次数"（无上界）也可能是"Jaccard"（0~1），
                // 量纲不统一，因此在种子内部做一次归一化
                double max = 0D;
                for (ZSetOperations.TypedTuple<String> t : tuples) {
                    max = Math.max(max, t.getScore() == null ? 0D : t.getScore());
                }
                int rank = 0;
                for (ZSetOperations.TypedTuple<String> t : tuples) {
                    if (t.getValue() == null) {
                        continue;
                    }
                    try {
                        long articleId = Long.parseLong(t.getValue());
                        double sim = max <= 0D ? 0D : (t.getScore() == null ? 0D : t.getScore()) / max;
                        // 种子越新（s 越小）权重略高；越相似、排名越靠前分越高
                        double seedWeight = 1D - 0.3D * (s / (double) Math.max(1, maxSeeds));
                        put(sink, articleId, seedWeight * sim * decayByRank(rank++, perSeed));
                    } catch (NumberFormatException ignore) {
                        // 脏数据跳过
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Recall][sim] 相似召回失败，降级为空", e);
        }
    }

    private static double decayByRank(int rank, int total) {
        return 1D - 0.5D * (rank / (double) Math.max(1, total));
    }
}
