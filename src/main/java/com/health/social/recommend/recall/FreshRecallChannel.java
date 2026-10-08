package com.health.social.recommend.recall;

import com.health.social.recommend.RecommendProperties;
import com.health.social.recommend.RecRedisKeys;
import com.health.social.recommend.model.Candidate;
import com.health.social.recommend.model.RecallContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 新鲜度召回（新内容扶持通道）。
 *
 * <h3>为什么必须有这一路</h3>
 * <p>只靠"热点 + 关注 + 兴趣 + 相似"四路召回，会形成典型的<b>马太效应</b>：
 * 老内容因为曝光多 → 点击多 → 热度高 → 曝光更多；新内容没有历史数据，
 * 永远进不了热点榜，也匹配不上任何人的兴趣画像，于是<b>永远没有曝光机会</b>。
 * 结果是内容生态快速板结，创作者流失。
 *
 * <p>所以专门开一路"无条件召回最新内容"，给它一个稳定的曝光配额，
 * 让它有机会积累点击/点赞数据，进而进入其它通道的正循环。
 * 这是所有内容平台推荐系统的标配（YouTube/抖音都叫 "fresh"/"cold start" 通道）。
 *
 * <h3>数据源</h3>
 * <p>{@code rec:cand:fresh}：全站新文章候选池，由
 * {@code CandidatePoolMaintainer} 通过"文章发布事件 + 定时水位线兜底扫描"维护。
 * 这个池子同时也是整个推荐模块的<b>最终兜底</b> —— 其它四路全空（比如 Redis 热榜被清、
 * 用户零行为零关注）时，至少还有新内容可以推。
 */
@Slf4j
@Component
public class FreshRecallChannel implements RecallChannel {

    private final StringRedisTemplate redis;
    private final RecommendProperties props;

    public FreshRecallChannel(StringRedisTemplate redis, RecommendProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public String name() {
        return "fresh";
    }

    @Override
    public void recall(RecallContext ctx, int limit, Map<Long, Candidate> sink) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                    .reverseRangeWithScores(RecRedisKeys.CAND_FRESH, 0, limit - 1L);
            if (tuples == null || tuples.isEmpty()) {
                return;
            }
            double halfLifeMs = Math.max(1D, props.getFreshnessHalfLifeHours()) * 3_600_000D;
            int rank = 0;
            for (ZSetOperations.TypedTuple<String> t : tuples) {
                if (t.getValue() == null) {
                    continue;
                }
                try {
                    long articleId = Long.parseLong(t.getValue());
                    long publishMs = t.getScore() == null ? ctx.getNowMs() : (long) (double) t.getScore();
                    // 指数衰减：越新越高；同时保留排名信息（池子本身就是按时间排序的）
                    double age = Math.max(0D, ctx.getNowMs() - publishMs);
                    double recency = Math.exp(-age / halfLifeMs);
                    put(sink, articleId, 0.5D * recency + 0.5D * decayByRank(rank++, limit));
                } catch (NumberFormatException ignore) {
                    // 脏数据跳过
                }
            }
        } catch (Exception e) {
            log.warn("[Recall][fresh] 新鲜度召回失败，降级为空", e);
        }
    }

    private static double decayByRank(int rank, int total) {
        return 1D - 0.5D * (rank / (double) Math.max(1, total));
    }
}
