-- ============================================================================
--  AniTrack · V4 给三张热表补索引（H2）
--
--  背景：整个库里原本只有 anime_tag 上的两个索引（V1 建的 idx_animetag_anime /
--  idx_animetag_tag），其余每一条「按某人取追番」「按某部番取评论」的查询都在全表扫描。
--
--  为什么一直没人发现：开发库是 H2，而 **H2 会为每个外键列自动建一棵索引**
--  （INFO_SCHEMA 里的 FK_<约束名>_INDEX_<n>）。生产是 PostgreSQL，而 PG **不会**
--  —— 官方手册 5.4.5 节原话是 "the declaration of a foreign key constraint does not
--  automatically create an index on the referencing columns"。于是同一句
--  WHERE user_id = ? 在开发机上是索引查询、在生产上是全表扫描，两边的 EXPLAIN
--  长得完全不一样，而 dev 上永远看不出慢。这正是这一批索引该补而没人补的原因。
--
--  所以下面第 1、4 条在 H2 上是**重复的**（外键那棵索引已经能用），仍然要建：
--  生产需要它们，而两套环境必须保持同一份表结构 —— 索引这种差异是看不见的，
--  连 Hibernate 的 ddl-auto=validate 也不比对索引（只比对表和列），
--  一旦允许两边不一样，就等于把「dev 上好的、prod 上坏的」制度化。
--  第 2、3 条则是两边都缺：那两个列既不是外键，也不是任何唯一约束的前缀。
--
--  为什么新开 V4，而不是按清单里那句「并入 V1」直接改 V1：V1~V3 已经在开发库和线上
--  执行过了，Flyway 记着每个脚本的校验和 —— 改动已执行脚本的任何一个字节，下次启动
--  都会以 "Migration checksum mismatch for migration version 1" 拒绝启动，而唯一的
--  修法是手工去改 flyway_schema_history 表。已经跑过的迁移是历史，只能往后加。
--
--  为什么这次两份脚本逐字相同：CREATE INDEX IF NOT EXISTS 是 PostgreSQL 9.5+ 与 H2
--  都支持的写法（生产用 PG 16），所以没有方言差异要绕。V3 那边不能这么省事，是因为
--  PG 的 ALTER TABLE ... ADD CONSTRAINT 没有 IF NOT EXISTS，只能套匿名块自己判断。
--  IF NOT EXISTS 在这里也不是装饰：手工在库上补过索引的情况是存在的（线上出慢查询、
--  运维先手工 CREATE INDEX 顶一下，代码里的迁移后才发版），没有它，那次启动会直接
--  以 "Index already exists" 失败。
--
--  每条索引对应哪个仓储方法、为什么是这几列，逐条写在下面。**故意没建的两条写在
--  最后** —— 那种「看起来该建」的索引最容易被后人顺手补上，而它们已经是多余的。
--
--  与 postgres/V4__add_hot_path_indexes.sql 一一对应（语句逐字相同，用例守着这一点）。
-- ============================================================================

-- 1) 追番：按用户取「最近更新」的列表
--    TrackingRepository.findByUserOrderByUpdatedAtDesc（含带 Pageable 的重载）
--    → SELECT ... FROM anime_tracking WHERE user_id=? ORDER BY updated_at DESC
--
--    为什么带上 updated_at：清单要的口径就是「(过滤字段, 排序字段)」—— 两个列都进
--    索引，一次索引范围扫描就能拿到有序结果，不必再排一遍。真正带 LIMIT 的只有首页
--    「最近活动」那一处（StatsService:89 的 top 10），其余四处是把该用户的追番全量
--    取出来在内存里算统计 —— 对它们来说省掉的是排序。
--
--    实测（H2, 20000 行, 见 HotPathIndexMigrationTest）：H2 这条查询仍会挑更窄的
--    外键索引或 V3 的唯一索引（三者都以 user_id 打头），不给「省了排序」记功。
--    所以这条索引的价值在带 LIMIT 的 top-N 上，而那是 PostgreSQL 的强项。
--    更重要的是：在 PG 上它是这条查询**唯一**能用的一棵树（外键不建索引），
--    没有它，每次取某个用户的追番列表都是全表扫描。
CREATE INDEX IF NOT EXISTS idx_anime_tracking_user_updated
    ON anime_tracking (user_id, updated_at);

