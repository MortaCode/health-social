package com.health.social.recommend.model;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 召回上下文：一次推荐请求中，所有召回通道共享的用户侧输入。
 *
 * <p>设计要点：把"用户侧特征"一次性算好放进上下文，而不是让每个召回通道各自去查库/查缓存。
 * 5 个通道各查一次 DB 就是 5 次冗余 IO；集中加载一次后共享，是推荐链路最基础的性能优化。
 */
@Data
@Builder
public class RecallContext {

    /** 当前用户 */
    private long userId;

    /** 请求时间（毫秒），全链路统一取一次，避免各阶段时间漂移 */
    private long nowMs;

    /** 期望页大小 */
    private int size;

    /** 我关注的人（关注召回的输入） */
    @Builder.Default
    private List<Long> followees = Collections.emptyList();

    /** 用户兴趣画像：tagId → 兴趣分（兴趣召回的输入） */
    @Builder.Default
    private Map<Long, Double> interests = Collections.emptyMap();

    /** 最近点赞的文章 ID，按时间倒序（相似召回的种子） */
    @Builder.Default
    private List<Long> recentLiked = Collections.emptyList();

    /**
     * 是否冷启动用户。
     *
     * <p>判定标准：没有兴趣画像 <b>且</b> 没有点赞行为。这类用户如果按老用户的权重打分，
     * 兴趣项恒为 0，等于白白浪费 14% 的权重预算，排序结果会明显偏向"老而热"的内容。
     * 冷启动档把权重让给热点与新鲜度，见 {@code RecommendProperties.Weights#coldStart()}。
     */
    private boolean coldStart;
}
