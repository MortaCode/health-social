package com.health.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.social.entity.DonateRecord;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DonateRecordMapper extends BaseMapper<DonateRecord> {

    /**
     * 查询某业务日已确认的打赏单（T+1 对账用）
     */
    List<DonateRecord> selectConfirmedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
