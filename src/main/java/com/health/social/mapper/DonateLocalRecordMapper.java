package com.health.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.social.entity.DonateLocalRecord;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DonateLocalRecordMapper extends BaseMapper<DonateLocalRecord> {

    /**
     * 拉取待推送 / 待重试的记录（配合 idx_status_retry 索引）
     */
    List<DonateLocalRecord> selectRetryable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 超过重试上限的死信
     */
    List<DonateLocalRecord> selectDead(@Param("retryLimit") int retryLimit, @Param("limit") int limit);
}
