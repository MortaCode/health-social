package com.health.social.recommend.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.health.social.common.RedisKeys;
import com.health.social.entity.Article;
import com.health.social.entity.UserProfile;
import com.health.social.like.LikeService;
import com.health.social.mapper.ArticleMapper;
import com.health.social.mapper.UserProfileMapper;
import com.health.social.recommend.RecommendProperties;
import com.health.social.recommend.RecRedisKeys;
import com.health.social.recommend.diversify.Diversifier;
import com.health.social.recommend.filter.ExposureFilter;
import com.health.social.recommend.model.Candidate;
import com.health.social.recommend.model.RecallContext;
import com.health.social.recommend.profile.InterestProfileService;
import com.health.social.recommend.rank.RankFeatures;
import com.health.social.recommend.rank.Ranker;
import com.health.social.recommend.recall.RecallChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐主链路编排：<b>召回 → 富化 → 过滤 → 排序 → 打散</b>。
 *
 * <pre>
 *   ① 多路召回      N 个 RecallChannel 并行产出候选，Map 天然去重
 *        │          每路独立 try/catch，挂一路只损失覆盖率
 *        ▼
 *   ② 粗排截断      按召回置信度取 Top candidateLimit（默认 800）
 *        │          否则 5 路 × 200 条全部走富化与打分，成本线性膨胀
 *        ▼
 *   ③ 富化          批量补 authorId / 发布时间 / 标签 / 作者等级 / 实时统计
 *        │          全部批量，禁止 N+1
 *        ▼
 *   ④ 过滤          已曝光、自己发的、已点赞、（可选）已删除
 *        ▼
 *   ⑤ 排序          7 特征加权 + 负反馈乘性惩罚（Ranker）
 *        ▼
 *   ⑥ 打散          同作者/同标签约束，三阶段贪心保证填满（Diversifier）
 * </pre>
 *
 * <h3>本类不碰"取文章正文"和"记曝光"</h3>
 * <p>编排只负责"算出该推哪些文章"，富化阶段顺手把 Article 带上避免二次查库；
 * 但把结果转成 VO、以及曝光上报留给 {@code RecommendService}。
 * 这样链路本身是纯计算 + 只读 IO，便于压测和单元测试。
 */
@Slf4j
@Component
public class RecommendPipeline {

    private final List<RecallChannel> channels;
    private final Diversifier diversifier;
    private final Ranker ranker;
    private final RecommendProperties props;
    private final InterestProfileService interestProfileService;
    private final ExposureFilter exposureFilter;
    private final ArticleMapper articleMapper;
    private final UserProfileMapper profileMapper;
    private final LikeService likeService;
    private final StringRedisTemplate redis;

    public RecommendPipeline(List<RecallChannel> channels,
                             Diversifier diversifier,
                             Ranker ranker,
                             RecommendProperties props,
                             InterestProfileService interestProfileService,
                             ExposureFilter exposureFilter,
                             ArticleMapper articleMapper,
                             UserProfileMapper profileMapper,
                             LikeService likeService,
                             StringRedisTemplate redis) {
        this.channels = channels;
        this.diversifier = diversifier;
        this.ranker = ranker;
        this.props = props;
        this.interestProfileService = interestProfileService;
        this.exposureFilter = exposureFilter;
        this.articleMapper = articleMapper;
        this.profileMapper = profileMapper;
        this.likeService = likeService;
        this.redis = redis;
    }

    /**
     * 链路产出。
     *
     * @param items       最终条目（已排序 + 已打散，长度 ≤ 请求 size）
     * @param articles    条目对应的文章本体（富化阶段顺带取回，避免二次查库）
     * @param channelHits 各召回通道的命中数（监控/调参用）
     * @param costMs      链路耗时
     */
    public record PipelineResult(List<Candidate> items,
                                 Map<Long, Article> articles,
                                 Map<String, Integer> channelHits,
                                 long costMs) {
    }

