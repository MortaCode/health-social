package com.health.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.social.entity.Article;
import com.health.social.mapper.bo.LikeCountDelta;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ArticleMapper extends BaseMapper<Article> {

    /**
     * 批量增减点赞数（一次网络往返完成整批，配合 rewriteBatchedStatements）
     */
    int batchIncrLikeCount(@Param("list") List<LikeCountDelta> list);

    /**
     * 全量回写点赞数（以 Redis 为准，修正 DB 漂移）
     */
    int batchSetLikeCount(@Param("list") List<LikeCountDelta> list);

    /**
     * 预热指定文章（缓存预热接口使用）。
     * 注意：方法名不能叫 selectByIds —— MP 内置注入器已有同名 SelectByIds，会被忽略并打 WARN。
     * 也可直接使用 BaseMapper 自带的 selectBatchIds。
     */
    List<Article> selectArticlesByIds(@Param("ids") List<Long> ids);
}
