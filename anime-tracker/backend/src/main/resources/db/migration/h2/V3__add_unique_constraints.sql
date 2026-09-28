-- ============================================================================
--  AniTrack · V3 给三张「一人一条」的表补唯一约束（H2）
--
--  背景：anime_tracking / review / episode_watched 三张表在业务上都是「一个用户
--  对同一部番只有一条」，但库里从来没有唯一约束，写入又全是「先查后插」。
--  两个请求同时走一遍「查不到 → 插入」（双击、重试、并发），就会落下两行。
--
--  为什么必须修：重复行一旦存在，所有 findByUserAndSubjectId 这种「期望单行」的
--  查询会抛 IncorrectResultSizeDataAccessException —— 该用户相关的接口从此永久 500，
--  而且不会自愈（数据还在，重启也没用）。AdminService 的计数查询同样会被拖垮。
--
--  这个脚本做两件事，顺序不能反：先清掉存量重复行（否则加约束会直接失败），
--  再加约束把这条路堵死。约束是最终防线，应用层的「查不到就插入」只是快路径。
--
--  与 postgres/V3__add_unique_constraints.sql 一一对应。
-- ============================================================================

-- 1) 去除存量重复行
--
-- 保留哪一行：功能上「一条就够」，选最新的那一行信息最全。
-- 排序里都带 id 兜底：updated_at 可能为 NULL，也可能两行同一毫秒，没有唯一
-- 决胜列时 ROW_NUMBER() 的分配不确定，同一份数据跑两次可能删掉不同的行。
-- 包一层派生表是必需的 —— 直接对着被删的表写子查询，H2 会拒绝执行。

-- 追番：每个 (user_id, subject_id) 只留 updated_at 最新的一行
DELETE FROM anime_tracking
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY user_id, subject_id
                                  ORDER BY updated_at DESC NULLS LAST, id DESC) AS rn
        FROM anime_tracking
    ) ranked
    WHERE ranked.rn > 1
);

-- 短评：同一用户对同一部番只留一条，取最近改动过的
DELETE FROM review
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY user_id, subject_id
                                  ORDER BY updated_at DESC NULLS LAST, id DESC) AS rn
        FROM review
    ) ranked
    WHERE ranked.rn > 1
);

-- 已看剧集：每个 (user_id, anime_id, episode_num) 只留一条。
-- 这里刻意保留**最早**的那一条：watched_at 是 updatable=false 的「首次打勾时间」，
-- 语义上它才是真的，后来的重复行只是竞态产物。
DELETE FROM episode_watched
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY user_id, anime_id, episode_num
                                  ORDER BY watched_at ASC NULLS LAST, id ASC) AS rn
        FROM episode_watched
    ) ranked
    WHERE ranked.rn > 1
);

-- 2) 唯一约束
--
-- 命名沿用 V1 的约定（uk_ 前缀 + 可读名字）。Hibernate 的 validate 只比对表与列、
-- 不看约束名，所以名字可以按我们的习惯来；可读名字在排障时值千金。
--
-- IF NOT EXISTS 让脚本在已经手工加过约束的库上也能跑过（H2 支持这个写法；
-- PostgreSQL 不支持，那边用了一段匿名块做等价判断）。

ALTER TABLE anime_tracking ADD CONSTRAINT IF NOT EXISTS uk_anime_tracking_user_subject
    UNIQUE (user_id, subject_id);

ALTER TABLE review ADD CONSTRAINT IF NOT EXISTS uk_review_user_subject
    UNIQUE (user_id, subject_id);

ALTER TABLE episode_watched ADD CONSTRAINT IF NOT EXISTS uk_episode_watched_user_anime_episode
    UNIQUE (user_id, anime_id, episode_num);
