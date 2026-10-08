package com.health.social.recommend.diversify;

import com.health.social.recommend.RecommendProperties;
import com.health.social.recommend.model.Candidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 打散器（Diversity / Re-rank）。
 *
 * <h3>为什么"排序分最高"不等于"体验最好"</h3>
 * <p>纯按分数取 TopN 会出现三种典型劣化：
 * <ol>
 *   <li><b>同作者霸屏</b>：一个明星医生发了 5 篇高血压科普，质量分都很高，
 *       于是推荐页前 5 条全是他的 —— 用户会觉得"这 App 是不是只有一个人"；</li>
 *   <li><b>同质化</b>：全是"高血压"相关内容，虽然每条都匹配兴趣，
 *       但信息增益极低（看过第 1 条，后 4 条没有新信息）；</li>
 *   <li><b>相邻重复</b>：连续两条同作者/同标签，观感上是"重复内容"。</li>
 * </ol>
 *
 * <h3>三阶段贪心（保证一定填满）</h3>
 * <pre>
 *   Phase A：硬约束  同作者 ≤ maxPerAuthor 且 同标签 ≤ maxPerTag 且 不与上一条同作者
 *   Phase B：放宽相邻  允许相邻同作者，但仍受数量约束
 *   Phase C：兜底补齐  按分数顺序把剩余候选填满，保证"候选够就一定给满一页"
 * </pre>
 * <p>阶段化而不是"一次性加约束"的原因：如果约束太紧导致选不满，
 * 直接返回半页数据是最糟的结果（用户要滑到底才能触发下一页）。
 * 宁可放宽多样性，也不能让页面变短 —— 这也是工业界的通行取舍。
 *
 * <p>注意：本类<b>不修改</b>输入列表，输出是新列表，便于单元测试与对比。
 */
@Component
public class Diversifier {

    private final RecommendProperties props;

    public Diversifier(RecommendProperties props) {
        this.props = props;
    }

    /**
     * 打散。
     *
     * @param ranked 已按 {@code finalScore} 降序排列的候选
     * @param size   目标条数
     * @return 打散后的列表（长度 = min(size, ranked.size())）
     */
    public List<Candidate> diversify(List<Candidate> ranked, int size) {
        if (ranked == null || ranked.isEmpty() || size <= 0) {
            return new ArrayList<>();
        }
        int maxPerAuthor = Math.max(1, props.getMaxPerAuthor());
        int maxPerTag = Math.max(1, props.getMaxPerTag());

        List<Candidate> picked = new ArrayList<>(size);
        Set<Long> used = new HashSet<>(ranked.size() * 2);
        Map<Long, Integer> authorCount = new HashMap<>();
        Map<Long, Integer> tagCount = new HashMap<>();

        // ---------- Phase A：硬约束（含"不与上一条同作者"） ----------
        long lastAuthor = Long.MIN_VALUE;
        for (Candidate c : ranked) {
            if (picked.size() >= size) {
                break;
            }
            long author = c.getAuthorId() == null ? 0L : c.getAuthorId();
            if (author == lastAuthor) {
                continue;
            }
            if (violates(c, authorCount, tagCount, maxPerAuthor, maxPerTag)) {
                continue;
            }
            accept(c, author, picked, used, authorCount, tagCount);
            lastAuthor = author;
        }

        // ---------- Phase B：放宽"相邻同作者"，数量约束仍然生效 ----------
        if (picked.size() < size) {
            for (Candidate c : ranked) {
                if (picked.size() >= size) {
                    break;
                }
                if (used.contains(c.getArticleId())) {
                    continue;
                }
                long author = c.getAuthorId() == null ? 0L : c.getAuthorId();
                if (violates(c, authorCount, tagCount, maxPerAuthor, maxPerTag)) {
                    continue;
                }
                accept(c, author, picked, used, authorCount, tagCount);
            }
        }

        // ---------- Phase C：兜底补齐（宁可有同质内容，也不给半页） ----------
        if (picked.size() < size) {
            for (Candidate c : ranked) {
                if (picked.size() >= size) {
                    break;
                }
                if (used.add(c.getArticleId())) {
                    picked.add(c);
                }
            }
        }
        return picked;
    }

    /** 判断是否违反作者/标签的数量约束 */
    private boolean violates(Candidate c,
                             Map<Long, Integer> authorCount,
                             Map<Long, Integer> tagCount,
                             int maxPerAuthor,
                             int maxPerTag) {
        long author = c.getAuthorId() == null ? 0L : c.getAuthorId();
        if (authorCount.getOrDefault(author, 0) >= maxPerAuthor) {
            return true;
        }
        // 只看主标签（前 maxPerTag 个之外的长尾标签不参与约束，否则约束过紧）
        List<Long> tags = c.getTagIds();
        if (tags != null) {
            int inspected = 0;
            for (Long tagId : new LinkedHashSet<>(tags)) {
                if (inspected++ >= maxPerTag) {
                    break;
                }
                if (tagCount.getOrDefault(tagId, 0) >= maxPerTag) {
                    return true;
                }
            }
        }
        return false;
    }

    private void accept(Candidate c,
                        long author,
                        List<Candidate> picked,
                        Set<Long> used,
                        Map<Long, Integer> authorCount,
                        Map<Long, Integer> tagCount) {
        picked.add(c);
        used.add(c.getArticleId());
        authorCount.merge(author, 1, Integer::sum);
        if (c.getTagIds() != null) {
            for (Long tagId : new LinkedHashSet<>(c.getTagIds())) {
                tagCount.merge(tagId, 1, Integer::sum);
            }
        }
    }
}
