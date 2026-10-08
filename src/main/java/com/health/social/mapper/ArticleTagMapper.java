package com.health.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.social.entity.ArticleTag;

/**
 * 文章标签 Mapper（推荐模块）。
 *
 * <p>批量查询用 MyBatis-Plus 的 {@code LambdaQueryWrapper.in(...)} 即可（见
 * {@code InterestProfileService}），无需自定义 XML。
 */
public interface ArticleTagMapper extends BaseMapper<ArticleTag> {
}
