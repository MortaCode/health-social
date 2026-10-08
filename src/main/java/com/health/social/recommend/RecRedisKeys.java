package com.health.social.recommend;

/**
 * 推荐模块 Redis Key 统一定义。
 *
 * <h3>为什么单独开一个类，而不是加到 common.RedisKeys</h3>
 * <p>{@code common.RedisKeys} 是推流 / 点赞 / 缓存等模块共享的文件。推荐模块是<b>新增</b>模块，
 * 把 key 定义收在本模块内，可以让"推荐"这个域整体可插拔 —— 删掉 {@code recommend} 包
 * 与这几张表即可完全回退，不需要回滚任何共享文件。
 *
 * <h3>命名规范</h3>
 * <p>统一 {@code rec:} 前缀，与推流模块的 {@code feed:}、点赞的 {@code article:like:*} 天然隔离，
 * 不会出现 key 冲突。所有 key 都<b>不加 hash tag</b>：本模块的读写都是单 key 操作，
 * 不需要 Lua 跨 key 原子性，让 CRC16 自然把热点散到不同 slot 更有利。
 *
 * <h3>在线 vs 离线</h3>
 * <p>这里定义的 key 全部是"在线状态"（毫秒级读写）。需要长期保存的数据
 * （标签、兴趣画像、曝光/反馈流水）由定时任务异步落库，见 {@code db/rec_schema.sql}。
 */
public final class RecRedisKeys {

    private RecRedisKeys() {
    }

    /* ===================== 候选池（内容侧） ===================== */

    /**
     * 全站新文章候选池：ZSet，member=articleId，score=发布时间毫秒。
     * 由文章发布事件 + 定时兜底扫描共同维护，是"新鲜度召回"的数据源，
     * 也是其他通道失效时的最后兜底。
     */
    public static final String CAND_FRESH = "rec:cand:fresh";

    /** 标签倒排索引前缀：rec:tag:articles:{tagId}，ZSet，score=发布时间毫秒 */
    public static final String TAG_ARTICLES = "rec:tag:articles:";

    /** 作者维度索引前缀：rec:author:articles:{authorId}，ZSet，score=发布时间毫秒 */
    public static final String AUTHOR_ARTICLES = "rec:author:articles:";

    /** 相似文章表前缀：rec:sim:{articleId}，ZSet，member=相似文章 ID，score=相似度 */
    public static final String SIM = "rec:sim:";

    /* ===================== 用户侧（在线状态） ===================== */

    /**
     * 已曝光集合：rec:exposed:{userId}，ZSet，score=曝光时间毫秒。
     * 用 ZSet 而不是 Set，是为了能按时间裁剪 —— 只保留最近 N 条，
     * 既控制内存，又让"很久以前看过"的内容有机会重新被推荐。
     */
    public static final String EXPOSED = "rec:exposed:";

    /** 用户兴趣画像：rec:interest:{userId}，Hash，field=tagId，value=兴趣分 */
    public static final String INTEREST = "rec:interest:";

    /**
     * 兴趣画像"已加载"标记：rec:interest:loaded:{userId}，String，短 TTL。
     *
     * <p>用途与推流侧缓存穿透防护同理：新用户兴趣画像在 Redis 与 DB 里都是空的，
     * 如果不留标记，每一次推荐请求都会回源查一次 {@code t_user_interest} ——
     * 高 QPS 下这就是把 DB 当缓存用。留一个 5 分钟的短 TTL 标记即可挡住。
     */
    public static final String INTEREST_LOADED = "rec:interest:loaded:";

    /** 用户最近点赞：rec:like:recent:{userId}，ZSet，score=点赞时间毫秒（相似召回的种子） */
    public static final String LIKE_RECENT = "rec:like:recent:";

    /* ===================== 实时统计（排序特征） ===================== */

    /** 曝光计数：rec:stat:imp:{articleId} */
    public static final String STAT_IMP = "rec:stat:imp:";
    /** 点击计数：rec:stat:clk:{articleId} */
    public static final String STAT_CLK = "rec:stat:clk:";
    /** 不感兴趣计数：rec:stat:dis:{articleId} */
    public static final String STAT_DIS = "rec:stat:dis:";

    /* ===================== 运维 / 分布式协调 ===================== */

    /** 相似度重建锁（多实例选主） */
    public static final String LOCK_SIM = "rec:lock:sim";
    /** 候选池兜底扫描锁 */
    public static final String LOCK_POOL = "rec:lock:pool";
    /** 候选池兜底扫描水位线：最后一次同步到的文章 create_time（毫秒） */
    public static final String POOL_WATERMARK = "rec:pool:watermark";
    /** 活跃用户榜：rec:active:users，ZSet，score=最近一次推荐请求时间（兴趣回写的输入） */
    public static final String ACTIVE_USERS = "rec:active:users";

    /* ===================== 拼接方法 ===================== */

    public static String tagArticles(long tagId) {
        return TAG_ARTICLES + tagId;
    }

    public static String authorArticles(long authorId) {
        return AUTHOR_ARTICLES + authorId;
    }

    public static String sim(long articleId) {
        return SIM + articleId;
    }

    public static String exposed(long userId) {
        return EXPOSED + userId;
    }

    public static String interest(long userId) {
        return INTEREST + userId;
    }

    public static String interestLoaded(long userId) {
        return INTEREST_LOADED + userId;
    }

    public static String likeRecent(long userId) {
        return LIKE_RECENT + userId;
    }

    public static String statImp(long articleId) {
        return STAT_IMP + articleId;
    }

    public static String statClk(long articleId) {
        return STAT_CLK + articleId;
    }

    public static String statDis(long articleId) {
        return STAT_DIS + articleId;
    }
}
