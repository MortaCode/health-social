package com.health.social.recommend.recall;

import com.health.social.recommend.model.Candidate;
import com.health.social.recommend.model.RecallContext;

import java.util.Map;

/**
 * 召回通道（多路召回的一路）。
 *
 * <h3>为什么要做成接口</h3>
 * <p>推荐系统的召回层是"可插拔"的：新增一路（比如向量召回、搜索行为召回）不应该动排序和编排代码。
 * 编排层 {@code RecommendPipeline} 只依赖这个接口，通过 Spring 注入拿到全部实现，
 * 因此<b>新增一个 {@code @Component} 实现类就等于上线一路召回</b>，零改动现有代码。
 *
 * <h3>实现约定（重要）</h3>
 * <ol>
 *   <li><b>只增不改</b>：向 {@code sink} 写入候选，不要覆盖已有候选的其它通道得分；</li>
 *   <li><b>必须容错</b>：任何异常都要在通道内部吞掉并降级为空结果。
 *       一路召回挂掉不能拖垮整条推荐链路 —— 召回是"多多益善"，缺一路只是少一点覆盖率；</li>
 *   <li><b>有界</b>：必须尊重 {@code limit}，禁止无界扫描（推荐链路是高 QPS 接口）；</li>
 *   <li><b>打分为 0~1</b>：通道内自行归一化，让不同量纲的通道分数可以放在一起比较。</li>
 * </ol>
 */
public interface RecallChannel {

    /** 通道名（用于打分特征、曝光归因、监控埋点） */
    String name();

    /**
     * 执行召回。
     *
     * @param ctx   召回上下文（用户侧共享输入）
     * @param limit 本路最多召回的条数
     * @param sink  候选池，{@code articleId → Candidate}；多路共用，天然去重
     */
    void recall(RecallContext ctx, int limit, Map<Long, Candidate> sink);

    /** 把候选写入候选池的公共逻辑 */
    default void put(Map<Long, Candidate> sink, long articleId, double score) {
        sink.computeIfAbsent(articleId, Candidate::new).addChannel(name(), score);
    }
}
