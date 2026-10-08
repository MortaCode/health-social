package com.health.social.recommend;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.health.social.entity.Article;
import com.health.social.entity.RecExposure;
import com.health.social.feed.FollowService;
import com.health.social.like.LikeService;
import com.health.social.recommend.feedback.RecommendPersistenceBuffer;
import com.health.social.recommend.filter.ExposureFilter;
import com.health.social.recommend.model.Candidate;
import com.health.social.recommend.model.RecallContext;
import com.health.social.recommend.model.RecommendPage;
import com.health.social.recommend.pipeline.CandidatePoolMaintainer;
import com.health.social.recommend.pipeline.RecommendPipeline;
import com.health.social.recommend.profile.InterestProfileService;
import com.health.social.recommend.profile.ItemSimilarityService;
import com.health.social.recommend.recall.RecallChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 推荐服务（对外统一入口）。
 *
 * <h3>职责边界</h3>
 * <p>本类只做"编排 + IO 组装"，不包含任何算法：
 * <ul>
 *   <li>加载用户侧特征（关注、兴趣、最近点赞）→ 交给 {@link RecommendPipeline}；</li>
 *   <li>把候选转成 VO、生成推荐理由、批量补点赞状态；</li>
 *   <li>曝光上报（在线去重 + 异步流水）、活跃用户榜维护。</li>
 * </ul>
 * 算法（召回/排序/打散）都在各自的类里，便于单独测试和调参。
 *
 * <h3>关于分页（重要设计说明）</h3>
 * <p>推荐流的"下一页"和列表流不一样：候选集每次都是重新算的，
 * 用 offset 跳过前 N 条毫无意义（第 2 次请求的 Top20 已经和第 1 次不同）。
 * 本模块的做法是<b>用曝光集合当分页状态</b>：
 * <ul>
 *   <li>每次返回的条目立即写入 {@code rec:exposed:{userId}}；</li>
 *   <li>下次请求时这些条目被过滤掉，于是自然返回"没看过的新内容"；</li>
 *   <li>{@code cursor} 只用于客户端记录已消费数量，服务端不做 offset 跳过。</li>
 * </ul>
 * 这样即使用户反复下拉刷新，也不会看到重复内容 —— 这是推荐流最核心的体验保障。
 */
@Slf4j
@Service
public class RecommendService {

    /** 活跃用户榜 TTL：7 天 */
    private static final long ACTIVE_USER_TTL_SECONDS = 7L * 24 * 3600;

    private final RecommendPipeline pipeline;
    private final FollowService followService;
    private final InterestProfileService interestProfileService;
    private final ItemSimilarityService similarityService;
    private final ExposureFilter exposureFilter;
    private final RecommendPersistenceBuffer buffer;
    private final CandidatePoolMaintainer maintainer;
    private final LikeService likeService;
    private final StringRedisTemplate redis;
    private final RecommendProperties props;
    private final List<RecallChannel> channels;

    public RecommendService(RecommendPipeline pipeline,
                            FollowService followService,
                            InterestProfileService interestProfileService,
                            ItemSimilarityService similarityService,
                            ExposureFilter exposureFilter,
                            RecommendPersistenceBuffer buffer,
                            CandidatePoolMaintainer maintainer,
                            LikeService likeService,
                            StringRedisTemplate redis,
                            RecommendProperties props,
                            List<RecallChannel> channels) {
        this.pipeline = pipeline;
        this.followService = followService;
        this.interestProfileService = interestProfileService;
        this.similarityService = similarityService;
        this.exposureFilter = exposureFilter;
        this.buffer = buffer;
        this.maintainer = maintainer;
        this.likeService = likeService;
        this.redis = redis;
        this.props = props;
        this.channels = channels;
    }

    /* ================================================================= */
    /*                            主流程                                  */
    /* ================================================================= */

