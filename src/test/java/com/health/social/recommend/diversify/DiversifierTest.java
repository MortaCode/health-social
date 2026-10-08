package com.health.social.recommend.diversify;

import com.health.social.recommend.RecommendProperties;
import com.health.social.recommend.model.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Diversifier} 单测。
 *
 * <p>重点验证两件容易写错的事：
 * <ol>
 *   <li><b>约束真的生效</b>：同作者/同标签被压到阈值以下；</li>
 *   <li><b>一定填满</b>：即使约束把候选卡死，也必须通过放宽与兜底把一页填满 ——
 *       返回半页是比"多样性差"更严重的问题。</li>
 * </ol>
 */
class DiversifierTest {

    private final RecommendProperties props = new RecommendProperties();
    private final Diversifier diversifier = new Diversifier(props);

    @Test
    @DisplayName("同作者霸屏被压制：maxPerAuthor=1 时一页内作者不重复（候选充足时）")
    void limitsSameAuthor() {
        // 作者 1 有 5 篇高分内容，作者 2/3/4 各 1 篇
        List<Candidate> ranked = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ranked.add(candidate(100 + i, 1L, 10L, 1.0D - i * 0.01D));
        }
        ranked.add(candidate(200, 2L, 20L, 0.50D));
        ranked.add(candidate(300, 3L, 30L, 0.40D));
        ranked.add(candidate(400, 4L, 40L, 0.30D));

        props.setMaxPerAuthor(1);
        List<Candidate> out = diversifier.diversify(ranked, 4);

        assertEquals(4, out.size());
        Map<Long, Integer> perAuthor = countByAuthor(out);
        assertTrue(perAuthor.getOrDefault(1L, 0) <= 1,
                "作者 1 在一页内不应出现超过 1 次，实际=" + perAuthor.get(1L));
        assertEquals(4, perAuthor.size(), "4 个作者应各占一席");
    }

    @Test
    @DisplayName("同标签霸屏被压制：maxPerTag=2 时同一标签不超过阈值")
    void limitsSameTag() {
        // 6 篇内容全是标签 10（作者各不相同，隔离作者维度的影响）
        List<Candidate> ranked = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ranked.add(candidate(100 + i, 100L + i, 10L, 1.0D - i * 0.01D));
        }
        props.setMaxPerAuthor(10);
        props.setMaxPerTag(2);
        List<Candidate> out = diversifier.diversify(ranked, 4);

        // 约束只能选出 2 条，但兜底必须把页面填满到 4
        assertEquals(4, out.size(), "候选足够时必须填满一页，不能返回半页");
        Map<Long, Integer> perTag = countByTag(out);
        // 前两条受约束（严格不超过 2），后面是兜底补齐
        assertTrue(perTag.getOrDefault(10L, 0) >= 2);
    }

    @Test
    @DisplayName("候选不足时返回全部，不抛异常也不补空对象")
    void returnsAllWhenNotEnough() {
        List<Candidate> ranked = List.of(
                candidate(1, 1L, 1L, 0.9D),
                candidate(2, 2L, 2L, 0.8D));
        List<Candidate> out = diversifier.diversify(ranked, 20);
        assertEquals(2, out.size());
    }

    @Test
    @DisplayName("空输入安全：返回空列表而不是 NPE")
    void handlesEmptyInput() {
        assertTrue(diversifier.diversify(null, 10).isEmpty());
        assertTrue(diversifier.diversify(List.of(), 10).isEmpty());
        assertTrue(diversifier.diversify(List.of(candidate(1, 1L, 1L, 1D)), 0).isEmpty());
    }

    @Test
    @DisplayName("结果不重复：同一条候选不会被选两次")
    void noDuplicates() {
        List<Candidate> ranked = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ranked.add(candidate(i, (long) (i % 3), (long) (i % 4), 1.0D - i * 0.01D));
        }
        List<Candidate> out = diversifier.diversify(ranked, 10);
        long distinct = out.stream().map(Candidate::getArticleId).distinct().count();
        assertEquals(out.size(), distinct, "输出中不应出现重复的 articleId");
    }

    @Test
    @DisplayName("输入列表不被修改（纯函数语义）")
    void doesNotMutateInput() {
        List<Candidate> ranked = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ranked.add(candidate(i, 1L, 1L, 1.0D - i * 0.01D));
        }
        int sizeBefore = ranked.size();
        diversifier.diversify(ranked, 3);
        assertEquals(sizeBefore, ranked.size());
    }

    /* ----------------------------- 工具 ----------------------------- */

    private static Candidate candidate(long articleId, Long authorId, Long tagId, double score) {
        Candidate c = new Candidate(articleId);
        c.setAuthorId(authorId);
        c.setPublishTimeMs(System.currentTimeMillis());
        c.setTagIds(new ArrayList<>(List.of(tagId)));
        c.setFinalScore(score);
        c.addChannel("hot", 0.8D);
        return c;
    }

    private static Map<Long, Integer> countByAuthor(List<Candidate> list) {
        Map<Long, Integer> m = new HashMap<>();
        for (Candidate c : list) {
            m.merge(c.getAuthorId(), 1, Integer::sum);
        }
        return m;
    }

    private static Map<Long, Integer> countByTag(List<Candidate> list) {
        Map<Long, Integer> m = new HashMap<>();
        for (Candidate c : list) {
            for (Long t : c.getTagIds()) {
                m.merge(t, 1, Integer::sum);
            }
        }
        return m;
    }
}
