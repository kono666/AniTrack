-- ============================================================================
--  AniTrack · V2 把「Flyway 之前就存在的库」对齐到 V1 描述的结构（H2）
--
--  背景：老库是 Hibernate 的 ddl-auto=update 一点点改出来的，它只加表、加列，
--  既不会加约束，也不会改已有列的类型。这个脚本把老库上已知的两处差异补平；
--  全新的库也会执行到这里（V1 已经是对的结构），所以每一步都必须可重复执行。
--
--  与 postgres/V2__align_legacy_schema.sql 一一对应。PG 那边只需要补邮箱约束 ——
--  同样的列在 PostgreSQL 上本来就是 numeric，不存在下面第 1 步的问题。
-- ============================================================================

-- 1) rating 的类型拼写：DECIMAL(3,1) -> NUMERIC(3,1)
--
-- 两者在 SQL 里是同一个类型，但 H2 的驱动会**照着声明的名字**报 JDBC 类型码：
--   DECIMAL(3,1) -> Types#DECIMAL(3)
--   NUMERIC(3,1) -> Types#NUMERIC(2)
-- 而 PostgreSQL 无论怎么写都报 Types#NUMERIC(2)。Hibernate 的 ddl-auto=validate
-- 正是拿类型码比对的，实体上写的是 NUMERIC(3,1)，于是老开发库这里会校验失败。
-- 改拼写而不是改实体：NUMERIC 是标准写法，也是两边唯一能统一的写法。
--
-- 已经是对类型的库重复执行这条只是原地改写，不会报错(H2 上实测 470 行的表可直接改)。
ALTER TABLE ANIME ALTER COLUMN RATING NUMERIC(3,1);

-- 2) email 唯一约束
--
-- 邮箱唯一是随「邮箱必填唯一」那次改动加进实体类的，而 update 模式**只给已存在的表补列、
-- 不补约束** —— 实测迁移完的库里只有主键和 username 的唯一索引，email 上什么都没有。
-- 应用层的 existsByEmail 能挡住顺序重复注册，但并发之间仍有窗口，唯一索引才是最终防线。
ALTER TABLE "user" ADD CONSTRAINT IF NOT EXISTS uk_user_email UNIQUE (email);
