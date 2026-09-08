package com.health.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文章
 */
@Data
@TableName("t_article")
public class Article implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long authorId;

    private String title;

    private String summary;

    private String content;

    private String cover;

    /** 点赞数：Redis 为准，DB 由 MQ 异步聚合落库 */
    private Long likeCount;

    private Long commentCount;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
