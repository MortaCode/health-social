package com.health.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.social.entity.RecExposure;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 推荐曝光流水 Mapper（推荐模块）。
 */
public interface RecExposureMapper extends BaseMapper<RecExposure> {

    /** 批量落库曝光流水（缓冲队列定期刷盘时调用） */
    int insertBatch(@Param("list") List<RecExposure> list);
}
