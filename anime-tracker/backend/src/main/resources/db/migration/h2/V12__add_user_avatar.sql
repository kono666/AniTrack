-- ============================================================================
--  AniTrack · V12 用户头像（H2）
--
--  背景：`user.avatar` 这一列从 V1 起就在，**6 处读它、0 处写它** —— 前端一直拿首字母
--  或图标兜底。字段是留的，能力从来没有过，于是「换个头像」这个人人都会先试一下的
--  操作，在界面上根本不存在入口。这一版把图片本身存进来。
--
--  为什么**新开一张表**，而不是给 `user` 加一列 `avatar_bytes`：
--
--  · `user` 是全仓读得最频繁的表 —— 每条评论列表、每条回复、每条通知都要 join 它
--    取用户名。把几百 KB 的二进制放进这张表，等于让**每一次** `SELECT * FROM user`
--    都拖着这些字节走，哪怕这一屏一个字都不需要头像。分表之后只有真的要那张图时
--    才碰它（见 {@code AvatarService}）。
--  · 头像是一人一张、至多一行，用 `user_id` 作主键最自然，`save` 天然就是 upsert
--    语义（上传覆盖、不存在则新建），不需要先查再决定 insert/update。
--
--  为什么是 **BYTEA** 而不是 BLOB —— 这是实测结论，不是推测：
--
--  本机拿项目实际使用的 h2-2.2.224.jar 跑 org.h2.tools.Shell，同一张表里两种写法：
--      BYTEA → 报告为 BINARY VARYING        (JDBC VARBINARY, -3)
--      BLOB  → 报告为 BINARY LARGE OBJECT   (JDBC BLOB,      2004)
--  Hibernate 把裸 `byte[]` 映射成的期望类型正是 VARBINARY，两者只对得上 BYTEA 那一个。
--  所以本迁移**不需要**给「两方言语句逐字相同」这条不变量开例外（与 V4/V5/V7/V8 同规矩），
--  而实体那一侧必须用**裸 `byte[]`**、绝不能加 {@code @Lob}：{@code @Lob} 会让 Hibernate
--  按 BLOB 走（H2 这一侧与建表结果对不上），在 PostgreSQL 上更糟 —— 它映射成 `oid`，
--  那是另一个完全不同的类型。`ddl-auto: validate` 会在启动时把这两种情况都拦下来，
--  拦得住是好事，但要知道是谁在拦。
--
--  为什么 `user_id` 上挂 **ON DELETE CASCADE**，而这与 V7/V8 那条「user_id 不级联」
--  的规矩并不矛盾 —— 这是全 schema 唯一一个对 "user" 挂级联的外键，要写清楚：
--
--  · V7/V8 那条规矩的真正理由是「级联会**静默删掉子行，而计数器不跟着减**」
--    （见 V8 里 fk_reply_like_user 的说明），删的是有从属关系的数据；
--  · 头像表里**没有任何计数器**，也没有任何别的东西引用它。用户没了，他的头像就是
--    一条谁也读不到的孤儿行，跟着消失是唯一正确的行为。
--  · 本仓目前根本没有「删用户」这条路，所以这个外键今天只是一句数据完整性声明。
--    留着它，别顺手改成不级联 —— 那只会让将来真的删用户时多一堆要手工清理的行。
--
--  为什么**不建任何索引**：主键 `pk_user_avatar` 就是 `user_id` 上那棵树，
--  而这张表只有「按 user_id 取一行」这一种读法。别再加。
--
--  为什么 `updated_at` 可空、且没有 DEFAULT：与 V10 的 password_changed_at 同一条理由
--  ——给已有表加列时 NOT NULL 会因为缺 DEFAULT 而被两个库双双拒绝；这里虽是新表，
--  但保持一致。它在读路径上是 **ETag 与 Cache-Control 的取值来源**（见 AvatarService），
--  所以写入时一定会被赋值，为空只可能是历史行。
--
--  为什么新开 V12 而不是改 V11：已执行过的迁移是历史，只能往后加（同 V1~V11）。
--
--  与 postgres/V12__add_user_avatar.sql 一一对应（语句逐字相同，用例守着这一点）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS user_avatar (
    user_id      BIGINT      NOT NULL,
    content_type VARCHAR(40) NOT NULL,
    bytes        BYTEA       NOT NULL,
    updated_at   TIMESTAMP(6),
    CONSTRAINT pk_user_avatar PRIMARY KEY (user_id),
    CONSTRAINT fk_user_avatar_user FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE
);
