package com.health.social.feed;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Feed 流条目（时间线上的一个节点）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedItem implements Serializable {

    public static final int SRC_INBOX = 0;
    public static final int SRC_PULL_BIG_V = 1;

    private Long articleId;

    private Long authorId;

    /** 排序分：通常取发帖时间戳（毫秒） */
    private double score;

    /** 来源：0 收件箱（推） 1 大 V 拉取 */
    private Integer source;

    public static FeedItem of(long articleId, long authorId, double score, int source) {
        return new FeedItem(articleId, authorId, score, source);
    }
}