    /**
     * 执行一次推荐链路。
     */
    public PipelineResult recommend(RecallContext ctx) {
        long t0 = System.currentTimeMillis();

        /* ---------------- ① 多路召回 ---------------- */
        Map<Long, Candidate> merged = new LinkedHashMap<>();
        Map<String, Integer> channelHits = new LinkedHashMap<>();
        int perChannel = Math.max(1, props.getRecallLimitPerChannel());
        for (RecallChannel channel : channels) {
            Map<Long, Candidate> local = new HashMap<>();
            try {
                channel.recall(ctx, perChannel, local);
            } catch (Exception e) {
                // 通道内部理论上已经自愈，这里是第二道防线：宁可这一路空，不能整页挂
                log.warn("[Pipeline] 召回通道异常, channel={}", channel.name(), e);
            }
            channelHits.put(channel.name(), local.size());
            for (Map.Entry<Long, Candidate> e : local.entrySet()) {
                Candidate target = merged.computeIfAbsent(e.getKey(), Candidate::new);
                for (Map.Entry<String, Double> cs : e.getValue().getChannelScores().entrySet()) {
                    target.addChannel(cs.getKey(), cs.getValue());
                }
            }
        }
        if (merged.isEmpty()) {
            return new PipelineResult(Collections.emptyList(), Collections.emptyMap(),
                    channelHits, System.currentTimeMillis() - t0);
        }

        /* ---------------- ② 粗排截断 ---------------- */
        List<Candidate> candidates = new ArrayList<>(merged.values());
        int candidateLimit = Math.max(1, props.getCandidateLimit());
        if (candidates.size() > candidateLimit) {
            candidates.sort(Comparator.comparingDouble(Candidate::recallScore).reversed());
            candidates = new ArrayList<>(candidates.subList(0, candidateLimit));
        }

        /* ---------------- ③ 富化 ---------------- */
        Enriched enriched = enrich(candidates, ctx);
        Map<Long, Article> articleMap = enriched.articles();

        /* ---------------- ④ 过滤 ---------------- */
        candidates = filter(candidates, articleMap, ctx);

        if (candidates.isEmpty()) {
            return new PipelineResult(Collections.emptyList(), articleMap,
                    channelHits, System.currentTimeMillis() - t0);
        }

        /* ---------------- ⑤ 排序 ---------------- */
        rank(candidates, articleMap, enriched.authorLevels(), ctx);

        /* ---------------- ⑥ 打散 ---------------- */
        List<Candidate> diversified = diversifier.diversify(candidates, ctx.getSize());

        long cost = System.currentTimeMillis() - t0;
        log.debug("[Pipeline] user={}, 召回={}, 候选={}, 产出={}, 耗时={}ms, 通道={}",
                ctx.getUserId(), merged.size(), candidates.size(), diversified.size(), cost, channelHits);
        return new PipelineResult(diversified, articleMap, channelHits, cost);
    }

    /* ================================================================= */
    /*                            ③ 富化                                  */
    /* ================================================================= */

    /**
     * 富化产物：文章本体 + 作者等级。
     *
     * <p>作者等级是"作者属性"而不是"候选属性"，所以不塞进 {@link Candidate}，
     * 而是单独用一个小 record 在链路内传递 —— 保持 Candidate 的语义干净。
     */
    private record Enriched(Map<Long, Article> articles, Map<Long, Integer> authorLevels) {
    }

    /**
     * 批量补齐候选元数据。
     */
    private Enriched enrich(List<Candidate> candidates, RecallContext ctx) {
        List<Long> ids = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            ids.add(c.getArticleId());
        }

        // 文章本体：一次 IN 查询（推荐模块只读，复用既有 ArticleMapper）
        Map<Long, Article> articleMap = new HashMap<>(ids.size() * 2);
        for (List<Long> part : partition(ids, 500)) {
            List<Article> list = articleMapper.selectArticlesByIds(part);
            if (list == null) {
                continue;
            }
            for (Article a : list) {
                if (a == null || a.getId() == null) {
                    continue;
                }
                // status: 1 正常 0 删除。删除的内容不能进推荐池
                if (a.getStatus() != null && a.getStatus() != 1) {
                    continue;
                }
                articleMap.put(a.getId(), a);
            }
        }
        if (articleMap.isEmpty()) {
            return new Enriched(articleMap, Collections.emptyMap());
        }

