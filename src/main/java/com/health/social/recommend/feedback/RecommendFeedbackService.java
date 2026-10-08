package com.health.social.recommend.feedback;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.health.social.entity.RecFeedback;
import com.health.social.recommend.RecommendProperties;
import com.health.social.recommend.RecRedisKeys;
import com.health.social.recommend.filter.ExposureFilter;
import com.health.social.recommend.profile.InterestProfileService;
import com.health.social.recommend.profile.ItemSimilarityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 推荐反馈闭环服务。
 *
 * <h3>三种反馈，三种语义</h3>
 * <table border="1">
 *   <tr><th>动作</th><th>含义</th><th>系统动作</th></tr>
 *   <tr>
 *     <td>CLICK</td><td>弱正向信号</td>
 *     <td>点击计数 +1；对文章标签的兴趣分小幅加强（{@code interestBoostClick}）</td>
 *   </tr>
 *   <tr>
 *     <td>LIKE</td><td>强正向信号</td>
 *     <td>点击计数 +1；兴趣分大幅加强；<b>写入共现矩阵</b>（相似召回的燃料）</td>
 *   </tr>
 *   <tr>
 *     <td>DISLIKE</td><td>明确负向信号</td>
 *     <td>不感兴趣计数 +1；兴趣分<b>扣减</b>；并写入曝光集合，短期内不再推给该用户</td>
 *   </tr>
 * </table>
 *
 * <h3>为什么"不感兴趣"要写进曝光集合</h3>
 * <p>排序阶段的负反馈惩罚 {@code 1/(1+dislikes)} 只是"压低排名"，并不能保证它不出现。
 * 用户点了"不感兴趣"是明确表达"我不想再看到这条"，属于强意图，应当<b>硬过滤</b>。
 * 复用曝光集合（而不是新建一个 blocklist）的好处：曝光集合本身会滑动裁剪，
 * 所以"永不推荐"其实是"最近一段时间不推荐"，避免误操作造成永久损失。
 *
 * <h3>闭环在哪里</h3>
 * <pre>
 *   曝光(imp) ──► 排序特征 hot / quality
 *   点击(clk) ──► 兴趣画像 ──► 兴趣召回 + 兴趣匹配打分 ──► 更准的推荐
 *   点赞(like) ─► 共现矩阵 ──► 相似召回
 *   不感兴趣 ──► 负反馈惩罚 + 硬过滤 ──► 立刻不再打扰
 * </pre>
 * 四类数据都同时落入 Redis（在线生效）与 {@code t_rec_exposure}/{@code t_rec_feedback}（离线分析）。
 */
@Slf4j
@Service
public class RecommendFeedbackService {

    /** 反馈动作 */
    public enum Action {
        /** 点击（弱正向） */
        CLICK,
        /** 点赞（强正向） */
        LIKE,
        /** 不感兴趣（负向） */
        DISLIKE;

        /** 解析动作字符串，非法值返回 null（由调用方转成 400） */
        public static Action parse(String raw) {
            if (raw == null) {
                return null;
            }
            try {
                return valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private final StringRedisTemplate redis;
    private final InterestProfileService interestProfileService;
    private final ItemSimilarityService similarityService;
    private final ExposureFilter exposureFilter;
    private final RecommendPersistenceBuffer buffer;
    private final RecommendProperties props;

    public RecommendFeedbackService(StringRedisTemplate redis,
                                    InterestProfileService interestProfileService,
                                    ItemSimilarityService similarityService,
                                    ExposureFilter exposureFilter,
                                    RecommendPersistenceBuffer buffer,
                                    RecommendProperties props) {
        this.redis = redis;
        this.interestProfileService = interestProfileService;
        this.similarityService = similarityService;
        this.exposureFilter = exposureFilter;
        this.buffer = buffer;
        this.props = props;
    }

    /**
     * 处理一次反馈。
     *
     * @param userId    用户
     * @param articleId 文章
     * @param actionRaw CLICK / LIKE / DISLIKE
     * @param scene     场景（home 等）
     * @return 是否被接受（动作非法返回 false）
     */
    public boolean feedback(long userId, long articleId, String actionRaw, String scene) {
        Action action = Action.parse(actionRaw);
        if (action == null || articleId <= 0L || userId <= 0L) {
            return false;
        }
        String safeScene = (scene == null || scene.isBlank()) ? "home" : scene.trim();

        try {
            switch (action) {
                case CLICK -> {
                    increment(RecRedisKeys.statClk(articleId));
                    interestProfileService.adjustByArticle(userId, articleId, props.getInterestBoostClick());
                }
                case LIKE -> {
                    // 点赞必然包含了一次点击行为
                    increment(RecRedisKeys.statClk(articleId));
                    interestProfileService.adjustByArticle(userId, articleId, props.getInterestBoostLike());
                    // 共现矩阵是相似召回的燃料，只有"点赞"这种强信号才值得写入
                    similarityService.onLike(userId, articleId);
                }
                case DISLIKE -> {
                    increment(RecRedisKeys.statDis(articleId));
                    interestProfileService.adjustByArticle(userId, articleId, -props.getInterestPenaltyDislike());
                    // 硬过滤：写进"近期不再推荐"集合，短期内不再打扰该用户
                    exposureFilter.markBlocked(userId, articleId);
                }
                default -> {
                    return false;
                }
            }
        } catch (Exception e) {
            // 反馈更新失败不应让客户端拿到错误（用户已经完成操作），记录日志即可
            log.warn("[Feedback] 反馈更新失败, userId={}, articleId={}, action={}", userId, articleId, action, e);
        }

        // 异步落库，供离线训练与效果归因
        RecFeedback fb = new RecFeedback();
        fb.setId(IdWorker.getId());
        fb.setUserId(userId);
        fb.setArticleId(articleId);
        fb.setScene(safeScene);
        fb.setAction(action.name());
        fb.setCreateTime(LocalDateTime.now());
        buffer.offerFeedback(fb);

        log.debug("[Feedback] 已处理: user={}, article={}, action={}", userId, articleId, action);
        return true;
    }

    private void increment(String key) {
        try {
            redis.opsForValue().increment(key);
        } catch (Exception e) {
            log.debug("[Feedback] 计数失败, key={}", key, e);
        }
    }
}
