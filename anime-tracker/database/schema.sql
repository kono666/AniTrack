-- ============================================
-- 动漫追番管理平台 - 数据库建表参考脚本 (MySQL 语法)
--
-- 说明: 项目实际运行时由 JPA (Hibernate, ddl-auto=update)
--       依据实体类自动建表, 无需手工执行本脚本。
--       本文件保留作为表结构与索引设计的可读参考。
-- ============================================

CREATE DATABASE IF NOT EXISTS anime_tracker
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE anime_tracker;

-- 用户表
DROP TABLE IF EXISTS review;
DROP TABLE IF EXISTS anime_tracking;
DROP TABLE IF EXISTS episode;
DROP TABLE IF EXISTS anime;
DROP TABLE IF EXISTS `user`;

CREATE TABLE `user` (
    id              BIGINT          AUTO_INCREMENT  PRIMARY KEY,
    username        VARCHAR(50)     NOT NULL UNIQUE  COMMENT '用户名',
    password        VARCHAR(100)    NOT NULL         COMMENT '密码 (BCrypt 哈希, 长度恒为 60)',
    email           VARCHAR(100)    NULL UNIQUE      COMMENT '邮箱. 唯一; 允许 NULL 是为了兼容老数据',
    avatar          VARCHAR(255)    NULL             COMMENT '头像URL',
    role            VARCHAR(10)     NOT NULL DEFAULT 'USER' COMMENT '角色: USER/ADMIN',
    status          VARCHAR(10)     NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/DISABLED',
    failed_attempts INT             NULL             COMMENT '连续登录失败次数, 成功登录后清零',
    locked_until    DATETIME        NULL             COMMENT '锁定截止时间, NULL 表示未锁定',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 关于 email 的两个细节, 与实体类 User 上的注释一致:
--   1. 唯一, 但不加 NOT NULL —— 表里存在历史空邮箱数据, 加非空约束会让迁移失败.
--      「注册必须填邮箱」由 RegisterRequest 的 @NotBlank 在接口层保证.
--   2. MySQL / PostgreSQL / H2 的唯一索引都不约束 NULL, 多个 NULL 可以共存.
--
-- 注意: 这里刻意不再提供 INSERT 初始管理员的语句.
-- 曾经的写法是 INSERT ... ('admin', 'admin123', ...) —— 那有两个问题:
--   一是密码在库里是明文, 而登录时用的是 BCrypt 比对, 这样插进去的账号根本登不进去;
--   二是把一个默认密码写进了仓库.
-- 现在的做法是应用启动时读 ADMIN_USERNAME / ADMIN_PASSWORD 自动创建 (见 DataInitializer),
-- 密码经 BCrypt 哈希后入库, 且只在库里没有任何管理员时执行一次.

-- 已存在的老库如何补上本轮新增的列与约束 (由 JPA 的 ddl-auto=update 自动完成列的部分,
-- 但实测 update 模式**不会**补建 email 的唯一约束, 需要手工执行):
--   ALTER TABLE `user` ADD COLUMN failed_attempts INT NULL;
--   ALTER TABLE `user` ADD COLUMN locked_until DATETIME NULL;
--   ALTER TABLE `user` ADD CONSTRAINT uk_user_email UNIQUE (email);

-- 番剧表
CREATE TABLE anime (
    id              INT             AUTO_INCREMENT  PRIMARY KEY,
    title           VARCHAR(200)    NOT NULL         COMMENT '日文原名',
    title_cn        VARCHAR(200)    NULL             COMMENT '中文名',
    summary         TEXT            NULL             COMMENT '简介',
    cover_url       VARCHAR(500)    NULL             COMMENT '封面图URL',
    date            VARCHAR(20)     NULL             COMMENT '播出日期',
    platform        VARCHAR(50)     NULL             COMMENT '放送平台',
    total_episodes  INT             NULL             COMMENT '总集数',
    rating          DECIMAL(3,1)    NULL             COMMENT '评分 1-10',
    rating_count    INT             NULL             COMMENT '评分人数',
    tags            VARCHAR(500)    NULL             COMMENT '标签,逗号分隔',
    season          VARCHAR(30)     NULL             COMMENT '季度如2024-01',
    `rank`          INT             NULL             COMMENT '排名',
    status          VARCHAR(20)     NOT NULL DEFAULT 'finished' COMMENT '状态'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='番剧表';

-- 剧集表
CREATE TABLE episode (
    id              BIGINT          AUTO_INCREMENT  PRIMARY KEY,
    anime_id        INT             NOT NULL         COMMENT '番剧ID',
    episode_num     INT             NOT NULL         COMMENT '第几集',
    title           VARCHAR(200)    NULL             COMMENT '剧集标题',
    airdate         VARCHAR(20)     NULL             COMMENT '播出日期',
    duration        VARCHAR(10)     NULL             COMMENT '时长',
    FOREIGN KEY (anime_id) REFERENCES anime(id) ON DELETE CASCADE,
    INDEX idx_anime_id (anime_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='剧集表';

-- 追番记录表
CREATE TABLE anime_tracking (
    id          BIGINT          AUTO_INCREMENT  PRIMARY KEY,
    user_id     BIGINT          NOT NULL         COMMENT '用户ID',
    subject_id  INT             NOT NULL         COMMENT 'Bangumi番剧ID',
    status      VARCHAR(20)     NOT NULL         COMMENT '追番状态: want_to_watch/watching/watched/on_hold/dropped',
    progress    INT             NOT NULL DEFAULT 0 COMMENT '观看进度(集数)',
    score       TINYINT         NULL             COMMENT '个人评分(1-10)',
    notes       TEXT            NULL             COMMENT '个人备注',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (user_id) REFERENCES `user`(id) ON DELETE CASCADE,
    UNIQUE KEY uk_user_subject (user_id, subject_id),
    INDEX idx_user_id (user_id),
    INDEX idx_subject_id (subject_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='追番记录表';

-- 评论表
CREATE TABLE review (
    id          BIGINT          AUTO_INCREMENT  PRIMARY KEY,
    user_id     BIGINT          NOT NULL         COMMENT '用户ID',
    subject_id  INT             NOT NULL         COMMENT 'Bangumi番剧ID',
    rating      TINYINT         NOT NULL         COMMENT '评分(1-10)',
    content     TEXT            NULL             COMMENT '评论内容',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (user_id) REFERENCES `user`(id) ON DELETE CASCADE,
    UNIQUE KEY uk_user_subject (user_id, subject_id),
    INDEX idx_subject_id (subject_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论表';
