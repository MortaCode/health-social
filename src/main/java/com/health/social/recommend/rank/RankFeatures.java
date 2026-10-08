package com.health.social.recommend.rank;

import lombok.Data;

/**
 * 排序特征（一条候选在排序阶段所需的全部输入）。
 *
 * <p>把特征从"数据源"里抽出来，是为了让 {@link Ranker} 变成<b>纯函数</b>：
 * 输入特征、输出分数，不碰 Redis / DB。好处是排序逻辑可以被单元测试直接覆盖，
 * 调权重时也能离线用同一套代码跑历史样本回放。
 */
@Data
public class RankFeatures {

    private long articleId;

    private long authorId;

    private long publishTimeMs;

    /* ---------------- 全局统计（来自 Redis 实时计数） ---------------- */

    /** 曝光次数 */
    private long impressions;

    /** 点击次数 */
    private long clicks;

    /** 不感兴趣次数（负反馈） */
    private long dislikes;

    /* ---------------- 内容质量（来自文章本体） ---------------- */

    /** 点赞数（DB 快照，用于排序足够；展示时仍以 Redis 权威计数为准） */
    private long likeCount;

    private long commentCount;

    /* ---------------- 个性化 ---------------- */

    /** 作者等级：0 普通 1 认证医生 2 明星医生 */
    private int authorLevel;

    /** 兴趣匹配度 0~1（文章标签与用户兴趣画像的交并比） */
    private double interestMatch;

    /** 作者是否被我关注 */
    private boolean followed;

    /** 召回通道置信度 0~1 */
    private double channelScore;
}
