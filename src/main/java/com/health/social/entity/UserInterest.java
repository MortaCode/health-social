package com.health.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户兴趣画像（持久化副本）。
 *
 * <p>在线读写全部走 Redis Hash {@code rec:interest:{userId}}（毫秒级、可原子加减），
 * 本表由 {@code CandidatePoolMaintainer} 定期回写，承担两个职责：
 * <ol>
 *   <li>Redis 数据丢失 / 新节点冷启动时的恢复源；</li>
 *   <li>离线侧做人群兴趣分析、兴趣标签挖掘。</li>
 * </ol>
 */
@Data
@TableName("t_user_interest")
public class UserInterest implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long tagId;

    /** 兴趣分（0 ~ cap，默认 cap=10） */
    private BigDecimal score;

    private LocalDateTime updateTime;
}
