-- =====================================================================
--  高并发健康社交平台 · 核心表结构（MySQL 8.0 / InnoDB）
-- =====================================================================
CREATE DATABASE IF NOT EXISTS `health_social`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `health_social`;

-- ---------------------------- 文章 ----------------------------
DROP TABLE IF EXISTS `t_article`;
CREATE TABLE `t_article`
(
    `id`            BIGINT       NOT NULL COMMENT '雪花 ID',
    `author_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT '作者 ID',
    `title`         VARCHAR(128) NOT NULL DEFAULT '' COMMENT '标题',
    `summary`       VARCHAR(512) NOT NULL DEFAULT '' COMMENT '摘要',
    `content`       MEDIUMTEXT COMMENT '正文',
    `cover`         VARCHAR(255) NOT NULL DEFAULT '' COMMENT '封面',
    `like_count`    BIGINT       NOT NULL DEFAULT 0 COMMENT '点赞数（Redis 为准，DB 为准异步落库）',
    `comment_count` BIGINT       NOT NULL DEFAULT 0 COMMENT '评论数',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 删除',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_author_time` (`author_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文章';

-- ------------------------ 点赞关系（聚合落库目标表） ------------------------
DROP TABLE IF EXISTS `t_article_like`;
CREATE TABLE `t_article_like`
(
    `id`          BIGINT   NOT NULL COMMENT '雪花 ID',
    `user_id`     BIGINT   NOT NULL DEFAULT 0 COMMENT '点赞用户',
    `article_id`  BIGINT   NOT NULL DEFAULT 0 COMMENT '文章 ID',
    `status`      TINYINT  NOT NULL DEFAULT 1 COMMENT '1 已点赞 0 已取消',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_article` (`user_id`, `article_id`),
    KEY `idx_article` (`article_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文章点赞关系';

-- ---------------------------- 关注关系 ----------------------------
DROP TABLE IF EXISTS `t_user_follow`;
CREATE TABLE `t_user_follow`
(
    `id`           BIGINT   NOT NULL COMMENT '雪花 ID',
    `follower_id`  BIGINT   NOT NULL DEFAULT 0 COMMENT '粉丝（谁关注）',
    `followee_id`  BIGINT   NOT NULL DEFAULT 0 COMMENT '被关注者（大 V / 医生）',
    `status`       TINYINT  NOT NULL DEFAULT 1 COMMENT '1 关注中 0 已取关',
    `create_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_follower_followee` (`follower_id`, `followee_id`),
    KEY `idx_followee` (`followee_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户关注关系';

-- ---------------------------- 用户画像 ----------------------------
DROP TABLE IF EXISTS `t_user_profile`;
CREATE TABLE `t_user_profile`
(
    `id`            BIGINT       NOT NULL COMMENT '用户 ID',
    `nickname`      VARCHAR(64)  NOT NULL DEFAULT '',
    `level`         TINYINT      NOT NULL DEFAULT 0 COMMENT '0 普通用户 1 认证医生 2 明星医生',
    `follower_cnt`  BIGINT       NOT NULL DEFAULT 0 COMMENT '粉丝数（判断是否大 V）',
    `follow_cnt`    BIGINT       NOT NULL DEFAULT 0,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_follower_cnt` (`follower_cnt`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户画像';

-- ---------------------------- 钱包 ----------------------------
DROP TABLE IF EXISTS `t_user_wallet`;
CREATE TABLE `t_user_wallet`
(
    `id`          BIGINT                                 NOT NULL COMMENT '雪花 ID',
    `user_id`     BIGINT                                 NOT NULL DEFAULT 0,
    `balance`     DECIMAL(12, 2)                         NOT NULL DEFAULT 0.00 COMMENT '可用余额',
    `version`     BIGINT                                 NOT NULL DEFAULT 0 COMMENT '乐观锁',
    `create_time` DATETIME                               NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME                               NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户钱包';

-- ------------------------ 打赏业务单（面向用户） ------------------------
DROP TABLE IF EXISTS `t_donate_record`;
CREATE TABLE `t_donate_record`
(
    `id`           BIGINT                        NOT NULL COMMENT '雪花 ID',
    `biz_no`       VARCHAR(64)                   NOT NULL COMMENT '幂等号（客户端/服务端生成）',
    `user_id`      BIGINT                        NOT NULL DEFAULT 0,
    `project_id`   BIGINT                        NOT NULL DEFAULT 0 COMMENT '公益项目 ID',
    `amount`       DECIMAL(12, 2)                NOT NULL DEFAULT 0.00,
    `status`       VARCHAR(16)                   NOT NULL DEFAULT 'INIT' COMMENT 'INIT 已扣款待推送 / CONFIRMED 基金会已确认 / FAILED 终态失败 / CLOSED 已冲正',
    `tx_no`        VARCHAR(64)                   NOT NULL DEFAULT '' COMMENT '基金会流水号',
    `create_time`  DATETIME                      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME                      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_no` (`biz_no`),
    KEY `idx_user` (`user_id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='打赏业务单';

-- ------------------ 本地事务表（最终一致性的关键） ------------------
DROP TABLE IF EXISTS `t_donate_local_record`;
CREATE TABLE `t_donate_local_record`
(
    `id`           BIGINT          NOT NULL COMMENT '雪花 ID',
    `biz_no`       VARCHAR(64)     NOT NULL COMMENT '与 t_donate_record.biz_no 一致',
    `donate_id`    BIGINT          NOT NULL DEFAULT 0 COMMENT '关联打赏单',
    `user_id`      BIGINT          NOT NULL DEFAULT 0,
    `project_id`   BIGINT          NOT NULL DEFAULT 0,
    `amount`       DECIMAL(12, 2)  NOT NULL DEFAULT 0.00,
    `status`       VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待推送 / SENT 已推送待确认 / SUCCESS 已确认 / DEAD 超重试进人工',
    `retry_count`  INT             NOT NULL DEFAULT 0,
    `next_retry_at` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可重试时间',
    `tx_no`        VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '基金会返回的流水号',
    `last_error`   VARCHAR(512)    NOT NULL DEFAULT '' COMMENT '最后一次失败原因',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_no` (`biz_no`),
    KEY `idx_status_retry` (`status`, `next_retry_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='打赏本地事务表（本地消息表）';

-- ------------------------ 对账结果 ------------------------
DROP TABLE IF EXISTS `t_reconcile_report`;
CREATE TABLE `t_reconcile_report`
(
    `id`             BIGINT                       NOT NULL COMMENT '雪花 ID',
    `biz_date`       DATE                         NOT NULL COMMENT '对账日（T+1 对 T 日）',
    `local_cnt`      INT                          NOT NULL DEFAULT 0 COMMENT '本地成功笔数',
    `local_amount`   DECIMAL(16, 2)               NOT NULL DEFAULT 0.00,
    `remote_cnt`     INT                          NOT NULL DEFAULT 0 COMMENT '基金会账单笔数',
    `remote_amount`  DECIMAL(16, 2)               NOT NULL DEFAULT 0.00,
    `diff_cnt`       INT                          NOT NULL DEFAULT 0 COMMENT '差异笔数',
    `diff_biz_nos`   TEXT COMMENT '差异幂等号，逗号分隔',
    `status`         VARCHAR(16)                  NOT NULL DEFAULT 'BALANCED' COMMENT 'BALANCED 平账 / DIFF 有差异 / FAILED 对账失败',
    `create_time`    DATETIME                     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME                     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_date` (`biz_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='T+1 日终对账报告';

-- ---------------------------- 演示数据 ----------------------------
INSERT INTO `t_user_profile` (`id`, `nickname`, `level`, `follower_cnt`)
VALUES (10001, '张医生（认证）', 1, 120000),
       (10002, '李医生（明星）', 2, 3500000),
       (20001, '普通用户小王', 0, 12);

INSERT INTO `t_user_wallet` (`id`, `user_id`, `balance`) VALUES (1, 20001, 1000.00);
