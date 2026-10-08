package com.health.social.recommend;

import com.health.social.common.Result;
import com.health.social.common.UserContext;
import com.health.social.recommend.feedback.RecommendFeedbackService;
import com.health.social.recommend.model.RecommendPage;
import com.health.social.recommend.pipeline.CandidatePoolMaintainer;
import com.health.social.recommend.profile.ItemSimilarityService;
import com.health.social.ratelimit.RateLimit;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 推荐模块接口。
 *
 * <p>全部挂在 {@code /recommend} 命名空间下，与推流模块的 {@code /feed} 完全并列 ——
 * 客户端可以选择"关注"标签页（走 {@code /feed}）和"推荐"标签页（走 {@code /recommend}），
 * 服务端两条链路互不影响。
 *
 * <h3>接口清单</h3>
 * <table border="1">
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/recommend</td><td>推荐流（多路召回 + 排序 + 打散）</td></tr>
 *   <tr><td>POST</td><td>/recommend/feedback</td><td>反馈闭环（CLICK / LIKE / DISLIKE）</td></tr>
 *   <tr><td>GET</td><td>/recommend/debug/{articleId}</td><td>单篇推荐特征解释</td></tr>
 *   <tr><td>GET</td><td>/recommend/stats</td><td>模块运行状态</td></tr>
 *   <tr><td>POST</td><td>/recommend/sim/rebuild</td><td>手动触发相似度重建</td></tr>
 *   <tr><td>POST</td><td>/recommend/pool/backfill</td><td>手动把存量文章灌入候选池</td></tr>
 * </table>
 *
 * <p>限流复用既有的 {@code @RateLimit} 令牌桶切面（与推流模块同一套机制），
 * 但使用独立的 {@code api} 标识，因此推荐接口的配额与 Feed 接口互不挤占。
 */
@RestController
@RequestMapping("/recommend")
public class RecommendController {

    private final RecommendService recommendService;
    private final RecommendFeedbackService feedbackService;
    private final ItemSimilarityService similarityService;
    private final CandidatePoolMaintainer poolMaintainer;

    public RecommendController(RecommendService recommendService,
                               RecommendFeedbackService feedbackService,
                               ItemSimilarityService similarityService,
                               CandidatePoolMaintainer poolMaintainer) {
        this.recommendService = recommendService;
        this.feedbackService = feedbackService;
        this.similarityService = similarityService;
        this.poolMaintainer = poolMaintainer;
    }

    /**
     * 拉取一页推荐。
     *
     * @param cursor 上一页返回的游标（服务端不做 offset 跳过，去重由曝光机制保证）
     * @param size   页大小，默认 20，上限 50
     * @param scene  场景标识，默认 home
     */
    @RateLimit(api = "recommend")
    @GetMapping
    public Result<RecommendPage> recommend(@RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) String scene) {
        long userId = UserContext.getUserId();
        return Result.ok(recommendService.recommend(userId, cursor, size, scene));
    }

    /**
     * 反馈上报。
     *
     * <p>{@code permits = 2}：反馈是轻量写，但一个用户短时间狂刷反馈没有意义，
     * 用 2 个令牌的消耗稍微收紧一点配额。
     */
    @RateLimit(api = "recommend.feedback", permits = 2)
    @PostMapping("/feedback")
    public Result<Void> feedback(@RequestBody FeedbackRequest request) {
        if (request == null || request.getArticleId() == null) {
            return Result.fail(400, "articleId 不能为空");
        }
        long userId = UserContext.getUserId();
        boolean ok = feedbackService.feedback(userId, request.getArticleId(),
                request.getAction(), request.getScene());
        if (!ok) {
            return Result.fail(400, "非法反馈动作，仅支持 CLICK / LIKE / DISLIKE");
        }
        return Result.ok();
    }

    /**
     * 解释某篇文章的推荐特征（内容标签、相似文章、实时计数、当前权重）。
     */
    @GetMapping("/debug/{articleId}")
    public Result<Map<String, Object>> debug(@PathVariable Long articleId,
                                             @RequestParam(defaultValue = "20") Integer topN) {
        return Result.ok(recommendService.debug(articleId, topN == null ? 20 : topN));
    }

    /**
     * 推荐模块运行状态：候选池规模、水位线、召回通道、缓冲积压等。
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(recommendService.stats(UserContext.getUserId()));
    }

    /**
     * 手动触发相似度重建（运维用；正常情况下由定时任务完成）。
     */
    @RateLimit(api = "recommend.sim.rebuild", permits = 5)
    @PostMapping("/sim/rebuild")
    public Result<Integer> rebuildSimilarity() {
        return Result.ok(similarityService.rebuildContentSimilarity());
    }

    /**
     * 手动把存量文章灌入候选池（首次上线 / 数据修复时使用）。
     */
    @RateLimit(api = "recommend.pool.backfill", permits = 5)
    @PostMapping("/pool/backfill")
    public Result<Integer> backfill(@RequestParam(defaultValue = "500") Integer limit) {
        return Result.ok(poolMaintainer.backfill(limit == null ? 500 : limit));
    }

    /** 反馈请求体 */
    @Data
    public static class FeedbackRequest {

        private Long articleId;

        /** CLICK / LIKE / DISLIKE */
        private String action;

        private String scene;
    }
}
