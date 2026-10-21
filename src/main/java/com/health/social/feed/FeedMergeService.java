package com.health.social.feed;

import com.health.social.common.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Feed 时间线归并服务。
 *
 * <h3>归并思路</h3>
 * <pre>
 *   源 1：收件箱 feed:inbox:{userId}          （推模式写入的普通用户动态）
 *   源 2：大 V 分片 feed:outbox:shard:{v}:{b} （拉模式按需拉取）
 *                        │
 *                        ▼
 *              k 路归并（每路内部已按 score 降序）
 *                        │
 *                  去重 + 全局降序 + 截断 pageSize
 * </pre>
 *
 * <p>复杂度：O(N log k)，N = 参与归并的总条数，k = 大 V 数量 + 1。
 * 每路最多取 {@code pageSize} 条即可（因为最终只要 pageSize 条），
 * 所以 N 被严格限制在 {@code pageSize * (k + 1)}，不会因为大 V 十万级发帖而爆炸。
 *
 * <h3>游标</h3>
 * <p>使用 {@code score:articleId} 复合游标。只用时间戳做游标时，
 * 同一秒发布的两条动态会被翻页漏掉（{@code create_time} 是 DATETIME 秒级精度），
 * 加上 articleId 做二级排序键即可严格有序。格式与解析见 {@link FeedCursor}。
 *
 * <h3>翻页必须守住的两条边界</h3>
 * <ol>
 *   <li><b>上界取游标，不取 now。</b>如果用 now 做上界，两次请求之间新发的帖子
 *       score 更高，会占满 {@code count} 配额，随后全部被"已返回"判断过滤掉，
 *       整页变成空的 —— 而 Feed 是实时流，两次请求之间有新帖几乎是必然的。</li>
 *   <li><b>下界不能被"现在"抬高。</b>首页用 now - pullDays 限制首屏回溯范围；
 *       翻页时下界只能由硬下限（max-backtrack-days）决定，否则游标一超过时间窗，
 *       下界就跑到游标前面去，查询区间为空，翻页静默终止。</li>
 * </ol>
 */
@Slf4j
@Service
public class FeedMergeService {

    private final StringRedisTemplate redis;
    private final BigKeySplitter splitter;
    private final FollowService followService;

    /** 首屏回溯天数：决定第一页从哪里开始往下取 */
    @Value("${health.feed.pull-days:7}")
    private int pullDays;

    /** 翻页硬下限：游标最多能往前翻多久，防止无限回溯 */
    @Value("${health.feed.max-backtrack-days:90}")
    private int maxBacktrackDays;

    @Value("${health.feed.default-page-size:20}")
    private int defaultPageSize;

    public FeedMergeService(StringRedisTemplate redis, BigKeySplitter splitter, FollowService followService) {
        this.redis = redis;
        this.splitter = splitter;
        this.followService = followService;
    }

