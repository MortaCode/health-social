package com.health.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.social.entity.RecFeedback;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 推荐反馈流水 Mapper（推荐模块）。
 */
public interface RecFeedbackMapper extends BaseMapper<RecFeedback> {

    /** 批量落库反馈流水 */
    int insertBatch(@Param("list") List<RecFeedback> list);
}
