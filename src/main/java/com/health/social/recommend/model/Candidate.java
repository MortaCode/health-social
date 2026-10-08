package com.health.social.recommend.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 召回候选。
 *
 * <p>生命周期分三段，字段按段填充，避免为每条候选创建多个对象：
 * <ol>
 *   <li><b>召回段</b>：只有 {@code articleId} + {@code channelScores}（各通道写入命中分）；</li>
 *   <li><b>富化段</b>：批量补齐 {@code authorId}、{@code publishTimeMs}、{@code tagIds}；</li>
 *   <li><b>排序段</b>：写入 {@code finalScore}，排序后取 TopN。</li>
 * </ol>
 *
 * <p>多个通道命中同一篇文章时，候选只保留一份（用 {@code Map<articleId, Candidate>} 去重），
 * 命中信息累积在 {@code channelScores} 里 —— 这是"多路召回"能产生协同增益的关键：
 * 同时被"热点"和"你的兴趣"命中的内容，置信度天然更高。
 */
@Data
public class Candidate {

    private long articleId;

    /** 作者 ID（富化阶段补齐） */
    private Long authorId;

    /** 发布时间毫秒（富化阶段补齐） */
    private long publishTimeMs;

    /** 标签 ID 列表（富化阶段补齐） */
    private List<Long> tagIds = new ArrayList<>();

    /** 通道名 → 该通道给出的置信分（0~1）。同一通道重复命中取最大值 */
    private Map<String, Double> channelScores = new LinkedHashMap<>();

    /** 排序阶段写入的最终分 */
    private double finalScore;

    public Candidate() {
    }

    public Candidate(long articleId) {
        this.articleId = articleId;
    }

    /** 记录一次通道命中（同通道取最大值，避免重复计入） */
    public void addChannel(String channel, double score) {
        double v = Math.max(0D, Math.min(1D, score));
        channelScores.merge(channel, v, Math::max);
    }

    /**
     * 通道置信度。
     *
     * <p>公式：{@code 0.6 * max(单通道最高分) + 0.4 * min(1, Σ通道分)}。
     * <ul>
     *   <li>前半段保证"被一个高置信通道强命中"的候选不吃亏；</li>
     *   <li>后半段奖励"被多个通道同时命中"的候选 —— 多路共识是比单路高分更可靠的信号。</li>
     * </ul>
     */
    public double channelScore() {
        if (channelScores.isEmpty()) {
            return 0D;
        }
        double max = 0D;
        double sum = 0D;
        for (double v : channelScores.values()) {
            max = Math.max(max, v);
            sum += v;
        }
        return Math.min(1D, 0.6D * max + 0.4D * Math.min(1D, sum));
    }

    /** 召回阶段用于粗排截断的分数 */
    public double recallScore() {
        return channelScore();
    }

    /** 命中的通道名，逗号分隔（曝光流水里记录，用于离线归因） */
    public String channels() {
        return String.join(",", channelScores.keySet());
    }
}
