package com.health.social.recommend.recall;

import com.health.social.common.RedisKeys;
import com.health.social.feed.BigKeySplitter;
import com.health.social.feed.FeedItem;
import com.health.social.feed.FollowService;
import com.health.social.recommend.RecommendProperties;
import com.health.social.recommend.model.Candidate;
import com.health.social.recommend.model.RecallContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关注召回（<b>只读</b>复用推流模块的 Feed 存储）。
 *
 * <h3>关键点：一行推流代码都没改</h3>
 * <p>推流模块已经把"关注关系 → 内容"这件事的存储做好了：
 * <ul>
 *   <li>普通作者：{@code feed:outbox:{authorId}}（ZSet，member=articleId，score=发布时间）；</li>
 *   <li>大 V：{@code feed:outbox:shard:{authorId}:{bucket}} 时间分桶，读时用
 *       {@link BigKeySplitter#range} 按时间区间拉取。</li>
 * </ul>
 * 推荐模块把它们当作<b>只读数据源</b>直接消费：注入 {@link FollowService} 判断大 V、
 * 注入 {@link BigKeySplitter} 读分片。全程没有任何写入，也没有为了推荐去改造 FeedService。
 *
 * <h3>与推流模块的语义差异（重要）</h3>
 * <p>推流模块读的是"我关注的人的全部动态，按时间倒序"；推荐模块要的是
 * "我关注的人里，<b>值得推</b>的动态"，所以这里做了两件事：
 * <ol>
 *   <li><b>作者轮转</b>：每个作者只取前 {@code followRecallPerAuthor} 条，而不是让一个大 V
 *       把 200 条候选全部占满 —— 否则关注召回就退化成"只推最活跃的那个大 V"；</li>
 *   <li><b>打分随排名衰减</b>：同一个作者内部，越新的帖子分越高。</li>
 * </ol>
 * 排序阶段还会再叠加热度与兴趣，最终"关注"只是众多信号之一，而不是决定性因素。
 */
@Slf4j
@Component
public class FollowRecallChannel implements RecallChannel {

    private final StringRedisTemplate redis;
    private final FollowService followService;
    private final BigKeySplitter bigKeySplitter;
    private final RecommendProperties props;

    public FollowRecallChannel(StringRedisTemplate redis,
                               FollowService followService,
                               BigKeySplitter bigKeySplitter,
                               RecommendProperties props) {
        this.redis = redis;
        this.followService = followService;
        this.bigKeySplitter = bigKeySplitter;
        this.props = props;
    }

    @Override
    public String name() {
        return "follow";
    }

    @Override
    public void recall(RecallContext ctx, int limit, Map<Long, Candidate> sink) {
        List<Long> followees = ctx.getFollowees();
        if (followees == null || followees.isEmpty()) {
            return;
        }
        int maxFollowees = Math.min(followees.size(), props.getFollowRecallMaxFollowees());
        int perAuthor = Math.max(1, props.getFollowRecallPerAuthor());
        // 只看最近 7 天的动态，与推流模块 pull-days 保持一致
        long fromMs = ctx.getNowMs() - 7 * 86_400_000L;
        int budget = limit;

        for (int i = 0; i < maxFollowees && budget > 0; i++) {
            long authorId = followees.get(i);
            try {
                int took = pullAuthor(authorId, ctx.getNowMs(), fromMs, perAuthor, sink);
                budget -= took;
            } catch (Exception e) {
                log.warn("[Recall][follow] 拉取作者动态失败, authorId={}", authorId, e);
            }
        }
    }

    /**
     * 拉取单个作者的动态，返回实际写入候选池的条数。
     *
     * <p>大 V 走分片（{@link BigKeySplitter}），普通作者走未分桶发件箱。
     * 这个分支判断与推流模块 {@code FeedMergeService.merge} 完全一致 ——
     * 因为读的必须是同一份存储，否则会出现"Feed 里能看到、推荐里看不到"的诡异现象。
     */
    private int pullAuthor(long authorId, long nowMs, long fromMs, int perAuthor, Map<Long, Candidate> sink) {
        if (followService.isBigV(authorId)) {
            List<FeedItem> items = bigKeySplitter.range(authorId, fromMs, nowMs, perAuthor);
            int n = 0;
            for (int i = 0; i < items.size(); i++) {
                put(sink, items.get(i).getArticleId(), decayByRank(i, perAuthor));
                n++;
            }
            return n;
        }

        Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                .reverseRangeWithScores(RedisKeys.feedOutbox(authorId), 0, perAuthor - 1L);
        if (tuples == null || tuples.isEmpty()) {
            return 0;
        }
        int n = 0;
        int rank = 0;
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            if (t.getValue() == null) {
                continue;
            }
            // 只取时间窗内的内容（发件箱是长期保留的，需要按时间裁剪）
            if (t.getScore() != null && t.getScore() < fromMs) {
                continue;
            }
            try {
                put(sink, Long.parseLong(t.getValue()), decayByRank(rank++, perAuthor));
                n++;
            } catch (NumberFormatException ignore) {
                // 脏数据直接跳过
            }
        }
        return n;
    }

    /** 同一作者内部按排名衰减：第 1 条 1.0，最后一条 0.5 */
    private static double decayByRank(int rank, int total) {
        return 1D - 0.5D * (rank / (double) Math.max(1, total));
    }
}
