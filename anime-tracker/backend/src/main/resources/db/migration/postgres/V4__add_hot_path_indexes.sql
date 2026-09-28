-- ============================================================================
--  AniTrack · V4 给三张热表补索引（PostgreSQL）
--
--  背景、每条索引对应哪个仓储方法、以及**故意没建的两条**，都写在
--  h2/V4__add_hot_path_indexes.sql 的注释里，这里不重复。只说一句最关键的：
--
--  这几条索引在生产上是必需的，原因是 PG 不为外键列自动建索引（手册 5.4.5：
--  "the declaration of a foreign key constraint does not automatically create an
--  index on the referencing columns"），而 H2 会。开发库跑在 H2 上，所以这些查询
--  在开发机上一律是索引查询，在生产上则一律是全表扫描 —— 这个差异不做迁移是
--  发现不了的，它不会报错，只会慢。
--
--  这份与 H2 那份的语句逐字相同，没有方言差异：CREATE INDEX IF NOT EXISTS 是
--  PostgreSQL 9.5+ 就有的写法（生产用 PG 16），H2 也支持。V3 那边需要两份写法，
--  是因为 PG 的 ALTER TABLE ... ADD CONSTRAINT 不支持 IF NOT EXISTS。
--
--  建索引会短暂锁写。这三张表在生产都很小（review / anime_tracking 目前是空的，
--  agent_message 也就几百行），秒级完成，所以不用 CREATE INDEX CONCURRENTLY ——
--  何况它在 Flyway 的事务里根本跑不了（CONCURRENTLY 不允许在事务块内执行）。
--  等哪天这几张表大到需要 CONCURRENTLY，那也意味着该重新考虑索引策略了。
-- ============================================================================

-- 1) 追番：按用户取「最近更新」的列表
CREATE INDEX IF NOT EXISTS idx_anime_tracking_user_updated
    ON anime_tracking (user_id, updated_at);

-- 2) 追番：按番剧统计人数
CREATE INDEX IF NOT EXISTS idx_anime_tracking_subject
    ON anime_tracking (subject_id);

-- 3) 短评：某部番的评论列表
CREATE INDEX IF NOT EXISTS idx_review_subject_created
    ON review (subject_id, created_at);

-- 4) AI 会话：取某个会话的消息
CREATE INDEX IF NOT EXISTS idx_agent_message_conversation
    ON agent_message (conversation_id);
