package com.health.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 打赏业务单（面向用户）
 */
@Data
@TableName("t_donate_record")
public class DonateRecord implements Serializable {

    /** INIT 已扣款待推送 */
    public static final String STATUS_INIT = "INIT";
    /** CONFIRMED 基金会已确认 */
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    /** FAILED 终态失败（等待冲正） */
    public static final String STATUS_FAILED = "FAILED";
    /** CLOSED 已冲正退款 */
    public static final String STATUS_CLOSED = "CLOSED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 幂等号 */
    private String bizNo;

    private Long userId;

    private Long projectId;

    private BigDecimal amount;

    private String status;

    /** 基金会流水号 */
    private String txNo;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
