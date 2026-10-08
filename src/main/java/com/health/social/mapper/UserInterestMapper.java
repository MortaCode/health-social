package com.health.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.social.entity.UserInterest;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户兴趣画像 Mapper（推荐模块）。
 */
public interface UserInterestMapper extends BaseMapper<UserInterest> {

    /**
     * 批量 upsert：一次网络往返写入整批兴趣分。
     *
     * <p>依赖唯一键 {@code uk_user_tag(user_id, tag_id)}，冲突时直接覆盖 score，
     * 避免"先查后写"的两次往返与并发覆盖问题。
     */
    int upsertBatch(@Param("list") List<UserInterest> list);
}
