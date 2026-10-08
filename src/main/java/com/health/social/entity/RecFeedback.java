package com.health.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 推荐反馈流水（正负样本）。
 *
 * <p>闭环链路：曝光（负样本）→ 点击（弱正）→ 点赞（强正）/ 不感兴趣（负反馈），
 * 反馈同时实时更新 Redis 计数与用户兴趣画像，形成"越用越准"的正循环。
 */
@Data
@TableName("t_rec_feedback")
public class RecFeedback implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long articleId;

    private String scene;

    /** CLICK 点击 / LIKE 点赞 / DISLIKE 不感兴趣 */
    private String action;

    private LocalDateTime createTime;
}
