package com.health.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.social.entity.ArticleLike;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ArticleLikeMapper extends BaseMapper<ArticleLike> {

    /**
     * 批量 upsert 点赞关系（MySQL ON DUPLICATE KEY UPDATE）
     */
    int batchUpsert(@Param("list") List<ArticleLike> list);
}
