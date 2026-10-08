-- =====================================================================
--  推荐模块 · 独立表结构（MySQL 8.0 / InnoDB）
--  ---------------------------------------------------------------------
--  设计原则：
--   1. 只新增，不修改任何既有表（t_article / t_article_like / t_user_follow
--      / t_user_profile / t_user_wallet / t_donate_* 一行都不动），
--      推流模块与推荐模块在存储层完全解耦；
--   2. 推荐模块的"在线状态"全部放 Redis（见 RecRedisKeys），
--      这些表只承担三件事：内容侧标签、用户兴趣画像的持久化、离线样本流水；
--   3. 所有表都带 idx 供离线分析，不做跨模块外键约束。
--
--  执行：mysql -uroot -p < src/main/resources/db/rec_schema.sql
-- =====================================================================
CREATE DATABASE IF NOT EXISTS `health_social`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `health_social`;

-- ---------------------------- 标签字典 ----------------------------
DROP TABLE IF EXISTS `t_tag`;
CREATE TABLE `t_tag`
(
    `id`          BIGINT      NOT NULL COMMENT '标签 ID（雪花/人工分配）',
    `name`        VARCHAR(64) NOT NULL DEFAULT '' COMMENT '标签名',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='标签字典（推荐内容侧特征）';

-- ------------------------ 文章-标签（内容侧画像） ------------------------
DROP TABLE IF EXISTS `t_article_tag`;
CREATE TABLE `t_article_tag`
(
    `id`          BIGINT         NOT NULL COMMENT '雪花 ID',
    `article_id`  BIGINT         NOT NULL DEFAULT 0 COMMENT '文章 ID',
    `tag_id`      BIGINT         NOT NULL DEFAULT 0 COMMENT '标签 ID',
    `weight`      DECIMAL(6, 4)  NOT NULL DEFAULT 1.0000 COMMENT '标签权重：1=主标签，越小越次要',
    `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_article_tag` (`article_id`, `tag_id`),
    KEY `idx_tag_article` (`tag_id`, `article_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文章标签（推荐内容侧特征）';

-- ------------------------ 用户兴趣画像（持久化） ------------------------
-- 在线读写走 Redis Hash（rec:interest:{userId}），本表由定时任务定期回写，
-- 用于：① Redis 数据丢失后的冷启动恢复；② 离线侧做人群/兴趣分析。
DROP TABLE IF EXISTS `t_user_interest`;
CREATE TABLE `t_user_interest`
(
    `id`          BIGINT         NOT NULL COMMENT '雪花 ID',
    `user_id`     BIGINT         NOT NULL DEFAULT 0,
    `tag_id`      BIGINT         NOT NULL DEFAULT 0,
    `score`       DECIMAL(10, 4) NOT NULL DEFAULT 0.0000 COMMENT '兴趣分（0 ~ cap，默认 cap=10）',
    `update_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_tag` (`user_id`, `tag_id`),
    KEY `idx_user_score` (`user_id`, `score`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户兴趣画像';

-- ------------------------ 曝光流水（离线训练样本） ------------------------
-- 在线去重用 Redis ZSet（rec:exposed:{userId}），本表是异步落库的明细流水，
-- 用途：CTR 模型训练、召回通道效果归因、多样性离线评估。
DROP TABLE IF EXISTS `t_rec_exposure`;
CREATE TABLE `t_rec_exposure`
(
    `id`         BIGINT         NOT NULL COMMENT '雪花 ID',
    `user_id`    BIGINT         NOT NULL DEFAULT 0,
    `article_id` BIGINT         NOT NULL DEFAULT 0,
    `scene`      VARCHAR(32)    NOT NULL DEFAULT 'home' COMMENT '场景：home / detail / search',
    `position`   INT            NOT NULL DEFAULT 0 COMMENT '在本次推荐列表中的位置（0 起）',
    `score`      DECIMAL(10, 4) NOT NULL DEFAULT 0.0000 COMMENT '排序分快照',
    `channels`   VARCHAR(128)   NOT NULL DEFAULT '' COMMENT '命中的召回通道，逗号分隔',
    `request_id` VARCHAR(64)    NOT NULL DEFAULT '' COMMENT '请求链路 ID，用于和反馈关联',
    `exposed_at` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `exposed_at`),
    KEY `idx_article_time` (`article_id`, `exposed_at`),
    KEY `idx_request` (`request_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='推荐曝光流水';

-- ------------------------ 反馈流水（正负样本） ------------------------
DROP TABLE IF EXISTS `t_rec_feedback`;
CREATE TABLE `t_rec_feedback`
(
    `id`          BIGINT      NOT NULL COMMENT '雪花 ID',
    `user_id`     BIGINT      NOT NULL DEFAULT 0,
    `article_id`  BIGINT      NOT NULL DEFAULT 0,
    `scene`       VARCHAR(32) NOT NULL DEFAULT 'home',
    `action`      VARCHAR(16) NOT NULL DEFAULT '' COMMENT 'CLICK 点击 / LIKE 点赞 / DISLIKE 不感兴趣',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `create_time`),
    KEY `idx_article_action` (`article_id`, `action`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='推荐反馈流水';
