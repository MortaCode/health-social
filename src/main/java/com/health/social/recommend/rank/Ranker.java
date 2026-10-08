package com.health.social.recommend.rank;

import com.health.social.recommend.RecommendProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 排序器：把 {@link RankFeatures} 映射成一个可比较的分数。
 *
 * <h3>设计取向：可解释优先</h3>
 * <p>这里是<b>显式加权线性模型</b>，不是深度模型。原因很实际：
 * <ul>
 *   <li>推荐结果要能回答"为什么推给我" —— 线性权重天然可归因，
 *       每个特征的贡献可以直接算出来（见 {@link #explain}）；</li>
 *   <li>冷启动阶段没有足够样本训模型，手调权重反而更稳；</li>
 *   <li>等曝光/反馈流水（{@code t_rec_exposure} / {@code t_rec_feedback}）积累到一定量级，
 *       把这里替换成 LR/GBDT 打分只需要改一个类 —— 特征工程已经在 {@link RankFeatures} 里做完了。</li>
 * </ul>
 *
 * <h3>七个特征</h3>
 * <table border="1">
 *   <tr><th>特征</th><th>含义</th><th>为什么这样算</th></tr>
 *   <tr><td>hot</td><td>全局热度</td>
 *       <td>{@code ln(1+imp+5*clk)} 后归一。用对数压制头部（10 万曝光和 1 万曝光的差距
 *           不该是线性的 10 倍），点击权重 ×5 体现"点击比曝光珍贵得多"。</td></tr>
 *   <tr><td>quality</td><td>内容质量</td>
 *       <td>点赞率的 <b>Wilson 下界</b>。直接算 {@code likes/impressions} 会让
 *           "1 次曝光 1 个赞" 的偶然样本（100% 点赞率）压过 "1 万曝光 3000 赞" 的优质内容；
 *           Wilson 下界对样本量做置信惩罚，样本越少越保守。</td></tr>
 *   <tr><td>fresh</td><td>新鲜度</td>
 *       <td>按半衰期指数衰减。资讯/健康科普类内容时效性敏感，24 小时半衰期意味着
 *           三天前的内容新鲜度只剩 1/8，需要靠质量分把总分拉回来。</td></tr>
 *   <tr><td>interest</td><td>兴趣匹配</td>
 *       <td>文章标签与用户兴趣画像的加权交并比，0~1。个性化最主要的来源。</td></tr>
 *   <tr><td>follow</td><td>关注加成</td>
 *       <td>0/1。权重刻意不高（0.06）：推荐流不是关注流，如果关注权重过大，
 *           推荐就退化成推流模块的时间线，失去"探索"价值。</td></tr>
 *   <tr><td>author</td><td>作者权威度</td>
 *       <td>明星医生 1.0 / 认证医生 0.6 / 普通 0.25。医疗内容对可信度极敏感，
 *           权威作者应当获得稳定加成。</td></tr>
 *   <tr><td>channel</td><td>召回置信度</td>
 *       <td>多路召回的共识程度。被 3 路同时命中的内容，比只被 1 路命中的更可信。</td></tr>
 * </table>
 *
 * <h3>负反馈</h3>
 * <p>不感兴趣<b>不是减分而是乘性惩罚</b> {@code 1/(1+dislikes)}：一次不感兴趣就砍半，
 * 两次砍到 1/3。乘性惩罚的语义是"这条内容对我无效"，比线性的减分更符合直觉，
 * 也保证无论其它特征多高，被明确讨厌的内容都排不到前面。
 */
@Component
public class Ranker {

    /** Wilson 置信区间的 z 值，1.96 对应 95% 置信度 */
    private static final double Z = 1.96D;

    private final RecommendProperties props;

    public Ranker(RecommendProperties props) {
        this.props = props;
    }

    /**
     * 计算最终分。
     *
     * @param f         特征
     * @param nowMs     当前时间（毫秒）
     * @param coldStart 是否冷启动用户（切换权重档）
     */
    public double score(RankFeatures f, long nowMs, boolean coldStart) {
        RecommendProperties.Weights w = coldStart ? props.getColdStartWeights() : props.getWeights();

        double base =
                w.getHot() * hotNorm(f.getImpressions(), f.getClicks())
                        + w.getQuality() * wilson(f.getLikeCount(), f.getImpressions() + f.getLikeCount())
                        + w.getFresh() * freshness(f.getPublishTimeMs(), nowMs)
                        + w.getInterest() * clamp01(f.getInterestMatch())
                        + w.getFollow() * (f.isFollowed() ? 1D : 0D)
                        + w.getAuthor() * authorWeight(f.getAuthorLevel())
                        + w.getChannel() * clamp01(f.getChannelScore());

        return base * dislikePenalty(f.getDislikes());
    }

    /**
     * 输出各归一化特征值与最终分（{@code GET /recommend/debug} 使用）。
     */
    public Map<String, Double> explain(RankFeatures f, long nowMs, boolean coldStart) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("hot", round(hotNorm(f.getImpressions(), f.getClicks())));
        m.put("quality", round(wilson(f.getLikeCount(), f.getImpressions() + f.getLikeCount())));
        m.put("fresh", round(freshness(f.getPublishTimeMs(), nowMs)));
        m.put("interest", round(clamp01(f.getInterestMatch())));
        m.put("follow", f.isFollowed() ? 1D : 0D);
        m.put("author", authorWeight(f.getAuthorLevel()));
        m.put("channel", round(clamp01(f.getChannelScore())));
        m.put("dislikePenalty", round(dislikePenalty(f.getDislikes())));
        m.put("weights", coldStart ? 1D : 0D);
        m.put("finalScore", round(score(f, nowMs, coldStart)));
        return m;
    }

    /* ================================================================= */
    /*                            特征函数                                */
    /* ================================================================= */

    /**
     * 全局热度归一：{@code h = ln(1 + imp + 5*clk)}，再 {@code h/(1+h)} 压到 [0,1)。
     *
     * <p>对数项让"从 0 到 100 曝光"的收益远大于"从 10 万到 10 万零 100"，
     * 避免头部内容靠体量永久霸榜。
     */
    static double hotNorm(long impressions, long clicks) {
        double h = Math.log1p(Math.max(0L, impressions) + 5D * Math.max(0L, clicks));
        return h / (1D + h);
    }

    /**
     * Wilson 下界（95% 置信）：把"点赞数 / 曝光数"当作二项分布的成功率，
     * 返回置信区间下界，从而对小样本自动降权。
     *
     * <pre>
     *   (p̂ + z²/2n − z·√((p̂(1−p̂) + z²/4n) / n)) / (1 + z²/n)
     * </pre>
     */
    static double wilson(long up, long n) {
        if (n <= 0L) {
            return 0D;
        }
        double nn = n;
        double phat = Math.min(1D, up / nn);
        double denom = 1D + Z * Z / nn;
        double center = phat + Z * Z / (2D * nn);
        double margin = Z * Math.sqrt((phat * (1D - phat) + Z * Z / (4D * nn)) / nn);
        return Math.max(0D, (center - margin) / denom);
    }

    /**
     * 时间衰减：{@code 0.5 ^ (ageHours / halfLifeHours)}。
     *
     * <p><b>注意这里是"半衰期"，不是 e 折损时间。</b>
     * 直觉上容易写成 {@code exp(-age/halfLife)}，但那样在半衰期时刻得到的是
     * {@code 1/e ≈ 0.368} 而不是 0.5 —— 衰减比配置预期的快得多，
     * 会让内容过早沉底（这个坑由 {@code RankerTest#freshnessHalfLife} 守住）。
     *
     * <p>用指数衰减而不是"线性减分"：线性衰减会让一批同时发布的内容在某个时间点
     * 集体掉出推荐池（阶跃），指数衰减是平滑的，排序稳定性好得多。
     */
    double freshness(long publishTimeMs, long nowMs) {
        double halfLifeMs = Math.max(1D, props.getFreshnessHalfLifeHours()) * 3_600_000D;
        double age = Math.max(0D, nowMs - publishTimeMs);
        // 0.5^(age/halfLife) 等价于 exp(-ln2 * age / halfLife)
        return Math.pow(0.5D, age / halfLifeMs);
    }

    /** 作者权威度 */
    static double authorWeight(int level) {
        if (level >= 2) {
            return 1.0D;
        }
        if (level == 1) {
            return 0.6D;
        }
        return 0.25D;
    }

    /** 负反馈乘性惩罚：0 次 → 1.0，1 次 → 0.5，2 次 → 0.33 */
    static double dislikePenalty(long dislikes) {
        return 1D / (1D + Math.max(0L, dislikes));
    }

    static double clamp01(double v) {
        if (v < 0D) {
            return 0D;
        }
        return Math.min(v, 1D);
    }

    static double round(double v) {
        return Math.round(v * 10000D) / 10000D;
    }
}
