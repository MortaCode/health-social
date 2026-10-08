package com.health.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 推荐曝光流水（离线训练样本）。
 *
 * <p>在线去重走 Redis ZSet {@code rec:exposed:{userId}}（快、可裁剪），
 * 本表是异步批量落库的明细，用于 CTR 模型训练、召回通道效果归因、多样性离线评估。
 */
@Data
@TableName("t_rec_exposure")
public class RecExposure implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long articleId;

    /** 场景：home / detail / search */
    private String scene;

    /** 在本次推荐列表中的位置（0 起） */
    private Integer position;

    /** 排序分快照 */
    private BigDecimal score;

    /** 命中的召回通道，逗号分隔 */
    private String channels;

    /** 请求链路 ID，用于把曝光与后续反馈关联起来 */
    private String requestId;

    private LocalDateTime exposedAt;
}
