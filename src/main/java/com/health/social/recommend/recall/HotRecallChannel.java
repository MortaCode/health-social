package com.health.social.recommend.recall;

import com.health.social.cache.HeavyKeeperDetector;
import com.health.social.recommend.model.Candidate;
import com.health.social.recommend.model.RecallContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 热点召回（复用已有 HeavyKeeper 热榜）。
 *
 * <h3>为什么直接复用，而不是自己再统计一套</h3>
 * <p>推流模块的 {@code HeavyKeeperDetector} 已经在 {@code @HotDetect} 切面上持续统计文章访问频次，
 * 榜单落在 {@code hk:top:{hot}}。推荐模块<b>只读</b>这个榜单即可获得"全站正在热"的信号，
 * 完全不需要重复埋点、重复统计 —— 这也是"新开模块、不改造推流模块"的直接收益。
 *
 * <h3>打分</h3>
 * <p>HeavyKeeper 给出的是估计访问频次，量纲是"次数"，跨天/跨活动不可比。
 * 因此在本通道内做<b>批内归一化</b>：以本批最高频次为 1.0，映射到 {@code [0.4, 1.0]}。
 * 保留下限 0.4 是因为"能进 TopN 榜"本身就已经是强信号，不该被后续排序完全忽略。
 */
@Slf4j
@Component
public class HotRecallChannel implements RecallChannel {

    /** 与推流模块共用的热点类型标识（@HotDetect(type="article")） */
    private static final String HOT_TYPE = "article";

    private final HeavyKeeperDetector detector;

    public HotRecallChannel(HeavyKeeperDetector detector) {
        this.detector = detector;
    }

    @Override
    public String name() {
        return "hot";
    }

    @Override
    public void recall(RecallContext ctx, int limit, Map<Long, Candidate> sink) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = detector.topNWithScore(HOT_TYPE, limit);
            if (tuples == null || tuples.isEmpty()) {
                return;
            }
            double max = 0D;
            for (ZSetOperations.TypedTuple<String> t : tuples) {
                max = Math.max(max, t.getScore() == null ? 0D : t.getScore());
            }
            for (ZSetOperations.TypedTuple<String> t : tuples) {
                if (t.getValue() == null) {
                    continue;
                }
                long articleId;
                try {
                    articleId = Long.parseLong(t.getValue());
                } catch (NumberFormatException e) {
                    continue;
                }
                double raw = t.getScore() == null ? 0D : t.getScore();
                double normalized = max <= 0D ? 1D : raw / max;
                put(sink, articleId, 0.4D + 0.6D * normalized);
            }
        } catch (Exception e) {
            // 召回通道必须自愈：热榜不可用只是少一路召回，绝不能影响整页推荐
            log.warn("[Recall][hot] 热点召回失败，降级为空", e);
        }
    }
}
