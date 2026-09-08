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
 * 同一毫秒发布的两条动态会被翻页漏掉；加上 articleId 做二级排序键即可严格有序。
 */
@Slf4j
@Service
public class FeedMergeService {

    private final StringRedisTemplate redis;
    private final BigKeySplitter splitter;
    private final FollowService followService;

    @Value("${health.feed.pull-days:7}")
    private int pullDays;

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

        // 解析游标：score:articleId
        double cursorScore = 0D;
        long cursorArticleId = 0L;
        boolean hasCursor = cursor != null && !cursor.isBlank();
        if (hasCursor) {
            String[] parts = cursor.split(":");
            try {
                cursorScore = Double.parseDouble(parts[0]);
                if (parts.length > 1) {
                    cursorArticleId = Long.parseLong(parts[1]);
                }
            } catch (NumberFormatException e) {
                hasCursor = false;
            }
        }

        long now = System.currentTimeMillis();
        // 拉模式下界：不允许无限回溯（历史数据走离线索服）
        long lowerBound = Math.max(cursorScore > 0 ? (long) cursorScore : 0L, now - pullDays * 86_400_000L);
        if (hasCursor) {
            lowerBound = Math.max(lowerBound, (long) cursorScore);
        }

        List<List<FeedItem>> sources = new ArrayList<>();

        // ---------- 源 1：收件箱 ----------
        Set<ZSetOperations.TypedTuple<String>> inboxTuples = redis.opsForZSet()
                .reverseRangeByScoreWithScores(RedisKeys.feedInbox(userId), lowerBound, now, 0, pageSize);
        sources.add(toItems(inboxTuples, -1L, FeedItem.SRC_INBOX));

        // ---------- 源 2：关注的大 V 分片 ----------
        List<Long> bigVs = followService.myBigVFollowees(userId);
        for (Long v : bigVs) {
            List<FeedItem> pulled = splitter.range(v, lowerBound, now, pageSize);
            if (!pulled.isEmpty()) {
                sources.add(pulled);
            }
        }

        // ---------- 归并 + 游标过滤 ----------
        List<FeedItem> merged = kWayMerge(sources, pageSize, hasCursor, cursorScore, cursorArticleId);

        boolean hasMore = merged.size() >= pageSize;
        String nextCursor = null;
        if (!merged.isEmpty()) {
            FeedItem last = merged.get(merged.size() - 1);
            nextCursor = ((long) last.getScore()) + ":" + last.getArticleId();
        }
        return new FeedPage(merged, nextCursor, hasMore);
    }

    /**
     * k 路归并：所有源内部已按 score 降序，用最小堆（按 score 降序比较）做归并
     */
    private List<FeedItem> kWayMerge(List<List<FeedItem>> sources,
                                     int pageSize,
                                     boolean hasCursor,
                                     double cursorScore,
                                     long cursorArticleId) {
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

        List<FeedItem> out = new ArrayList<>(pageSize);
        Set<Long> seen = new HashSet<>(pageSize * 4);
        while (!heap.isEmpty() && out.size() < pageSize) {
            Node node = heap.poll();
            idx[node.src]++;

            // 游标过滤：严格取"排在上一条之后"的内容
            if (hasCursor) {
                double s = node.item.getScore();
                if (s > cursorScore || (s == cursorScore && node.item.getArticleId() >= cursorArticleId)) {
                    fillNext(sources, idx, node.src, heap);
                    continue;
                }
            }
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
