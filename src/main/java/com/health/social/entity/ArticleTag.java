package com.health.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 文章标签（推荐模块内容侧特征）。
 *
 * <p>推荐模块通过它做两件事：
 * <ol>
 *   <li><b>兴趣召回</b>：构建 Redis 倒排 {@code rec:tag:articles:{tagId}}；</li>
 *   <li><b>兴趣匹配打分</b>：候选文章的标签与用户兴趣画像求交。</li>
 * </ol>
 *
 * <p>注意：这是推荐模块的<b>旁挂</b>表。文章正文仍由 {@code t_article} 承载，
 * 打标签是推荐侧行为，因此不往 {@code t_article} 上加字段 —— 避免污染推流模块的表结构。
 */
@Data
@TableName("t_article_tag")
public class ArticleTag implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long articleId;

    private Long tagId;

    /** 标签权重：1=主标签，越小越次要（用于兴趣匹配加权） */
    private BigDecimal weight;

    private LocalDateTime createTime;
}