    /**
     * 生成一页推荐。
     *
     * @param userId 当前用户
     * @param cursor 客户端游标（服务端不做 offset 跳过，仅用于回显）
     * @param size   页大小
     * @param scene  场景标识（home / detail ...），用于曝光归因
     */
    public RecommendPage recommend(long userId, String cursor, Integer size, String scene) {
        int pageSize = resolvePageSize(size);
        String safeScene = (scene == null || scene.isBlank()) ? "home" : scene.trim();
        String requestId = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();

        /* ---------- ① 加载用户侧特征（一次，多路召回共享） ---------- */
        Map<Long, Double> interests = interestProfileService.interests(userId);
        List<Long> followees = safeFollowees(userId);
        List<Long> recentLiked = recentLiked(userId);
        boolean coldStart = interests.isEmpty() && recentLiked.isEmpty();

        RecallContext ctx = RecallContext.builder()
                .userId(userId)
                .nowMs(now)
                .size(pageSize)
                .followees(followees)
                .interests(interests)
                .recentLiked(recentLiked)
                .coldStart(coldStart)
                .build();

        /* ---------- ② 执行链路 ---------- */
        RecommendPipeline.PipelineResult result = pipeline.recommend(ctx);
        List<Candidate> picked = result.items();
        Map<Long, Article> articles = result.articles();

        /* ---------- ③ 组装 VO + 曝光上报（同一个循环，保证 position 对齐） ---------- */
        List<Long> exposedIds = new ArrayList<>(picked.size());
        List<RecommendPage.RecommendItem> items = new ArrayList<>(picked.size());
        LocalDateTime exposedAt = LocalDateTime.now();

        for (int i = 0; i < picked.size(); i++) {
            Candidate c = picked.get(i);
            Article article = articles.get(c.getArticleId());
            if (article == null) {
                continue;
            }
            // 展示用的点赞数必须以 Redis 权威计数为准，保证与 /feed、/article 口径一致
            // （排序用的是 DB 快照，见 RankFeatures 注释）
            article.setLikeCount(likeService.count(c.getArticleId()));

            RecommendPage.RecommendItem item = new RecommendPage.RecommendItem();
            item.setArticle(article);
            item.setScore(round(c.getFinalScore()));
            item.setChannels(new ArrayList<>(c.getChannelScores().keySet()));
            item.setReason(reason(c));
            item.setPosition(i);
            items.add(item);

            exposedIds.add(c.getArticleId());
            buffer.offerExposure(toExposure(userId, c, safeScene, requestId, i, exposedAt));
        }

        // 批量补"是否已点赞"（只对最终返回的这几十条，不铺开到全部候选）
        if (!exposedIds.isEmpty()) {
            Map<Long, Boolean> likedMap = likeService.batchHasLiked(userId, exposedIds);
            for (RecommendPage.RecommendItem item : items) {
                item.setLiked(Boolean.TRUE.equals(likedMap.get(item.getArticle().getId())));
            }
            // 在线曝光去重（写 Redis）
            exposureFilter.markExposed(userId, exposedIds);
        }

        /* ---------- ④ 活跃用户榜（供兴趣画像定时回写） ---------- */
        markActive(userId, now);

        boolean hasMore = items.size() >= pageSize;
        long delivered = parseCursor(cursor) + items.size();
        String nextCursor = hasMore ? String.valueOf(delivered) : null;

        log.debug("[Recommend] user={}, scene={}, coldStart={}, 产出={}, 耗时={}ms, 通道={}",
                userId, safeScene, coldStart, items.size(), result.costMs(), result.channelHits());

        return new RecommendPage(items, nextCursor, hasMore, requestId);
    }

    /* ================================================================= */
    /*                          运维 / 调试接口                            */
    /* ================================================================= */

