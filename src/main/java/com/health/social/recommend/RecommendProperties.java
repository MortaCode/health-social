package com.health.social.recommend;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推荐模块配置（{@code health.recommend.*}）。
 *
 * <p>所有算法参数都外置可调：召回路数、候选上限、时间衰减半衰期、打散约束、
 * 排序权重档位……这样"调参"不需要改代码，也便于做 A/B 实验时按配置下发不同权重。
 *
 * <p>注意：三个定时任务的 cron（{@code pool-reconcile-cron} / {@code sim-rebuild-cron} /
 * {@code interest-persist-cron}）以及缓冲刷盘间隔 {@code flush-interval-ms} 不在这里声明 ——
 * 它们由 {@code @Scheduled} 的占位符直接读取，声明在这里也无法被使用（Spring 的
 * {@code @Scheduled} 只支持占位符，不支持引用 bean 属性），放进来只会变成死字段。
 */
@Data
@Component
@ConfigurationProperties(prefix = "health.recommend")
public class RecommendProperties {

    /* ---------------------------- 召回 ---------------------------- */

    /** 单路召回上限（每路各取这么多，合并后再统一截断） */
    private int recallLimitPerChannel = 200;

    /** 进入排序阶段的候选上限（防止 5 路 × 200 条全部走富化与打分） */
    private int candidateLimit = 800;

    /** 关注召回：最多扫描多少个关注对象（防止关注 5000 人时把 Redis 打穿） */
    private int followRecallMaxFollowees = 200;

    /** 关注召回：每个关注对象最多取几条 */
    private int followRecallPerAuthor = 20;

    /** 兴趣召回：最多用几个兴趣标签 */
    private int interestRecallMaxTags = 10;

    /** 兴趣召回：每个标签最多取几条 */
    private int interestRecallPerTag = 100;

    /* ---------------------------- 排序 ---------------------------- */

    /** 时间衰减半衰期（小时）：24 表示每过 24 小时，新鲜度分衰减到一半 */
    private double freshnessHalfLifeHours = 24;

    /** 兴趣匹配：单篇文章最多参与计算的标签数 */
    private int interestMaxTagsPerArticle = 5;

    /** 排序权重（有行为数据的老用户） */
    private Weights weights = new Weights();

    /** 排序权重（冷启动：无兴趣、无点赞行为的新用户） */
    private Weights coldStartWeights = Weights.coldStart();

    /* ---------------------------- 打散 ---------------------------- */

    /** 一页内同一作者最多出现几条 */
    private int maxPerAuthor = 2;

    /** 一页内同一标签最多出现几条 */
    private int maxPerTag = 3;

    /* ---------------------------- 曝光去重 ---------------------------- */

    /** 在线曝光集合保留的条数上限（ZSet 按时间裁剪） */
    private int exposureWindowSize = 2000;

    /** 曝光集合 TTL（天） */
    private int exposureTtlDays = 30;

    /** 是否过滤掉已经点过赞的文章（默认开：不重复推荐用户已经认可过的内容） */
    private boolean filterLiked = true;

    /* ---------------------------- 兴趣画像 ---------------------------- */

    /** 兴趣分上限（防止单一标签无限累积） */
    private double interestCap = 10.0;

    /** 点击一次的兴趣增量 */
    private double interestBoostClick = 0.3;

    /** 点赞一次的兴趣增量 */
    private double interestBoostLike = 1.0;

    /** 不感兴趣的兴趣惩罚量 */
    private double interestPenaltyDislike = 2.0;

    /** 兴趣回写 DB 时保留的最少兴趣标签数 */
    private int interestPersistMinTags = 5;

    /* ---------------------------- 相似度 ---------------------------- */

    /** 内容相似度（标签 Jaccard）重建时处理的最近文章数 */
    private int simRebuildArticleLimit = 2000;

    /**
     * 同一标签下参与两两比较的文章数上限。
     *
     * <p>相似度计算是"同标签内两两比较"，复杂度 O(Σ C(n_tag, 2))。
     * 热门标签（如"高血压"）下如果挂了几千篇文章，两两比较会直接爆掉。
     * 因此每个标签只取最近 N 篇参与 —— 老内容本来也不需要实时算相似度。
     */
    private int simRebuildGroupCap = 100;

    /** 每篇文章保留的相似文章数 */
    private int simTopK = 50;

    /** 实时共现：单个用户参与共现的最近点赞文章数上限 */
    private int simCoLikeSeedLimit = 50;

    /** 相似召回：每篇种子文章取几条 */
    private int simRecallPerSeed = 30;

    /** 相似召回：最多用几篇种子文章 */
    private int simRecallMaxSeeds = 5;

    /* ---------------------------- 候选池 ---------------------------- */

    /** 候选池最大条数（ZSet 裁剪） */
    private int poolMaxSize = 5000;

    /** 兜底扫描每批条数 */
    private int poolReconcileBatch = 500;

    /** 活跃用户榜保留条数（兴趣回写的输入） */
    private int activeUserWindowSize = 5000;

    /* ---------------------------- 其他 ---------------------------- */

    private int defaultPageSize = 20;

    private int maxPageSize = 50;

    /** 单次刷盘最大条数 */
    private int flushBatchSize = 500;

    /**
     * 排序权重档。
     *
     * <p>各项特征都被归一化到 {@code [0,1]}，权重之和不必为 1 —— 排序只看相对大小，
     * 但保持和为 1 更利于横向比较与调参。
     */
    @Data
    public static class Weights {

        /** 全局热度：曝光 + 点击的对数归一 */
        private double hot = 0.28;

        /** 内容质量：点赞率的 Wilson 下界（小样本自动降权） */
        private double quality = 0.24;

        /** 新鲜度：按半衰期指数衰减 */
        private double fresh = 0.20;

        /** 兴趣匹配：文章标签与用户兴趣画像的加权交并比 */
        private double interest = 0.14;

        /** 关注加成：作者是我关注的人 */
        private double follow = 0.06;

        /** 作者权威度：明星医生 / 认证医生 / 普通用户 */
        private double author = 0.04;

        /** 召回通道置信度：多路命中、且命中高置信通道时加分 */
        private double channel = 0.04;

        /**
         * 冷启动权重：新用户没有兴趣画像、也没有点赞行为，
         * 兴趣项权重必须归零（否则整个兴趣维度都是 0，等于白占权重），
         * 把这部分权重让给"热点 + 新鲜 + 质量"这些不依赖个人行为的特征。
         */
        public static Weights coldStart() {
            Weights w = new Weights();
            w.interest = 0.0;
            w.hot = 0.38;
            w.fresh = 0.28;
            w.quality = 0.20;
            w.follow = 0.06;
            w.author = 0.04;
            w.channel = 0.04;
            return w;
        }

        /** 各项之和（运维接口展示用） */
        public double sum() {
            return hot + quality + fresh + interest + follow + author + channel;
        }
    }
}