-- 2) 追番：按番剧统计人数
--    StatsService.getAnimeHeat 一次调 5 个 countBySubjectIdAndStatus + 1 个
--    countBySubjectId（详情页的热度条），另有全站热度榜的 GROUP BY subject_id。
--    → WHERE subject_id=? [AND status=?]
--
--    subject_id 既不是外键（没有 REFERENCES，见 V1 的建表说明），也不是 V3 唯一索引
--    (user_id, subject_id) 的前缀（打头的是 user_id）——**两套环境上都没有索引可用**。
CREATE INDEX IF NOT EXISTS idx_anime_tracking_subject
    ON anime_tracking (subject_id);

-- 3) 短评：某部番的评论列表
--    ReviewRepository.findPageBySubjectIdWithUser / findBySubjectIdOrderByCreatedAtDesc
--    → WHERE subject_id=? ORDER BY created_at DESC
--
--    这是 review 表上最热的一条读：详情页每打开一次走一遍，而且是分页取（Pageable
--    会下推成 LIMIT），排序列进索引的价值在这里最大。subject_id 上原本同样什么
--    都没有（理由同上）。
CREATE INDEX IF NOT EXISTS idx_review_subject_created
    ON review (subject_id, created_at);

-- 4) AI 会话：取某个会话的消息
--    AgentMessageRepository.findByConversationOrderByIdAsc / countByConversation /
--    deleteByConversation → WHERE conversation_id=?
--
--    在 PG 上 conversation_id 上一棵树都没有（外键不建索引），打开一个会话就是把
--    整张 agent_message 读一遍；H2 上则有外键自带的 FK_AGENT_MESSAGE_CONVERSATION_INDEX
--    —— 同列、同用途，所以这条在开发环境上是重复的，读上面「为什么一直没人发现」。
CREATE INDEX IF NOT EXISTS idx_agent_message_conversation
    ON agent_message (conversation_id);

-- ============================================================================
--  故意没建的两条（清单里列了，但实测它们已经是多余的）
--
--  注意这两条的理由与 H2 的外键行为**无关** —— 它们靠的是 V3 那两条唯一约束，
--  而唯一约束在 H2 与 PG 上都会建出索引来。所以「不建」这个结论在两套环境上都成立，
--  不存在「dev 上多余、prod 上其实需要」的问题（那正是上面第 1、4 条要建的原因）。
--
--  · review(user_id) —— 清单里给的理由是 findByUserOrderByCreatedAtDesc。
--    实际调用点只有 StatsService.getOverallStats 一处，而且是 .size()：它要的是
--    **条数**，不关心顺序。而「按 user_id 过滤」这件事，V3 的唯一索引
--    uk_review_user_subject (user_id, subject_id) 用最左前缀就能做（EXPLAIN 里走的
--    正是它，见用例）。再建一个打头也是 user_id 的索引不会让这条查询更快，
--    只会在每次写 review 时多维护一棵树。真要排序（将来做「我的短评」列表），
--    该建的是 (user_id, created_at) 而不是 (user_id) —— 写在这里免得下一个人重新猜。
--
--  · episode_watched(user_id, anime_id) —— 清单里给的理由是 countByUser 系列。
--    这张表上的每一个查询，列集都是 (user_id) / (user_id, anime_id) /
--    (user_id, anime_id, episode_num)，而这些**全都是** V3 唯一索引
--    uk_episode_watched_user_anime_episode (user_id, anime_id, episode_num) 的前缀 ——
--    前缀查询走的同一棵树。用例里用 EXPLAIN 把这一点钉住了：
--    "WHERE user_id=? AND anime_id=?" 命中的是那个唯一索引，不是全表扫描；
--    同时钉住「这张表上不该出现我们建的索引」。
--    再补一条 (user_id, anime_id) 是纯粹的写放大（打勾是高频写）。
-- ============================================================================
