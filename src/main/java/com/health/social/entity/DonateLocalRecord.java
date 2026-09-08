package com.health.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 打赏本地事务表（本地消息表）
 *
 * <p>最终一致性的核心：业务事务与消息写入在同一个本地事务里完成，
 * 事务提交后由异步任务推送基金会，失败则按指数退避重试，
 * 超过重试上限标记为 DEAD 进入人工 / 由 T+1 对账兜底。
 */
@Data
@TableName("t_donate_local_record")
public class DonateLocalRecord implements Serializable {

    /** 待推送 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已推送，等基金会回执 */
    public static final String STATUS_SENT = "SENT";
    /** 基金会已确认 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 超重试上限，转人工 */
    public static final String STATUS_DEAD = "DEAD";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String bizNo;

    private Long donateId;

    private Long userId;

    private Long projectId;

    private BigDecimal amount;

    private String status;

    private Integer retryCount;

    private LocalDateTime nextRetryAt;

    /** 基金会返回的流水号 */
    private String txNo;

    private String lastError;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
