package com.health.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 标签字典（推荐模块内容侧特征）。
 *
 * <p>属于推荐模块自有表，与推流模块的文章/点赞表无耦合。
 */
@Data
@TableName("t_tag")
public class Tag implements Serializable {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String name;

    private LocalDateTime createTime;
}
