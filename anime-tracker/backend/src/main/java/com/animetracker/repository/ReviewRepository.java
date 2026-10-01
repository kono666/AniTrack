package com.animetracker.repository;

import com.animetracker.entity.Review;
import com.animetracker.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    Optional<Review> findByUserAndSubjectId(User user, Integer subjectId);
    List<Review> findBySubjectIdOrderByCreatedAtDesc(Integer subjectId);
    List<Review> findByUserOrderByCreatedAtDesc(User user);
    long countBySubjectId(Integer subjectId);
    long count();

    /**
     * 每个分数各有几条 —— 评分统计用, 返回的每行是 {@code [分数, 条数]}, 最多十行.
     *
     * <p>为什么是聚合而不是把该番的评论取回来在内存里数: 那个写法要走
     * {@link #findBySubjectIdOrderByCreatedAtDesc}, 它把**每一行短评都读成一个实体**
     * (评论正文、时间、作者外键全都读进来), 只为了算三个数字; 而且那句 ORDER BY
     * 是白排的 —— 统计结果与顺序无关. 详情页每打开一次就走一遍这条路,
     * 评论多的番就是白白读几百行.
     *
     * <p>换成 GROUP BY 之后, 数据库只回十行以内的计数, 读进来的实体是 0 个
     * (投影不是实体), 与评论条数无关. 用例数着这两个数(见 QueryCountIntegrationTest).
     */
    @Query("SELECT r.rating, COUNT(r) FROM Review r WHERE r.subjectId = ?1 GROUP BY r.rating")
    List<Object[]> countByRating(Integer subjectId);

    /**
     * 某部番的评论 + 作者, 一次取回; 取多少条由 Pageable 决定. 默认按时间倒序.
     *
     * <p>为什么要 JOIN FETCH: {@code Review.user} 是 LAZY 的, 而列表里每一条都要读
     * 作者的名字和头像. 不 fetch 就是标准的 N+1 —— 20 条评论 = 20 次「按 id 查用户」,
     * 而这 20 次查询的结果就在上一句已经读出来的那几行里.
     *
     * <p>这里用 JOIN FETCH 而不是 @EntityGraph, 只因为查询本来就要写 ORDER BY 和分页,
     * 顺手写在一起, 少一个要跟着改的注解. 两者等价.
     *
     * <p>对 @ManyToOne 做 fetch join 再分页是安全的: 一行评论仍然只对应一行用户,
     * 结果集不会被放大, 所以 limit 是真的下推到 SQL 的. (若是集合关联,
     * Hibernate 就只能把整个结果集读进内存再切页, 并打印 firstResult/maxResults 警告.)
     *
     * <p>语句本身搬去了 {@link ReviewQueries}, 参数也从 {@code ?1} 改成命名参数:
     * 这一轮 review 表上有了两种排序口径, 两条语句要共用同一个 SELECT 片段,
     * 位置参数在两条语句里的含义得靠人去数.
     *
     * @param epoch 只服务于"ORDER BY 里不出现 NULL", 值无所谓(见 {@link ReviewQueries})
     */
    @Query(ReviewQueries.PAGE_CREATED_DESC)
    List<Review> findPageBySubjectIdWithUser(@Param("subjectId") Integer subjectId,
                                             @Param("epoch") LocalDateTime epoch,
                                             Pageable pageable);

    /**
     * 同上, 但按热度(赞数)倒序. 与默认序共用 SELECT 片段, 只有 ORDER BY 不同.
     *
     * <p>为什么热度序不做成"排好序的两个常量数组"或服务层的比较器: 那样会把整部番的
     * 评论全读进内存再切页, 分页就不是下推的了 —— 与
     * {@link #findPageBySubjectIdWithUser} 的注释里那条限制同一个道理.
     *
     * <p><b>代价要说清楚</b>: 排序键 {@code likeCount} 是会变的, 而分页是 OFFSET 切片.
     * 用户停在第 1 页时, 若有人给第 1 页之外的一条评论点了赞, 它可能被顶进第 1 页,
     * 于是翻到第 2 页时会**再看到一次**某条已经在第 1 页出现过的评论(或漏掉一条).
     * 这是 OFFSET 分页 + 可变排序键的固有性质, 不是实现错误; 换成游标分页才能根治,
     * 而那要连前端一起改. 已知, 接受.
     */
    @Query(ReviewQueries.PAGE_HOT_DESC)
    List<Review> findPageBySubjectIdWithUserHot(@Param("subjectId") Integer subjectId,
                                                @Param("epoch") LocalDateTime epoch,
                                                Pageable pageable);

    /**
     * 点赞数 +1, 自增在**数据库里**做.
     *
     * <p>它替换掉的写法是「把 Review 读出来、setLikeCount(+1)、save」—— 那是典型的
     * 丢失更新: 两个请求同时读到 5, 各自写回 6, 两次点赞只记了一次.
     *
     * <p>{@code Review.likeCount} 上有 {@code insertable=false, updatable=false},
     * 所以 JPA 的脏检查**不会**把这一列写回去 —— 这个 UPDATE 是这一列唯一的写入路径.
     * 开第二条之前先看那个字段上的注释(它记的是"改评论正文会把并发点赞抹掉"那条路径).
     *
     * <p>{@code @Transactional} 加在仓储方法上, 与
     * {@link UserRepository#incrementFailedAttempts} 同一形状: 它让这个方法在没有外层
     * 事务时自己开一个, 有外层事务时(点赞走的是 {@code IsolatedInsert})加入那一个 ——
     * 点赞行与计数必须在同一个事务里落地, 否则会出现"赞记下了、数没涨"或反过来的半截状态.
     */
    @Transactional
    @Modifying
    @Query("UPDATE Review r SET r.likeCount = r.likeCount + 1 WHERE r.id = :id")
    void incrementLikeCount(@Param("id") Long id);

    /**
     * 点赞数 -1, 返回真正被改的行数(0 或 1).
     *
     * <p>{@code AND r.likeCount > 0} 是**防负数**, 不是装饰: 并发双删时第二条会匹配
     * 0 行, 计数不会掉到 -1; 而一列允许为负的"计数"没有任何办法自愈 —— 之后每一次
     * 点赞都在把它往 0 拉, 界面上显示的赞数从此长期偏小.
     *
     * <p>返回值也让调用方能区分"真的减了"和"本来就没有" —— 虽然当前的调用点
     * (取消点赞)不需要这个区分, 但把"有没有生效"丢掉, 以后想在它上面加判断
     * 就只能改成先查一次.
     */
    @Transactional
    @Modifying
    @Query("UPDATE Review r SET r.likeCount = r.likeCount - 1 WHERE r.id = :id AND r.likeCount > 0")
    int decrementLikeCount(@Param("id") Long id);

    /**
     * 把点赞数读回来 —— 自增/自减之后要回给前端一个新数字.
     *
     * <p><b>必须是一条投影查询, 不能写成 {@code findById(id).getLikeCount()}。</b>
     * 上面那两个是 JPQL 的批量 UPDATE, 它们**绕过持久化上下文**: 库里已经加过了,
     * 而上下文里那个 Review 实体还是旧值。本项目开着 open-in-view(Spring Boot 的默认),
     * 一次请求共用一个 EntityManager, 于是同一次请求里再 findById 会命中一级缓存、
     * 原样返回那个旧实体 —— 前端就会看到"点击后数字没变", 而库里其实是对的。
     * 投影查询(标量)不经过一级缓存, 每次都真的问库。
     *
     * <p>形状与 {@link UserRepository#readFailedAttempts} 一致(自增之后把计数读回来),
     * 那里也是同一条理由。
     */
    @Query("SELECT r.likeCount FROM Review r WHERE r.id = :id")
    Long readLikeCount(@Param("id") Long id);

    /**
     * 回复数 +1 / -1, 与上面那两条形状逐字相同(V8).
     *
     * <p>分开两条注释没有意义 —— 自增在数据库里做(防丢失更新)、{@code AND r.replyCount > 0}
     * 防负数、{@code Review.replyCount} 上有 {@code insertable/updatable=false} 所以这是
     * 那一列唯一的写入路径, 三条理由与赞数完全一样, 见上面。
     *
     * <p>注意这里**只有回复的增删会动它**: 删一条短评会连带删掉它下面的回复, 但那条短评
     * 本身正在被删除, 它的 reply_count 已经没有读者了 —— 所以删评论的路径不需要(也不该)
     * 去减任何计数。这一点见 {@code Review.replyCount} 的注释。
     */
    @Transactional
    @Modifying
    @Query("UPDATE Review r SET r.replyCount = r.replyCount + 1 WHERE r.id = :id")
    void incrementReplyCount(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE Review r SET r.replyCount = r.replyCount - 1 WHERE r.id = :id AND r.replyCount > 0")
    int decrementReplyCount(@Param("id") Long id);

    /**
     * 一条短评 + 它的作者.
     *
     * <p><b>为什么不是 {@code findById} 然后 {@code r.getUser().getId()}。</b> 那个写法
     * 在多数情况下也能拿到 id —— 但拿到的是**懒加载代理上的 id**, 而这条路径上没有任何
     * 环境事务(互动那三个 service 都刻意不带类级注解), 一旦 Hibernate 决定为了这个 id
     * 去初始化代理, 撞上的就是 {@code LazyInitializationException}。同一个判断在
     * {@link ReviewReplyRepository#findByIdWithUser} 那里已经写过一次, 这里照旧:
     * 多读一行用户, 比在这条"点赞 / 回复"的热路径上赌"它不会初始化"便宜。
     *
     * <p>与 {@code findById} 一样是一次查询, 只是多一个 join —— 所以它不是一笔额外开销,
     * 而是把"取回整行"这件事一次做完。
     *
     * <p>写通知要收件人的 id(评论作者), 这是它唯一的调用方。
     */
    @Query("SELECT r FROM Review r JOIN FETCH r.user WHERE r.id = :id")
    Optional<Review> findByIdWithUser(@Param("id") Long id);

    /**
     * 全部评论 + 作者(管理端).
     *
     * <p>同样是为了避免逐条加载作者. 管理端的分页留到 4.6 —— 这里的量级远小于
     * 用户侧, 而先把 N+1 去掉是这次的目标.
     */
    @Query("SELECT r FROM Review r JOIN FETCH r.user ORDER BY r.createdAt DESC")
    List<Review> findAllWithUser(Pageable pageable);
}