    /**
     * 解释单篇文章的推荐特征（{@code GET /recommend/debug/{articleId}}）。
     *
     * <p>推荐系统最常被问到的问题是"为什么给我推这个"。
     * 这个接口把内容侧标签、相似文章、实时计数、当前权重档全部摊开，
     * 让排序结果可被人工复核。
     */
    public Map<String, Object> debug(long articleId, int topN) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("articleId", articleId);
        m.put("tags", interestProfileService.articleTags(articleId));
        m.put("similarArticles", similarityService.similar(articleId, Math.max(1, topN)));
        m.put("impressions", counter(RecRedisKeys.statImp(articleId)));
        m.put("clicks", counter(RecRedisKeys.statClk(articleId)));
        m.put("dislikes", counter(RecRedisKeys.statDis(articleId)));
        m.put("weights", props.getWeights());
        m.put("coldStartWeights", props.getColdStartWeights());
        return m;
    }

    /**
     * 推荐模块运行状态（{@code GET /recommend/stats}）。
     */
    public Map<String, Object> stats(long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> channelNames = new ArrayList<>();
        for (RecallChannel c : channels) {
            channelNames.add(c.name());
        }
        m.put("recallChannels", channelNames);
        m.put("poolSize", maintainer.poolSize());
        m.put("poolWatermark", maintainer.watermark());
        m.put("userExposedCount", exposureFilter.exposedCount(userId));
        m.put("userInterestTags", interestProfileService.interests(userId).size());
        m.put("persistBufferPending", buffer.pending());
        m.put("weightSum", round(props.getWeights().sum()));
        m.put("coldStartWeightSum", round(props.getColdStartWeights().sum()));
        return m;
    }

    /* ================================================================= */
    /*                            内部工具                                */
    /* ================================================================= */

    /**
     * 生成可解释的推荐理由。
     *
     * <p>策略：取置信度最高的通道作为主因；但如果存在个性化通道（兴趣/相似/关注）
     * 且其分数不低于最高分的 80%，则优先展示个性化理由 ——
     * 用户更愿意看到"因为你的关注"，而不是"因为它很热门"。
     */
    private String reason(Candidate c) {
        Map<String, Double> cs = c.getChannelScores();
        if (cs.isEmpty()) {
            return "为你推荐";
        }
        String best = null;
        double bestScore = -1D;
        for (Map.Entry<String, Double> e : cs.entrySet()) {
            if (e.getValue() > bestScore) {
                bestScore = e.getValue();
                best = e.getKey();
            }
        }
        String personalized = null;
        double personalizedScore = -1D;
        for (String key : List.of("interest", "sim", "follow")) {
            Double v = cs.get(key);
            if (v != null && v > personalizedScore) {
                personalizedScore = v;
                personalized = key;
            }
        }
        String chosen = (personalized != null && personalizedScore >= 0.8D * bestScore) ? personalized : best;
        return switch (chosen == null ? "" : chosen) {
            case "interest" -> "匹配你的健康关注";
            case "sim" -> "和你点赞过的内容相似";
            case "follow" -> "你关注的人发布";
            case "hot" -> "全站热门";
            case "fresh" -> "最新发布";
            default -> "为你推荐";
        };
    }

    private RecExposure toExposure(long userId, Candidate c, String scene, String requestId,
                                   int position, LocalDateTime exposedAt) {
        RecExposure e = new RecExposure();
        e.setId(IdWorker.getId());
        e.setUserId(userId);
        e.setArticleId(c.getArticleId());
        e.setScene(scene);
        e.setPosition(position);
        e.setScore(BigDecimal.valueOf(c.getFinalScore()).setScale(4, RoundingMode.HALF_UP));
        e.setChannels(truncate(c.channels(), 128));
        e.setRequestId(requestId);
        e.setExposedAt(exposedAt);
        return e;
    }

    /**
     * 读取关注列表，失败时降级为空。
     *
     * <p>为什么这里必须 try/catch：关注关系存在 MySQL 里，而"热点召回 + 新鲜度召回"
     * 这两路完全不依赖 DB。如果因为一次 DB 抖动就让整个推荐接口 500，
     * 等于把"能用的两路召回"也一起废掉了。
     * 降级为空的后果只是这一页少了关注召回的内容 —— 用户几乎无感，
     * 但接口可用性从"DB 挂 = 推荐挂"变成"DB 挂 = 推荐内容略少"。
     */
    private List<Long> safeFollowees(long userId) {
        try {
            return safeList(followService.myFollowees(userId));
        } catch (Exception e) {
            log.warn("[Recommend] 读取关注列表失败，关注召回本页降级为空, userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    /** 最近点赞（相似召回的种子），按时间倒序 */
    private List<Long> recentLiked(long userId) {
        try {
            int seedLimit = Math.max(1, props.getSimCoLikeSeedLimit());
            Set<String> raw = redis.opsForZSet()
                    .reverseRange(RecRedisKeys.likeRecent(userId), 0, seedLimit - 1L);
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
        } catch (Exception e) {
            log.warn("[Recommend] 读取最近点赞失败, userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    /** 维护活跃用户榜（兴趣画像定时回写的输入） */
    private void markActive(long userId, long now) {
        try {
            String key = RecRedisKeys.ACTIVE_USERS;
            redis.opsForZSet().add(key, String.valueOf(userId), now);
            int window = Math.max(1, props.getActiveUserWindowSize());
            Long size = redis.opsForZSet().size(key);
            if (size != null && size > window) {
                redis.opsForZSet().removeRange(key, 0, size - window - 1);
            }
            redis.expire(key, Duration.ofSeconds(ACTIVE_USER_TTL_SECONDS));
        } catch (Exception e) {
            log.debug("[Recommend] 活跃用户榜更新失败, userId={}", userId, e);
        }
    }

    private long counter(String key) {
        try {
            String v = redis.opsForValue().get(key);
            return v == null ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            return 0L;
        }
    }

    private int resolvePageSize(Integer size) {
        int def = Math.max(1, props.getDefaultPageSize());
        int max = Math.max(def, props.getMaxPageSize());
        if (size == null || size <= 0) {
            return def;
        }
        return Math.min(size, max);
    }

    private static long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(cursor.trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static List<Long> safeList(List<Long> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static double round(double v) {
        return Math.round(v * 10000D) / 10000D;
    }
}
