package com.health.social.like;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 点赞变更事件（MQ 载体）。
 *
 * <p>关键设计：<b>同时携带最终状态与增量</b>。
 * <ul>
 *   <li>{@code liked}：用于 upsert 用户-文章关系表，同一 (user, article) 只需保留<b>最后一条</b>；</li>
 *   <li>{@code delta}：用于累加文章点赞数，+1 与 -1 在同一聚合窗口内可以互相抵消，
 *       避免"先赞后取消"被错误地扣掉一次计数。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LikeEvent implements Serializable {

    private Long userId;

    private Long articleId;

    /** 1 已点赞 / 0 已取消 */
    private Integer liked;

    /** +1 / -1，由 Lua 脚本返回 */
    private Integer delta;

    /** 事件发生时间戳（毫秒） */
    private Long ts;

    public static LikeEvent of(Long userId, Long articleId, int liked, int delta) {
        return new LikeEvent(userId, articleId, liked, delta, System.currentTimeMillis());
    }
}
