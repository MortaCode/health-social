package com.health.social.recommend.model;

import com.health.social.entity.Article;
import lombok.Data;

import java.util.List;

/**
 * 推荐结果页。
 *
 * @param items      推荐条目（已按最终分降序、且完成打散）
 * @param nextCursor 下一页游标；{@code null} 表示没有更多
 * @param hasMore    是否还有下一页
 * @param requestId  本次请求的链路 ID，客户端在反馈时回传，用于把曝光与反馈关联
 */
public record RecommendPage(List<RecommendItem> items, String nextCursor, boolean hasMore, String requestId) {

    /**
     * 单条推荐结果。
     *
     * <p>除了文章本体，还额外返回 {@code score} / {@code channels} / {@code reason} 三个字段：
     * <ul>
     *   <li>{@code channels}：命中的召回通道（可做线上归因、排查"为什么给我推这个"）；</li>
     *   <li>{@code reason}：面向用户的可解释文案 —— 推荐系统不解释自己，用户就会怀疑它；</li>
     *   <li>{@code score}：排序分快照，便于对照曝光流水做离线评估。</li>
     * </ul>
     */
    @Data
    public static class RecommendItem {

        private Article article;

        /** 最终排序分 */
        private double score;

        /** 命中的召回通道 */
        private List<String> channels;

        /** 可解释的推荐理由（展示给用户） */
        private String reason;

        /** 当前用户是否已点赞 */
        private Boolean liked;

        /** 在本次列表中的位置（0 起），与曝光流水一致 */
        private int position;
    }
}
