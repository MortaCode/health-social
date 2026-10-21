package com.health.social.feed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Feed 游标翻页的行为测试。
 *
 * <p>用内存里的假 ZSet 复刻 Redis 的真实语义：
 * <ul>
 *   <li>ZREVRANGEBYSCORE：按 score 降序，<b>相同 score 按 member 字符串逆字典序</b>；</li>
 *   <li>ZRANGEBYSCORE：闭区间。</li>
 * </ul>
 *
 * <p>测试数据全部使用 19 位定长的雪花 ID，因为字典序等于数值序，
 * 这也正是当前 {@code t_article.id} 的真实形态。
 */
class FeedMergeServicePagingTest {

    /** 时间基准：取"一分钟前"，保证测试数据落在 pullDays 时间窗内 */
    private static final long BASE_TS = System.currentTimeMillis() - 60_000L;
    /** 19 位雪花 ID 起点 */
    private static final long BASE_ID = 1_846_231_587_557_281_793L;

    private final List<Entry> store = new ArrayList<>();
    private FeedMergeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store.clear();

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zops = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zops);

        when(zops.reverseRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenAnswer(inv -> reverseRangeByScore(
                        inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)));
        when(zops.rangeByScore(anyString(), anyDouble(), anyDouble()))
                .thenAnswer(inv -> rangeByScore(inv.getArgument(1), inv.getArgument(2)));

        BigKeySplitter splitter = mock(BigKeySplitter.class);
        FollowService followService = mock(FollowService.class);
        when(followService.myBigVFollowees(anyLong())).thenReturn(List.of());

        service = new FeedMergeService(redis, splitter, followService);
        ReflectionTestUtils.setField(service, "pullDays", 7);
        ReflectionTestUtils.setField(service, "maxBacktrackDays", 90);
        ReflectionTestUtils.setField(service, "defaultPageSize", 20);
    }

    @Test
    @DisplayName("连续翻页：每页 20 条，不漏不重，最后一页正确收尾")
    void pageThroughWithoutGapOrDuplicate() {
        seedDistinct(45);

        List<Long> collected = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            FeedMergeService.FeedPage page = service.merge(1L, cursor, null);
            pages++;
            collected.addAll(page.items().stream().map(FeedItem::getArticleId).collect(Collectors.toList()));
            if (!page.hasMore()) {
                break;
            }
            cursor = page.nextCursor();
            assertNotNull(cursor, "hasMore=true 时必须返回游标");
            assertTrue(pages < 10, "翻页没有正常终止");
        }

        assertEquals(3, pages, "45 条 / 每页 20 条 = 3 页");
        assertEquals(45, collected.size(), "翻完必须拿到全部 45 条，不能少");
        assertEquals(45, collected.stream().distinct().count(), "不能出现重复条目");
    }

    @Test
    @DisplayName("第一页必须是最近的 20 条，且按时间倒序")
    void firstPageIsLatestTwenty() {
        seedDistinct(45);

        FeedMergeService.FeedPage page = service.merge(1L, null, null);

        assertEquals(20, page.items().size());
        assertTrue(page.hasMore());
        assertEquals(BASE_ID + 44, page.items().get(0).getArticleId(), "第一条应是最新的");
        assertEquals(BASE_ID + 25, page.items().get(19).getArticleId(), "第二十条应是第 20 新的");
    }

    @Test
    @DisplayName("翻页期间有新帖插入，第二页不能被新帖挤空")
    void newPostsBetweenPagesMustNotBreakPaging() {
        seedDistinct(45);
        FeedMergeService.FeedPage first = service.merge(1L, null, null);

        // 模拟两次请求之间又发了 5 条
        for (int i = 0; i < 5; i++) {
            store.add(new Entry(String.valueOf(BASE_ID + 100 + i), BASE_TS + 1_000L * (i + 1)));
        }

        FeedMergeService.FeedPage second = service.merge(1L, first.nextCursor(), null);

        assertEquals(20, second.items().size(), "第二页必须仍然是满 20 条");
        assertTrue(second.hasMore());
        assertTrue(second.items().get(0).getScore() <= first.items().get(19).getScore(),
                "第二页不能包含比游标更新的条目");
    }

    @Test
    @DisplayName("同一秒发布的多条（score 完全相同）在翻页边界上不能丢")
    void sameScoreGroupMustNotBeLostAtPageBoundary() {
        // 45 条里让第 24~28 条落在同一秒，正好横跨第一页（20 条）的边界
        long sameScore = BASE_TS - 1_000L * 19;
        for (int i = 0; i < 45; i++) {
            long score = (i >= 24 && i <= 28) ? sameScore : BASE_TS - 1_000L * (44 - i);
            store.add(new Entry(String.valueOf(BASE_ID + i), score));
        }

        List<Long> collected = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        while (guard++ < 10) {
            FeedMergeService.FeedPage page = service.merge(1L, cursor, null);
            collected.addAll(page.items().stream().map(FeedItem::getArticleId).collect(Collectors.toList()));
            if (!page.hasMore()) {
                break;
            }
            cursor = page.nextCursor();
        }

        assertEquals(45, collected.size(), "同分组的条目不能在翻页边界丢失");
        assertEquals(45, collected.stream().distinct().count(), "同分组的条目不能重复返回");
    }

    @Test
    @DisplayName("数据不足一页时 hasMore=false 且不返回游标")
    void shortPageHasNoCursor() {
        seedDistinct(8);

        FeedMergeService.FeedPage page = service.merge(1L, null, null);

        assertEquals(8, page.items().size());
        assertFalse(page.hasMore());
        assertEquals(null, page.nextCursor());
    }

    /* ----------------------------- 假 ZSet ----------------------------- */

    /** 按真实形态铺数据：id 越大越新（雪花 ID 的 id 与时间同向） */
    private void seedDistinct(int count) {
        for (int i = 0; i < count; i++) {
            long score = BASE_TS - 1_000L * (count - 1 - i);
            store.add(new Entry(String.valueOf(BASE_ID + i), score));
        }
    }

    /** 复刻 ZREVRANGEBYSCORE：score 降序，同分按 member 逆字典序 */
    private Set<ZSetOperations.TypedTuple<String>> reverseRangeByScore(
            double min, double max, long offset, long count) {
        List<Entry> sorted = store.stream()
                .filter(e -> e.score >= min && e.score <= max)
                .sorted((a, b) -> {
                    int c = Double.compare(b.score, a.score);
                    return c != 0 ? c : b.member.compareTo(a.member);
                })
                .collect(Collectors.toList());

        Set<ZSetOperations.TypedTuple<String>> out = new LinkedHashSet<>();
        for (int i = (int) offset; i < sorted.size() && out.size() < count; i++) {
            out.add(new Tuple(sorted.get(i).member, sorted.get(i).score));
        }
        return out;
    }

    /** 复刻 ZRANGEBYSCORE（闭区间） */
    private Set<String> rangeByScore(double min, double max) {
        return store.stream()
                .filter(e -> e.score >= min && e.score <= max)
                .map(e -> e.member)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private record Entry(String member, double score) {
    }

    private static final class Tuple implements ZSetOperations.TypedTuple<String> {

        private final String value;
        private final Double score;

        private Tuple(String value, double score) {
            this.value = value;
            this.score = score;
        }

        @Override
        public String getValue() {
            return value;
        }

        @Override
        public Double getScore() {
            return score;
        }

        @Override
        public int compareTo(ZSetOperations.TypedTuple<String> o) {
            return Double.compare(score, o.getScore() == null ? 0D : o.getScore());
        }
    }
}
