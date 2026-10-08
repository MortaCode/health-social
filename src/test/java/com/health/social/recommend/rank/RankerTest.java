package com.health.social.recommend.rank;

import com.health.social.recommend.RecommendProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Ranker} 的特征函数单测。
 *
 * <p>排序逻辑被刻意设计成纯函数（只吃 {@link RankFeatures}），就是为了能被这样直接测。
 * 这里覆盖的都是"调参时最容易改错"的地方：Wilson 的样本量惩罚、时间衰减的半衰期语义、
 * 负反馈惩罚、冷启动权重档。
 */
class RankerTest {

    private final Ranker ranker = new Ranker(new RecommendProperties());

    @Test
    @DisplayName("Wilson 下界：小样本必须被惩罚（1赞/1曝光 不能压过 300赞/1000曝光）")
    void wilsonPenalizesSmallSamples() {
        double tiny = Ranker.wilson(1, 1);
        double healthy = Ranker.wilson(300, 1000);
        assertTrue(tiny < healthy,
                "小样本的 Wilson 下界应低于大样本，实际 tiny=" + tiny + ", healthy=" + healthy);
        // 1/1 的点赞率虽然是 100%，但 95% 置信下界只有 0.207 —— 这正是要"惩罚小样本"的原因：
        // 如果直接用 likes/impressions，这条内容会拿到满分 1.0，压过所有真实优质内容。
        assertTrue(tiny > 0.20 && tiny < 0.22, "1/1 的 Wilson 下界应约等于 0.207，实际=" + tiny);
        // 300/1000 的点赞率是 30%，下界应接近 27%
        assertTrue(healthy > 0.25 && healthy < 0.30, "300/1000 的 Wilson 下界应接近 0.27，实际=" + healthy);
    }

    @Test
    @DisplayName("Wilson 下界：无曝光时返回 0，且永远不超过 1")
    void wilsonBoundaries() {
        assertEquals(0D, Ranker.wilson(0, 0));
        assertEquals(0D, Ranker.wilson(5, 0));
        assertTrue(Ranker.wilson(1000, 1000) <= 1D);
    }

    @Test
    @DisplayName("时间衰减：age=0 → 1.0，age=半衰期 → 0.5")
    void freshnessHalfLife() {
        long now = 1_700_000_000_000L;
        assertEquals(1.0D, ranker.freshness(now, now), 1e-9);

        long halfLifeMs = (long) (24 * 3_600_000L);
        assertEquals(0.5D, ranker.freshness(now - halfLifeMs, now), 1e-6);

        // 两天前 → 1/4
        assertEquals(0.25D, ranker.freshness(now - 2 * halfLifeMs, now), 1e-6);
    }

    @Test
    @DisplayName("热度归一：单调递增且被压到 [0,1)")
    void hotNormIsBoundedAndMonotonic() {
        double a = Ranker.hotNorm(0, 0);
        double b = Ranker.hotNorm(100, 10);
        double c = Ranker.hotNorm(100_000, 10_000);
        assertEquals(0D, a);
        assertTrue(b > a && c > b, "热度应随曝光/点击单调递增");
        assertTrue(c < 1D, "热度必须被压在上界内，否则头部内容会永久霸榜");
    }

    @Test
    @DisplayName("负反馈是乘性惩罚：1 次砍半，2 次降到 1/3")
    void dislikePenaltyIsMultiplicative() {
        assertEquals(1.0D, Ranker.dislikePenalty(0));
        assertEquals(0.5D, Ranker.dislikePenalty(1));
        assertEquals(1D / 3D, Ranker.dislikePenalty(2), 1e-9);
    }

    @Test
    @DisplayName("作者权威度：明星医生 > 认证医生 > 普通用户")
    void authorWeightOrdering() {
        assertTrue(Ranker.authorWeight(2) > Ranker.authorWeight(1));
        assertTrue(Ranker.authorWeight(1) > Ranker.authorWeight(0));
    }

    @Test
    @DisplayName("冷启动档必须把兴趣权重归零，否则该维度恒为 0 等于白占权重")
    void coldStartWeightsZeroOutInterest() {
        RecommendProperties.Weights cold = new RecommendProperties().getColdStartWeights();
        assertEquals(0D, cold.getInterest());
        assertEquals(1.0D, cold.sum(), 1e-9);
        // 让出来的权重应该加在"不依赖个人行为"的特征上
        RecommendProperties.Weights normal = new RecommendProperties().getWeights();
        assertTrue(cold.getHot() > normal.getHot());
        assertTrue(cold.getFresh() > normal.getFresh());
    }

    @Test
    @DisplayName("冷启动与老用户打分差异：热点内容 vs 兴趣匹配内容的相对排名应当反转")
    void coldStartVsWarmUserScoring() {
        long now = 1_700_000_000_000L;
        RankFeatures hotItem = features(now, 50_000, 4_000, 3_000, 1, true, 0.2D);
        RankFeatures matchedItem = features(now - 40L * 3_600_000L, 100, 5, 20, 1, false, 1.0D);

        double coldHot = ranker.score(hotItem, now, true);
        double coldMatched = ranker.score(matchedItem, now, true);
        assertTrue(coldHot > coldMatched, "冷启动用户应更偏向全站热点");

        double warmHot = ranker.score(hotItem, now, false);
        double warmMatched = ranker.score(matchedItem, now, false);

        // 核心断言：切换到老用户权重档后，兴趣匹配内容获得的提升，
        // 必须大于热点内容获得的提升 —— 这证明"兴趣"这一维真的在起作用，
        // 而不是被写进配置却对排序毫无影响。
        double matchedGain = warmMatched - coldMatched;
        double hotGain = warmHot - coldHot;
        assertTrue(matchedGain > hotGain,
                "兴趣维度应更有利于兴趣匹配内容, matchedGain=" + matchedGain + ", hotGain=" + hotGain);
        assertTrue(warmHot > 0 && warmMatched > 0);
    }

    @Test
    @DisplayName("不感兴趣的内容即使其它特征拉满也排不到前面")
    void dislikedContentSinks() {
        long now = 1_700_000_000_000L;
        RankFeatures f = features(now, 50_000, 4_000, 3_000, 2, true, 1.0D);
        double before = ranker.score(f, now, false);
        f.setDislikes(3);
        double after = ranker.score(f, now, false);
        assertEquals(before / 4D, after, 1e-9);
    }

    private static RankFeatures features(long publishMs, long imp, long clk, long likes,
                                         int level, boolean followed, double interest) {
        RankFeatures f = new RankFeatures();
        f.setPublishTimeMs(publishMs);
        f.setImpressions(imp);
        f.setClicks(clk);
        f.setLikeCount(likes);
        f.setAuthorLevel(level);
        f.setFollowed(followed);
        f.setInterestMatch(interest);
        f.setChannelScore(0.8D);
        return f;
    }
}
