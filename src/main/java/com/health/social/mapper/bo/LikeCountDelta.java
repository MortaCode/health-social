package com.health.social.mapper.bo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文章点赞数增量（批量聚合落库用）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LikeCountDelta {

    private Long articleId;

    /** +1 / -1 */
    private Long delta;
}
