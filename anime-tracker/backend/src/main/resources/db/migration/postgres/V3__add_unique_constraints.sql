-- ============================================================================
--  AniTrack · V3 给三张「一人一条」的表补唯一约束（PostgreSQL）
--
--  背景与取舍见 h2/V3__add_unique_constraints.sql 的头部说明，两边做的事完全一样：
--  先清掉存量重复行，再加唯一约束。这里只记 PG 侧写法上的差异。
--
--  PG 的 ALTER TABLE ... ADD CONSTRAINT 不支持 IF NOT EXISTS（H2 支持），
--  所以每条约束外面套一段匿名块自己判断。判断条件写的是「这张表上有没有一个
--  列集完全相同的唯一索引」，而不是「有没有叫这个名字的约束」—— 与 V2 里
--  邮箱那条同样的理由：约束可能早就被别的名字建出来了。
-- ============================================================================

-- 1) 去除存量重复行
--
-- 排序里都带 id 兜底：updated_at 可能为 NULL，也可能两行同一微秒，缺少唯一决胜列时
-- ROW_NUMBER() 的分配不确定，同一份数据跑两次可能删掉不同的行。

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
-- 保留**最早**的那一条：watched_at 是「首次打勾时间」，后来的重复行只是竞态产物。
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

-- 2) 唯一约束（幂等）

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_index i
        WHERE i.indrelid = 'anime_tracking'::regclass
          AND i.indisunique
          AND i.indpred IS NULL
          AND (SELECT array_agg(a.attname::text ORDER BY a.attname::text)
               FROM pg_attribute a
               WHERE a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey))::text[]
              = ARRAY['subject_id', 'user_id']::text[]
    ) THEN
        ALTER TABLE anime_tracking
            ADD CONSTRAINT uk_anime_tracking_user_subject UNIQUE (user_id, subject_id);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_index i
        WHERE i.indrelid = 'review'::regclass
          AND i.indisunique
          AND i.indpred IS NULL
          AND (SELECT array_agg(a.attname::text ORDER BY a.attname::text)
               FROM pg_attribute a
               WHERE a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey))::text[]
              = ARRAY['subject_id', 'user_id']::text[]
    ) THEN
        ALTER TABLE review
            ADD CONSTRAINT uk_review_user_subject UNIQUE (user_id, subject_id);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_index i
        WHERE i.indrelid = 'episode_watched'::regclass
          AND i.indisunique
          AND i.indpred IS NULL
          AND (SELECT array_agg(a.attname::text ORDER BY a.attname::text)
               FROM pg_attribute a
               WHERE a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey))::text[]
              = ARRAY['anime_id', 'episode_num', 'user_id']::text[]
    ) THEN
        ALTER TABLE episode_watched
            ADD CONSTRAINT uk_episode_watched_user_anime_episode UNIQUE (user_id, anime_id, episode_num);
    END IF;
END
$$;
