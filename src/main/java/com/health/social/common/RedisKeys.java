package com.health.social.common;

/**
 * Redis Key 统一定义。
 *
 * <p>注意 hash tag {@code {}} 的使用：
 * <ul>
 *   <li>需要被同一段 Lua 脚本原子操作的 key，使用相同 hash tag，保证在 Redis Cluster 下落在同一 slot；</li>
 *   <li>需要打散的大 Key 分片（如大 V Feed 分桶），故意<b>不</b>加 hash tag，让 CRC16 把分片均匀散到各 slot。</li>
 * </ul>
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /* ---------------- 热点探测 HeavyKeeper ---------------- */
    /** HeavyKeeper 桶数组（Hash）：所有 key 共用 {hot} 保证同 slot */
    public static final String HK_TABLE = "hk:table:{hot}";
    /** 热点榜单（ZSet） */
    public static final String HK_TOP = "hk:top:{hot}";
    /** 热点榜单按业务类型区分时的前缀 */
    public static final String hkTable(String type) {
        return "hk:table:{" + type + "}";
    }

    public static final String hkTop(String type) {
        return "hk:top:{" + type + "}";
    }

    /* ---------------- 多级缓存 ---------------- */
    /** 二级缓存：文章详情 */
    public static final String CACHE_ARTICLE = "cache:article:";
    /** 缓存重建分布式锁 */
    public static final String CACHE_LOCK = "cache:lock:";
    /** 缓存失效广播频道 */
    public static final String TOPIC_CACHE_INVALIDATE = "topic:cache:invalidate";
    /** 缓存预热广播频道（多节点一起预热） */
    public static final String TOPIC_CACHE_WARMUP = "topic:cache:warmup";

    public static String articleCache(long articleId) {
        return CACHE_ARTICLE + articleId;
    }

    public static String cacheLock(String bizKey) {
        return CACHE_LOCK + bizKey;
    }

    /* ---------------- 点赞 ---------------- */
    /** 文章点赞数计数器：article:like:count:{articleId} */
    public static final String ARTICLE_LIKE_COUNT = "article:like:count:";
    /** 用户点赞集合：user:like:set:{userId}（幂等判重 + 是否点过） */
    public static final String USER_LIKE_SET = "user:like:set:";
    /** 文章点赞人集合：article:like:users:{articleId} */
    public static final String ARTICLE_LIKE_USERS = "article:like:users:";

    public static String articleLikeCount(long articleId) {
        return ARTICLE_LIKE_COUNT + articleId;
    }

    public static String userLikeSet(long userId) {
        return USER_LIKE_SET + userId;
    }

    public static String articleLikeUsers(long articleId) {
        return ARTICLE_LIKE_USERS + articleId;
    }

    /* ---------------- Feed 流 ---------------- */
    /** 收件箱：feed:inbox:{userId} */
    public static final String FEED_INBOX = "feed:inbox:";
    /** 大 V 发件箱分片（无 hash tag，打散 slot）：feed:outbox:shard:{authorId}:{bucket} */
    public static final String FEED_OUTBOX_SHARD = "feed:outbox:shard:";
    /** 大 V 分桶元数据：feed:outbox:meta:{authorId} */
    public static final String FEED_OUTBOX_META = "feed:outbox:meta:";
    /** 普通用户发件箱（未分桶） */
    public static final String FEED_OUTBOX = "feed:outbox:";

    public static String feedInbox(long userId) {
        return FEED_INBOX + userId;
    }

    public static String feedOutboxShard(long authorId, long bucket) {
        return FEED_OUTBOX_SHARD + authorId + ":" + bucket;
    }

    public static String feedOutboxMeta(long authorId) {
        return FEED_OUTBOX_META + authorId;
    }

    public static String feedOutbox(long authorId) {
        return FEED_OUTBOX + authorId;
    }

    /* ---------------- 限流 ---------------- */
    /** 令牌桶：rl:token:{userId}:{api} */
    public static final String RATE_LIMIT_TOKEN = "rl:token:";

    public static String rateLimitToken(long userId, String api) {
        return RATE_LIMIT_TOKEN + userId + ":" + api;
    }
}
