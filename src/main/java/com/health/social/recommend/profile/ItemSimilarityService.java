package com.health.social.recommend.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.health.social.entity.ArticleTag;
import com.health.social.mapper.ArticleTagMapper;
import com.health.social.recommend.RecommendProperties;
import com.health.social.recommend.RecRedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文章相似度服务（相似召回的底座）。
 *
 * <h3>两条互补的相似度来源</h3>
 * <ol>
 *   <li><b>实时共现（行为侧）</b>：{@link #onLike} —— 用户点赞文章 A 时，
 *       把 A 与他最近点赞过的其它文章互相 +1。
 *       这是"喜欢 A 的人也喜欢 B"最直接的证据，实时、无需批处理；</li>
 *   <li><b>近线标签相似（内容侧）</b>：{@link #rebuildContentSimilarity} ——
 *       用标签 Jaccard 相似度批量重建，补足共现稀疏的长尾内容。
 *       新文章刚发布时还没有任何人点赞，共现矩阵里是空的，只能靠内容相似度先兜住。</li>
 * </ol>
 * 两者写入同一个 key（{@code rec:sim:{articleId}}），score 可能量纲不同
 * （共现是次数、Jaccard 是 0~1），所以召回通道里做了批内归一化。
 *
 * <h3>为什么不做全量 Item-CF</h3>
 * <p>真正的全量协同过滤需要扫描全量行为日志、在离线集群（Spark/Flink）里算共现矩阵，
 * 再灌回线上。本项目是单机演示，因此：
 * <ul>
 *   <li>共现只算"最近 {@code simCoLikeSeedLimit} 篇"的增量窗口，实时性好但覆盖率有限；</li>
 *   <li>标签 Jaccard 是它的廉价替代品，用内容特征近似行为相似度。</li>
 * </ul>
 * 生产落地的替换点很明确：把 {@link #rebuildContentSimilarity} 换成"读离线产出的相似度表"即可，
 * 召回通道、排序、打散都不需要改。
 */
@Slf4j
@Service
public class ItemSimilarityService {

    /** 相似表 TTL：7 天（长期不更新说明这篇文章已无行为，相似度不可信） */
    private static final long SIM_TTL_SECONDS = 7L * 24 * 3600;

    /** 最近点赞窗口 TTL：30 天 */
    private static final long LIKE_RECENT_TTL_SECONDS = 30L * 24 * 3600;

    private final StringRedisTemplate redis;
    private final ArticleTagMapper articleTagMapper;
    private final RecommendProperties props;

    public ItemSimilarityService(StringRedisTemplate redis,
                                 ArticleTagMapper articleTagMapper,
                                 RecommendProperties props) {
        this.redis = redis;
        this.articleTagMapper = articleTagMapper;
        this.props = props;
    }

    /* ================================================================= */
    /*                              读                                   */
    /* ================================================================= */

    /** 取某篇文章的相似文章 ID（按相似度降序） */
    public List<Long> similar(long articleId, int k) {
        if (k <= 0) {
            return Collections.emptyList();
        }
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                    .reverseRangeWithScores(RecRedisKeys.sim(articleId), 0, k - 1L);
            if (tuples == null || tuples.isEmpty()) {
                return Collections.emptyList();
            }
            List<Long> ids = new ArrayList<>(tuples.size());
            for (ZSetOperations.TypedTuple<String> t : tuples) {
                if (t.getValue() == null) {
                    continue;
                }
                try {
                    ids.add(Long.parseLong(t.getValue()));
                } catch (NumberFormatException ignore) {
                    // 脏数据跳过
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("[Similarity] 读取相似文章失败, articleId={}", articleId, e);
            return Collections.emptyList();
        }
    }

    /* ================================================================= */
    /*                     写：实时共现（点赞反馈触发）                     */
    /* ================================================================= */

    /**
     * 用户点赞后更新共现矩阵。
     *
     * <p>语义："一个同时喜欢 A 和 B 的用户"就是一条共现证据。
     * 用 {@code ZINCRBY} 累加而不是覆盖，让反复出现的组合自然浮到相似列表顶部。
     */
    public void onLike(long userId, long articleId) {
        try {
            String recentKey = RecRedisKeys.likeRecent(userId);
            int seedLimit = Math.max(1, props.getSimCoLikeSeedLimit());
            Set<String> recent = redis.opsForZSet().range(recentKey, 0, seedLimit - 1L);
            if (recent != null) {
                String self = String.valueOf(articleId);
                for (String other : recent) {
                    if (other == null || other.equals(self)) {
                        continue;
                    }
                    try {
                        long otherId = Long.parseLong(other);
                        incrSimilarity(articleId, otherId);
                        incrSimilarity(otherId, articleId);
                    } catch (NumberFormatException ignore) {
                        // 脏数据跳过
                    }
                }
            }
            // 记录到"最近点赞"窗口（相似召回的种子来源）
            redis.opsForZSet().add(recentKey, String.valueOf(articleId), System.currentTimeMillis());
            Long size = redis.opsForZSet().size(recentKey);
            if (size != null && size > seedLimit) {
                redis.opsForZSet().removeRange(recentKey, 0, size - seedLimit - 1);
            }
            redis.expire(recentKey, java.time.Duration.ofSeconds(LIKE_RECENT_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("[Similarity] 更新共现矩阵失败, userId={}, articleId={}", userId, articleId, e);
        }
    }

    private void incrSimilarity(long articleId, long otherId) {
        String key = RecRedisKeys.sim(articleId);
        redis.opsForZSet().incrementScore(key, String.valueOf(otherId), 1D);
        // 只保留 TopK：否则热门文章的相似列表会无限膨胀成大 key
        int topK = Math.max(1, props.getSimTopK());
        Long size = redis.opsForZSet().size(key);
        if (size != null && size > topK) {
            redis.opsForZSet().removeRange(key, 0, size - topK - 1);
        }
        redis.expire(key, java.time.Duration.ofSeconds(SIM_TTL_SECONDS));
    }

    /* ================================================================= */
    /*                   写：近线标签相似度批量重建                        */
    /* ================================================================= */

    /**
     * 用标签 Jaccard 相似度重建相似表。
     *
     * <pre>
     *   1. 取候选池里最近的 N 篇文章
     *   2. 批量查标签 → 建立"标签 → 文章"倒排（每个标签只保留最近的 cap 篇）
     *   3. 同一标签下的文章两两比较，累计共同标签数
     *   4. Jaccard(A,B) = 共同标签数 / (|A| + |B| − 共同标签数)
     *   5. 每篇文章取 TopK 写回 rec:sim:{articleId}
     * </pre>
     *
     * <p>复杂度控制：不做 O(n²) 全量两两比较，而是只在"共享至少一个标签"的文章之间比较，
     * 且每个标签桶截断到 {@code simRebuildGroupCap} 篇。
     *
     * @return 本次写入相似关系的文章数
     */
    public int rebuildContentSimilarity() {
        List<Long> articleIds = recentArticleIds();
        if (articleIds.size() < 2) {
            log.info("[Similarity] 候选池文章不足，跳过相似度重建, size={}", articleIds.size());
            return 0;
        }

        Map<Long, Set<Long>> articleTags = loadArticleTags(articleIds);
        if (articleTags.isEmpty()) {
            log.info("[Similarity] 文章均无标签，跳过相似度重建");
            return 0;
        }

        // 标签 → 文章倒排（每个标签桶截断）
        Map<Long, List<Long>> tagToArticles = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> e : articleTags.entrySet()) {
            for (Long tagId : e.getValue()) {
                List<Long> bucket = tagToArticles.computeIfAbsent(tagId, k -> new ArrayList<>());
                if (bucket.size() < Math.max(2, props.getSimRebuildGroupCap())) {
                    bucket.add(e.getKey());
                }
            }
        }

        // 共同标签计数：articleId → (otherArticleId → 共同标签数)
        Map<Long, Map<Long, Integer>> co = new HashMap<>();
        for (List<Long> bucket : tagToArticles.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    long a = bucket.get(i);
                    long b = bucket.get(j);
                    co.computeIfAbsent(a, k -> new HashMap<>()).merge(b, 1, Integer::sum);
                    co.computeIfAbsent(b, k -> new HashMap<>()).merge(a, 1, Integer::sum);
                }
            }
        }
        if (co.isEmpty()) {
            log.info("[Similarity] 没有共享标签的文章对，跳过相似度重建");
            return 0;
        }

        int topK = Math.max(1, props.getSimTopK());
        int written = 0;
        for (Map.Entry<Long, Map<Long, Integer>> e : co.entrySet()) {
            long articleId = e.getKey();
            Set<Long> own = articleTags.get(articleId);
            if (own == null || own.isEmpty()) {
                continue;
            }
            List<Map.Entry<Long, Integer>> partners = new ArrayList<>(e.getValue().entrySet());
            // 按 Jaccard 降序
            partners.sort(Comparator.comparingDouble(
                    (Map.Entry<Long, Integer> p) -> jaccard(own, articleTags.get(p.getKey()), p.getValue())).reversed());

            Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
            for (int i = 0; i < partners.size() && i < topK; i++) {
                Map.Entry<Long, Integer> p = partners.get(i);
                double jac = jaccard(own, articleTags.get(p.getKey()), p.getValue());
                if (jac <= 0D) {
                    continue;
                }
                tuples.add(ZSetOperations.TypedTuple.of(String.valueOf(p.getKey()), jac));
            }
            if (tuples.isEmpty()) {
                continue;
            }
            String key = RecRedisKeys.sim(articleId);
            try {
                redis.opsForZSet().add(key, tuples);
                redis.expire(key, java.time.Duration.ofSeconds(SIM_TTL_SECONDS));
                written++;
            } catch (Exception ex) {
                log.warn("[Similarity] 写入相似表失败, articleId={}", articleId, ex);
            }
        }
        log.info("[Similarity] 标签相似度重建完成：处理文章 {} 篇，写入 {} 篇，候选对 {} 组",
                articleTags.size(), written, co.size());
        return written;
    }

    /** Jaccard 相似度：|A ∩ B| / |A ∪ B| = co / (|A| + |B| − co) */
    private static double jaccard(Set<Long> a, Set<Long> b, int coCount) {
        if (a == null || b == null || coCount <= 0) {
            return 0D;
        }
        int union = a.size() + b.size() - coCount;
        return union <= 0 ? 0D : coCount / (double) union;
    }

    /** 候选池里最近的文章 ID */
    private List<Long> recentArticleIds() {
        Set<String> raw = redis.opsForZSet().reverseRange(
                RecRedisKeys.CAND_FRESH, 0, Math.max(1, props.getSimRebuildArticleLimit()) - 1L);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                ids.add(Long.parseLong(s));
            } catch (NumberFormatException ignore) {
                // 脏数据跳过
            }
        }
        return ids;
    }

    /** 批量查标签：articleId → tagId 集合 */
    private Map<Long, Set<Long>> loadArticleTags(List<Long> articleIds) {
        Map<Long, Set<Long>> result = new HashMap<>();
        // 分批查，避免 IN 列表过长
        int batch = 500;
        for (int i = 0; i < articleIds.size(); i += batch) {
            List<Long> sub = articleIds.subList(i, Math.min(i + batch, articleIds.size()));
            List<ArticleTag> list = articleTagMapper.selectList(
                    new LambdaQueryWrapper<ArticleTag>().in(ArticleTag::getArticleId, sub));
            if (list == null) {
                continue;
            }
            for (ArticleTag at : list) {
                if (at.getArticleId() == null || at.getTagId() == null) {
                    continue;
                }
                result.computeIfAbsent(at.getArticleId(), k -> new LinkedHashSet<>()).add(at.getTagId());
            }
        }
        // 无标签文章用空集合占位，后续会被跳过
        for (Long id : articleIds) {
            result.computeIfAbsent(id, k -> new HashSet<>());
        }
        return result;
    }
}
