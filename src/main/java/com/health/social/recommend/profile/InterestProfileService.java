package com.health.social.recommend.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.health.social.entity.ArticleTag;
import com.health.social.entity.UserInterest;
import com.health.social.mapper.ArticleTagMapper;
import com.health.social.mapper.UserInterestMapper;
import com.health.social.recommend.RecommendProperties;
import com.health.social.recommend.RecRedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用户兴趣画像服务。
 *
 * <h3>存储选型：Redis Hash 为主，DB 为辅</h3>
 * <ul>
 *   <li><b>在线</b>：{@code rec:interest:{userId}} 是一个 Hash（tagId → 兴趣分）。
 *       推荐请求要读它（兴趣召回 + 兴趣匹配打分），反馈事件要写它，
 *       读写都是毫秒级、单 key 操作，天然适合 Redis；</li>
 *   <li><b>离线/兜底</b>：{@code t_user_interest} 由定时任务定期回写，
 *       用于 Redis 数据丢失后的恢复，以及离线人群分析。</li>
 * </ul>
 *
 * <h3>为什么兴趣分要"加减"而不是"覆盖"</h3>
 * <p>兴趣是<b>累积证据</b>，不是当前状态：用户点了 3 篇高血压科普，
 * 说明他对高血压的兴趣比点 1 篇的人更强。所以用 {@code HINCRBYFLOAT} 式的累加，
 * 而不是"最近一次行为覆盖"。同时必须有上限（cap）和衰减（负 delta），
 * 否则早期的一次误点会让某个标签永久霸占兴趣画像。
 *
 * <h3>并发安全</h3>
 * <p>"读-改-写 + 裁剪到 [0, cap] + 续期"整段逻辑放在 Lua 里原子执行
 * （见 {@code lua/rec_interest.lua}）。用普通 HINCRBYFLOAT 会失去上下界裁剪，
 * 用"先 HGET 再 HSET"则会在并发下丢失更新。
 */
@Slf4j
@Service
public class InterestProfileService {

    /** 兴趣 Hash 的 TTL：30 天不活跃即回收（长期不来的用户，兴趣已经不可信） */
    private static final long INTEREST_TTL_SECONDS = 30L * 24 * 3600;

    /** 空画像标记 TTL：5 分钟，防止新用户反复回源 DB */
    private static final long LOADED_MARKER_TTL_SECONDS = 300L;

    private final StringRedisTemplate redis;
    private final UserInterestMapper interestMapper;
    private final ArticleTagMapper articleTagMapper;
    private final RecommendProperties props;
    private final RedisScript<String> interestScript;

    public InterestProfileService(StringRedisTemplate redis,
                                  UserInterestMapper interestMapper,
                                  ArticleTagMapper articleTagMapper,
                                  RecommendProperties props) {
        this.redis = redis;
        this.interestMapper = interestMapper;
        this.articleTagMapper = articleTagMapper;
        this.props = props;
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/rec_interest.lua"));
        script.setResultType(String.class);
        this.interestScript = script;
    }

    /* ================================================================= */
    /*                              读                                   */
    /* ================================================================= */