    /**
     * 生成一页时间线
     *
     * @param userId 当前用户
     * @param cursor 上一页返回的游标，首页传 null
     * @param size   页大小
     */
    public FeedPage merge(long userId, String cursor, Integer size) {
        int pageSize = (size == null || size <= 0) ? defaultPageSize : Math.min(size, 100);
        // 多取一条：既用来探测 hasMore，也抵掉"游标自己那条不会再返回"造成的损耗
        int fetch = pageSize + 1;

        FeedCursor.Parsed parsed = FeedCursor.parse(cursor);
        boolean hasCursor = parsed != null;
        double cursorScore = hasCursor ? parsed.score() : 0D;
        long cursorArticleId = hasCursor ? parsed.articleId() : 0L;

        long now = System.currentTimeMillis();

        // 上界：首页取 now，翻页取游标 —— 从游标往下取，新帖不会挤占本页配额
        double maxScore = hasCursor ? cursorScore : now;
        // 下界：首页受 pullDays 约束；翻页只受硬下限约束，不能被 now 抬高
        long lowerBound = hasCursor
                ? now - (long) maxBacktrackDays * 86_400_000L
                : now - (long) pullDays * 86_400_000L;

        List<List<FeedItem>> sources = new ArrayList<>();

        // ---------- 源 1：收件箱 ----------
        String inboxKey = RedisKeys.feedInbox(userId);
        // 同 score 分组里上一页已经返回过的部分，直接用 offset 跳过，不要取回来再过滤
        long inboxOffset = hasCursor
                ? FeedCursor.sameScorePrefix(redis, inboxKey, cursorScore, cursorArticleId)
                : 0L;
        Set<ZSetOperations.TypedTuple<String>> inboxTuples = redis.opsForZSet()
                .reverseRangeByScoreWithScores(inboxKey, lowerBound, maxScore, inboxOffset, fetch);
        sources.add(toItems(inboxTuples, -1L, FeedItem.SRC_INBOX));

        // ---------- 源 2：关注的大 V 分片 ----------
        List<Long> bigVs = followService.myBigVFollowees(userId);
        for (Long v : bigVs) {
            List<FeedItem> pulled = splitter.range(v, lowerBound, maxScore, fetch,
                    hasCursor ? cursorScore : null, cursorArticleId);
            if (!pulled.isEmpty()) {
                sources.add(pulled);
            }
        }

        // ---------- 归并 ----------
        List<FeedItem> merged = kWayMerge(sources, fetch);

        boolean hasMore = merged.size() > pageSize;
        List<FeedItem> items = hasMore ? new ArrayList<>(merged.subList(0, pageSize)) : merged;

        // 游标必须取"本页真正返回的最后一条"，所以要在截断之后算
        String nextCursor = items.size() == pageSize
                ? FeedCursor.format(items.get(items.size() - 1).getScore(),
                                    items.get(items.size() - 1).getArticleId())
                : null;
        return new FeedPage(items, nextCursor, hasMore);
    }

    /**
     * k 路归并：所有源内部已按 (score desc, articleId desc) 排好序，
     * 用最小堆（按 score 降序比较）做归并
     */
    private List<FeedItem> kWayMerge(List<List<FeedItem>> sources, int limit) {
        Comparator<Node> cmp = (a, b) -> {
            int c = Double.compare(b.item.getScore(), a.item.getScore());
            if (c != 0) {
                return c;
            }
            return Long.compare(b.item.getArticleId(), a.item.getArticleId());
        };
        PriorityQueue<Node> heap = new PriorityQueue<>(cmp);
        int[] idx = new int[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            if (!sources.get(i).isEmpty()) {
                heap.offer(new Node(i, sources.get(i).get(0)));
            }
        }

        List<FeedItem> out = new ArrayList<>(limit);
        Set<Long> seen = new HashSet<>(limit * 4);
        while (!heap.isEmpty() && out.size() < limit) {
            Node node = heap.poll();
            idx[node.src]++;
            if (seen.add(node.item.getArticleId())) {
                out.add(node.item);
            }
            fillNext(sources, idx, node.src, heap);
        }
        return out;
    }

    private void fillNext(List<List<FeedItem>> sources, int[] idx, int src, PriorityQueue<Node> heap) {
        List<FeedItem> list = sources.get(src);
        if (idx[src] < list.size()) {
            heap.offer(new Node(src, list.get(idx[src])));
        }
    }

    private List<FeedItem> toItems(Set<ZSetOperations.TypedTuple<String>> tuples, long authorId, int source) {
        List<FeedItem> items = new ArrayList<>();
        if (tuples == null) {
            return items;
        }
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            if (t.getValue() == null) {
                continue;
            }
            try {
                long articleId = Long.parseLong(t.getValue());
                double score = t.getScore() == null ? 0D : t.getScore();
                items.add(FeedItem.of(articleId, authorId, score, source));
            } catch (NumberFormatException ignore) {
                // skip
            }
        }
        return items;
    }

    /** 归并堆节点 */
    private record Node(int src, FeedItem item) {
    }

    /**
     * 一页 Feed
     */
    public record FeedPage(List<FeedItem> items, String nextCursor, boolean hasMore) {
    }
}
