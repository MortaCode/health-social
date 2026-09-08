package com.health.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户关注关系
 */
@Data
@TableName("t_user_follow")
public class UserFollow implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 粉丝 */
    private Long followerId;

    /** 被关注者 */
    private Long followeeId;

    /** 1 关注中 0 已取关 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