        // 标签：一次 IN 查询。标签只参与"兴趣匹配打分"，拿不到不影响内容展示，
        // 因此降级为空而不是让整页失败 —— 区别对待"内容依赖"与"打分依赖"。
        Map<Long, List<Long>> tagsByArticle;
        try {
            tagsByArticle = interestProfileService.articleTagsBatch(articleMap.keySet());
        } catch (Exception e) {
            log.warn("[Pipeline] 批量读取文章标签失败，兴趣匹配本页按 0 处理", e);
            tagsByArticle = Collections.emptyMap();
        }

        // 作者等级：一次 IN 查询，同样是打分依赖，失败降级为普通用户
        Set<Long> authorIds = new HashSet<>();
        for (Article a : articleMap.values()) {
            if (a.getAuthorId() != null) {
                authorIds.add(a.getAuthorId());
            }
        }
        Map<Long, Integer> authorLevels;
        try {
            authorLevels = loadAuthorLevels(authorIds);
        } catch (Exception e) {
            log.warn("[Pipeline] 批量读取作者等级失败，按普通用户处理", e);
            authorLevels = Collections.emptyMap();
        }

        // 回填到候选
        List<Candidate> alive = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            Article a = articleMap.get(c.getArticleId());
            if (a == null) {
                continue;
            }
            c.setAuthorId(a.getAuthorId() == null ? 0L : a.getAuthorId());
            c.setPublishTimeMs(toEpochMs(a, ctx.getNowMs()));
            c.setTagIds(tagsByArticle.getOrDefault(c.getArticleId(), Collections.emptyList()));
            alive.add(c);
        }
        candidates.clear();
        candidates.addAll(alive);

        return new Enriched(articleMap, authorLevels);
    }

    private Map<Long, Integer> loadAuthorLevels(Set<Long> authorIds) {
        Map<Long, Integer> levels = new HashMap<>(authorIds.size() * 2);
        if (authorIds.isEmpty()) {
            return levels;
        }
        List<Long> ids = new ArrayList<>(authorIds);
        for (List<Long> part : partition(ids, 500)) {
            List<UserProfile> profiles = profileMapper.selectList(
                    new LambdaQueryWrapper<UserProfile>().in(UserProfile::getId, part));
            if (profiles == null) {
                continue;
            }
            for (UserProfile p : profiles) {
                if (p.getId() != null) {
                    levels.put(p.getId(), p.getLevel() == null ? 0 : p.getLevel());
                }
            }
        }
        return levels;
    }

    private static long toEpochMs(Article a, long fallback) {
        if (a.getCreateTime() == null) {
            return fallback;
        }
        return a.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /* ================================================================= */
    /*                            ④ 过滤                                  */
    /* ================================================================= */

    private List<Candidate> filter(List<Candidate> candidates, Map<Long, Article> articleMap, RecallContext ctx) {
        Set<Long> exposed = exposureFilter.recentExposed(ctx.getUserId());
        Set<Long> liked = props.isFilterLiked()
                ? likedAmong(ctx.getUserId(), candidates)
                : Collections.emptySet();

        List<Candidate> out = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            // 自己发的内容不进自己的推荐流（"我推荐我自己"是典型体验事故）
            if (c.getAuthorId() != null && c.getAuthorId() == ctx.getUserId()) {
                continue;
            }
            if (exposed.contains(c.getArticleId())) {
                continue;
            }
            if (liked.contains(c.getArticleId())) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    /**
     * 批量判断候选是否已点赞。
     *
     * <p>用 {@code executePipelined} 把 N 次 {@code SISMEMBER} 压成 1 次网络往返。
     * 逐个调用在 800 条候选时会产生 800 次 RTT，直接把接口 P99 拉爆。
     * 失败时返回空集合 —— 退化为"不过滤已点赞"，而不是让推荐失败。
     */
    private Set<Long> likedAmong(long userId, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return Collections.emptySet();
        }
        List<Long> ordered = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            ordered.add(c.getArticleId());
        }
        byte[] key = RedisKeys.userLikeSet(userId).getBytes(StandardCharsets.UTF_8);
        List<Object> raw;
        try {
            raw = redis.executePipelined((RedisCallback<Object>) connection -> {
                for (Long id : ordered) {
                    connection.setCommands().sIsMember(key, String.valueOf(id).getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("[Pipeline] 批量点赞判断失败，跳过已点赞过滤, userId={}", userId, e);
            return Collections.emptySet();
        }
        Set<Long> liked = new HashSet<>();
        for (int i = 0; i < ordered.size() && i < raw.size(); i++) {
            if (Boolean.TRUE.equals(raw.get(i))) {
                liked.add(ordered.get(i));
            }
        }
        return liked;
    }

    /* ================================================================= */
    /*                            ⑤ 排序                                  */
    /* ================================================================= */

    private void rank(List<Candidate> candidates,
                      Map<Long, Article> articleMap,
                      Map<Long, Integer> authorLevels,
                      RecallContext ctx) {
        Map<Long, Double> interests = ctx.getInterests();
        Set<Long> followeeSet = new HashSet<>(ctx.getFollowees());

        // 实时统计：3 次 MGET 拿全部候选的曝光/点击/不感兴趣计数
        List<Long> ids = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            ids.add(c.getArticleId());
        }
        Map<Long, Long> impressions = multiGetLong(ids, RecRedisKeys::statImp);
        Map<Long, Long> clicks = multiGetLong(ids, RecRedisKeys::statClk);
        Map<Long, Long> dislikes = multiGetLong(ids, RecRedisKeys::statDis);

        for (Candidate c : candidates) {
            Article a = articleMap.get(c.getArticleId());
            RankFeatures f = new RankFeatures();
            f.setArticleId(c.getArticleId());
            f.setAuthorId(c.getAuthorId() == null ? 0L : c.getAuthorId());
            f.setPublishTimeMs(c.getPublishTimeMs());
            f.setImpressions(impressions.getOrDefault(c.getArticleId(), 0L));
            f.setClicks(clicks.getOrDefault(c.getArticleId(), 0L));
            f.setDislikes(dislikes.getOrDefault(c.getArticleId(), 0L));
            f.setLikeCount(a == null || a.getLikeCount() == null ? 0L : a.getLikeCount());
            f.setCommentCount(a == null || a.getCommentCount() == null ? 0L : a.getCommentCount());
            f.setAuthorLevel(authorLevels.getOrDefault(f.getAuthorId(), 0));
            f.setInterestMatch(interestProfileService.interestMatch(c.getTagIds(), interests));
            f.setFollowed(followeeSet.contains(f.getAuthorId()));
            f.setChannelScore(c.channelScore());
            c.setFinalScore(ranker.score(f, ctx.getNowMs(), ctx.isColdStart()));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::getFinalScore).reversed());
    }

    /**
     * 批量读计数：{@code MGET} 一次拿回 N 个 key。
     *
     * <p>用 MGET 而不是 pipelined GET，是因为 MGET 是 Redis 原生多键命令，
     * 语义更清晰，且在 Cluster 下同一 slot 才能用（这里 key 前缀一致但不保证同 slot，
     * 单机/主从下无影响；Cluster 下会自动按 slot 拆分，仍比 N 次往返快）。
     */
    private Map<Long, Long> multiGetLong(List<Long> ids, java.util.function.LongFunction<String> keyFn) {
        Map<Long, Long> result = new HashMap<>(ids.size() * 2);
        if (ids.isEmpty()) {
            return result;
        }
        List<String> keys = new ArrayList<>(ids.size());
        for (Long id : ids) {
            keys.add(keyFn.apply(id));
        }
        try {
            List<String> values = redis.opsForValue().multiGet(keys);
            if (values == null) {
                return result;
            }
            for (int i = 0; i < ids.size() && i < values.size(); i++) {
                String v = values.get(i);
                if (v == null) {
                    continue;
                }
                try {
                    result.put(ids.get(i), Long.parseLong(v));
                } catch (NumberFormatException ignore) {
                    // 脏数据跳过
                }
            }
        } catch (Exception e) {
            log.warn("[Pipeline] 批量读取统计计数失败，按 0 处理", e);
        }
        return result;
    }

    /* ================================================================= */

    private static <T> List<List<T>> partition(List<T> list, int size) {
        if (list.size() <= size) {
            return List.of(list);
        }
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return parts;
    }
}