    /**
     * 读取兴趣画像。
     *
     * <p>Redis miss 时回源 DB 并回填；DB 也为空则打一个短 TTL 标记，
     * 避免新用户（画像天然为空）每次请求都打一次 DB。
     */
    public Map<Long, Double> interests(long userId) {
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(RecRedisKeys.interest(userId));
            if (raw != null && !raw.isEmpty()) {
                return parse(raw);
            }
            // 空画像标记命中 → 直接返回空，不再回源
            if (Boolean.TRUE.equals(redis.hasKey(RecRedisKeys.interestLoaded(userId)))) {
                return Collections.emptyMap();
            }
            return loadFromDb(userId);
        } catch (Exception e) {
            log.warn("[Interest] 读取兴趣画像失败, userId={}", userId, e);
            return Collections.emptyMap();
        }
    }

    private Map<Long, Double> loadFromDb(long userId) {
        Map<Long, Double> result = new LinkedHashMap<>();
        List<UserInterest> list = interestMapper.selectList(
                new LambdaQueryWrapper<UserInterest>().eq(UserInterest::getUserId, userId));
        if (list == null || list.isEmpty()) {
            markLoaded(userId);
            return result;
        }
        Map<String, String> toCache = new HashMap<>(list.size() * 2);
        for (UserInterest ui : list) {
            if (ui.getTagId() == null || ui.getScore() == null) {
                continue;
            }
            double score = ui.getScore().doubleValue();
            if (score <= 0D) {
                continue;
            }
            result.put(ui.getTagId(), score);
            toCache.put(String.valueOf(ui.getTagId()), String.valueOf(score));
        }
        if (!toCache.isEmpty()) {
            redis.opsForHash().putAll(RecRedisKeys.interest(userId), toCache);
            redis.expire(RecRedisKeys.interest(userId), java.time.Duration.ofSeconds(INTEREST_TTL_SECONDS));
        } else {
            markLoaded(userId);
        }
        return result;
    }

    private void markLoaded(long userId) {
        redis.opsForValue().set(RecRedisKeys.interestLoaded(userId), "1",
                LOADED_MARKER_TTL_SECONDS, TimeUnit.SECONDS);
    }

    private static Map<Long, Double> parse(Map<Object, Object> raw) {
        Map<Long, Double> m = new LinkedHashMap<>(raw.size() * 2);
        for (Map.Entry<Object, Object> e : raw.entrySet()) {
            try {
                long tagId = Long.parseLong(String.valueOf(e.getKey()));
                double score = Double.parseDouble(String.valueOf(e.getValue()));
                if (score > 0D) {
                    m.put(tagId, score);
                }
            } catch (NumberFormatException ignore) {
                // 脏数据跳过
            }
        }
        return m;
    }

    /* ================================================================= */
    /*                              写                                   */
    /* ================================================================= */

    /**
     * 调整单个标签的兴趣分（Lua 原子：累加 + 裁剪 + 续期）。
     *
     * @param delta 正数=加强，负数=削弱
     */
    public void adjust(long userId, long tagId, double delta) {
        if (Math.abs(delta) < 1e-9) {
            return;
        }
        try {
            redis.execute(interestScript,
                    List.of(RecRedisKeys.interest(userId)),
                    String.valueOf(tagId),
                    String.valueOf(delta),
                    String.valueOf(props.getInterestCap()),
                    String.valueOf(INTEREST_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("[Interest] 调整兴趣分失败, userId={}, tagId={}, delta={}", userId, tagId, delta, e);
        }
    }

    /** 按文章批量调整兴趣分（文章的所有标签一起加减） */
    public void adjustByArticle(long userId, long articleId, double delta) {
        List<Long> tagIds = articleTags(articleId);
        for (Long tagId : tagIds) {
            adjust(userId, tagId, delta);
        }
    }

    /* ================================================================= */
    /*                          文章标签（内容侧）                        */
    /* ================================================================= */

    /** 查询单篇文章的标签 ID */
    public List<Long> articleTags(long articleId) {
        List<ArticleTag> list = articleTagMapper.selectList(
                new LambdaQueryWrapper<ArticleTag>().eq(ArticleTag::getArticleId, articleId));
        return toTagIds(list);
    }

    /**
     * 批量查询多篇文章的标签（一次 SQL 解决，避免 N+1）。
     *
     * @return articleId → tagId 列表
     */
    public Map<Long, List<Long>> articleTagsBatch(Collection<Long> articleIds) {
        Map<Long, List<Long>> result = new HashMap<>();
        if (articleIds == null || articleIds.isEmpty()) {
            return result;
        }
        List<ArticleTag> list = articleTagMapper.selectList(
                new LambdaQueryWrapper<ArticleTag>().in(ArticleTag::getArticleId, articleIds));
        if (list == null || list.isEmpty()) {
            return result;
        }
        for (ArticleTag at : list) {
            if (at.getArticleId() == null || at.getTagId() == null) {
                continue;
            }
            result.computeIfAbsent(at.getArticleId(), k -> new ArrayList<>()).add(at.getTagId());
        }
        return result;
    }

    private static List<Long> toTagIds(List<ArticleTag> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> tags = new ArrayList<>(list.size());
        for (ArticleTag at : list) {
            if (at.getTagId() != null) {
                tags.add(at.getTagId());
            }
        }
        return tags;
    }

    /* ================================================================= */
    /*                          兴趣匹配打分                              */
    /* ================================================================= */

    /**
     * 计算文章与用户兴趣的匹配度（0~1）。
     *
     * <p>公式：{@code 1 - exp(-Σ_t min(1, interest[t]/cap) * weight[t])}
     *
     * <p>为什么用"饱和累加"而不是简单平均：
     * <ul>
     *   <li><b>简单平均</b>的问题：一篇文章命中 1 个高兴趣标签得 1.0，
     *       命中 3 个高兴趣标签也只能得 1.0，无法区分"精准命中"和"全面命中"；</li>
     *   <li><b>饱和累加</b>：命中标签越多、兴趣越强，得分单调递增，
     *       但被 {@code 1 - e^-x} 压在上界内 —— 3 个标签 ≈ 0.95，已经接近满分，
     *       避免"堆标签"的内容无限得分。</li>
     * </ul>
     *
     * @param articleTagIds 文章标签（按权重降序，主标签在前）
     * @param interests     用户兴趣画像
     */
    public double interestMatch(List<Long> articleTagIds, Map<Long, Double> interests) {
        if (articleTagIds == null || articleTagIds.isEmpty() || interests == null || interests.isEmpty()) {
            return 0D;
        }
        double cap = Math.max(1e-6D, props.getInterestCap());
        int maxTags = Math.max(1, props.getInterestMaxTagsPerArticle());
        double matched = 0D;
        int inspected = 0;
        for (Long tagId : articleTagIds) {
            if (inspected++ >= maxTags) {
                break;
            }
            Double interest = interests.get(tagId);
            if (interest == null || interest <= 0D) {
                continue;
            }
            matched += Math.min(1D, interest / cap);
        }
        if (matched <= 0D) {
            return 0D;
        }
        return 1D - Math.exp(-matched);
    }

    /* ================================================================= */
    /*                          持久化（定时任务调用）                     */
    /* ================================================================= */

    /**
     * 把 Redis 兴趣画像回写 DB（只保留分数最高的若干标签，避免画像无限膨胀）。
     *
     * @return 实际写入条数
     */
    public int persist(long userId) {
        Map<Long, Double> interests = interests(userId);
        if (interests.isEmpty()) {
            return 0;
        }
        List<Map.Entry<Long, Double>> entries = new ArrayList<>(interests.entrySet());
        entries.sort(Comparator.comparingDouble((Map.Entry<Long, Double> e) -> e.getValue()).reversed());
        int keep = Math.min(entries.size(), Math.max(1, props.getInterestPersistMinTags()));
        List<UserInterest> list = new ArrayList<>(keep);
        for (int i = 0; i < keep; i++) {
            UserInterest ui = new UserInterest();
            ui.setId(IdWorker.getId());
            ui.setUserId(userId);
            ui.setTagId(entries.get(i).getKey());
            ui.setScore(BigDecimal.valueOf(entries.get(i).getValue()).setScale(4, RoundingMode.HALF_UP));
            list.add(ui);
        }
        try {
            return interestMapper.upsertBatch(list);
        } catch (Exception e) {
            log.warn("[Interest] 兴趣画像回写失败, userId={}", userId, e);
            return 0;
        }
    }
}
