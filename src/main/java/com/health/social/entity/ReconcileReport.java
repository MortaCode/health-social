package com.health.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * T+1 日终对账报告
 */
@Data
@TableName("t_reconcile_report")
public class ReconcileReport implements Serializable {

    public static final String STATUS_BALANCED = "BALANCED";
    public static final String STATUS_DIFF = "DIFF";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private LocalDate bizDate;

    private Integer localCnt;

    private BigDecimal localAmount;

    private Integer remoteCnt;

    private BigDecimal remoteAmount;

    private Integer diffCnt;

    private String diffBizNos;

    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
