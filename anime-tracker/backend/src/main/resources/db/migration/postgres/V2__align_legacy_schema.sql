-- ============================================================================
--  AniTrack · V2 把「Flyway 之前就存在的库」对齐到 V1 描述的结构（PostgreSQL）
--
--  背景：老库是 Hibernate 的 ddl-auto=update 一点点改出来的，它只加表、加列，
--  既不会加约束，也不会改已有列的类型。这个脚本补上 PG 这边已知的差异；
--  全新的库也会执行到这里（V1 已经是对的结构），所以它必须可重复执行。
--
--  与 h2/V2__align_legacy_schema.sql 一一对应。H2 那边多做一件事：把 rating 的
--  DECIMAL(3,1) 改成 NUMERIC(3,1)（两个库的驱动对这个拼写报的类型码不同）。
--  PostgreSQL 无论写 decimal 还是 numeric，底层都是同一个 numeric 类型、都报
--  Types#NUMERIC，所以这里不需要动。
-- ============================================================================

-- 邮箱唯一约束。
--
-- 为什么要写成一段匿名块：ALTER TABLE ... ADD CONSTRAINT 不支持 IF NOT EXISTS，
-- 而这段脚本在全新的库上也会被执行到（V1 已经加过这条约束了）。要么让它幂等，
-- 要么让它在干净的库上失败 —— 显然是前者。
--
-- 判断条件写的是「email 上有没有任何唯一索引」，而不是「有没有叫 uk_user_email 的约束」：
-- Hibernate 当年自己建的约束叫 user_email_key，只看名字会漏判，结果就是重复加一条
-- 同列的唯一约束（PG 允许，但白白多一个索引）。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_index i
                 JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
        WHERE i.indrelid = '"user"'::regclass
          AND i.indisunique
          AND a.attname = 'email'
    ) THEN
        ALTER TABLE "user" ADD CONSTRAINT uk_user_email UNIQUE (email);
    END IF;
END
$$;
