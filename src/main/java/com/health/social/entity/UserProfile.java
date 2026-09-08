package com.health.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户画像（是否大 V、是否认证医生）
 */
@Data
@TableName("t_user_profile")
public class UserProfile implements Serializable {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String nickname;

    /** 0 普通 1 认证医生 2 明星医生 */
    private Integer level;

    private Long followerCnt;

    private Long followCnt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
