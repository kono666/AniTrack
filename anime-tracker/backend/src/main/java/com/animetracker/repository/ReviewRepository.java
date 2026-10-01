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

/**
 * 短评的仓储.
 *
 * <p><b>V14 起 review 是软删的, 于是这里多了一条纪律: 凡是"读给用户看"的方法都必须带
 * {@code deletedAt IS NULL}</b>, 而漏掉的表现是「被管理员移除的评论又冒出来」——
 * 不报错、不掉用例, 只是有人能重新看见它。判定统一收在 {@link ReviewQueries#ALIVE}
 * 与 {@link #existsByIdAndDeletedAtIsNull} 两处, 每条读路径各有用例钉着;
 * 名字里带 {@code AndDeletedAtIsNull} 也是这个用途: 让过滤条件出现在调用处。
 *
 * <p><b>只有三处刻意不带</b>, 各自都有注释说明, 都别"顺手补齐":
 * {@link #findByUserAndSubjectId}(查重, 带上会让用户再也发不出评论)、
 * {@link #findByIdWithUser} 的两个调用方在冲突分支里用的 {@link #existsById}
 * (问的是"这一行还在不在", 用来分辨外键冲突与唯一冲突)、以及账本自己的读
 * (账本必须活得比目标久 —— 但它读的是另一张表)。
 */
public interface ReviewRepository extends JpaRepository<Review, Long> {

    /**
     * 一个用户对一部番的那一条短评.
     *
     * <p><b>这里刻意不带 {@code deletedAt IS NULL}, 别"顺手补齐"。</b> 它是
     * {@code ReviewService.saveReview} 那条「一个人对一部番只有一条」的查重路径:
     * 带上过滤之后, 被移除过短评的用户再发同一部番, 这里查不到旧行, 于是直接 INSERT,
     * 撞上 {@code uk_review_user_subject}; 冲突重试分支里再查一次还是查不到, 最后抛出去。
     * 用户看到的是「这部番我再也发不了评论」, 而日志里只有一句唯一约束冲突。
     *
     * <p>不过滤之后, 查到一条已移除的行该怎么处理由调用方定: {@code saveReview}
     * 回 400「该作品的评论已被管理员移除」, <b>而不是把旧行复活</b> —— 复活等于
     * 用户自己点一下发布就撤销了管理员的处置。
     */
    Optional<Review> findByUserAndSubjectId(User user, Integer subjectId);

    /**
     * 某部番的全部短评, 按时间倒序. <b>生产代码里没有调用方</b>: 唯一的调用者是
     * {@code AgentAsyncToolExecutionTest}(它要的只是"把那部番的短评取回来"当探针),
     * 以及 {@code ReviewServiceTest} 里一条「评分统计不该走这条路」的哨兵。
     *
     * <p>不过滤的名字里也带着 {@code AndDeletedAtIsNull}: 它是一条公开读路径,
     * 将来谁拿它去渲染列表, 过滤条件已经写在他眼前了。
     */
    List<Review> findBySubjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(Integer subjectId);

    /**
     * 某个用户的全部短评, 按时间倒序. 调用方只有 {@code StatsService.getOverallStats},
     * 在那里是为了数出「我写了几条」。
     *
     * <p>必须过滤: 不过滤的话, 个人中心会显示一个用户自己怎么数都数不出来的数字
     * (被移除的那条他一条也看不到)。
     */
    List<Review> findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(User user);

    /**
     * 某个用户的短评, 按时间倒序取一页 —— 后台用户详情页的「最近评论」.
     *
     * <p><b>含被移除的</b>(语句里没有 {@code deletedAt IS NULL}), 见
     * {@link ReviewQueries#PAGE_USER_CREATED_DESC} 的长注释. 与上面那条的关系是
     * 「口径不同」而不是「要不要分页」, 所以是另一个方法名, 不是重载.
     */
    @Query(ReviewQueries.PAGE_USER_CREATED_DESC)
    List<Review> findPageByUserOrderByCreatedAtDesc(@Param("user") User user,
                                                    @Param("epoch") LocalDateTime epoch,
                                                    Pageable pageable);

    /** 该用户在架的短评数 —— 用户详情页四个计数之一, 与下面的被移除数配成一对 */
    long countByUserAndDeletedAtIsNull(User user);

    /**
     * 该用户被移除的短评数.
     *
     * <p>与在架数分开数、不写成 {@code countByUser} 再相减: 相减要求两个数来自同一瞬间,
     * 而这是两次查询. 详情页上「4 条在架」与「1 条已移除」是要并排显示给人看的,
     * 对不上就会被当成 bug 报回来.
     */
    long countByUserAndDeletedAtIsNotNull(User user);

    /**
     * 在架短评总数 —— 管理端仪表盘上那个数字.
     *
     * <p>为什么不是继承来的 {@code count()}: 那个把被移除的也算进去, 于是仪表盘上
     * 「总评论数」比评论管理页翻得到的行数还多, 而差在哪没有任何地方会说明。
     * 与上面那些读路径是同一条规矩。
     */
    long countByDeletedAtIsNull();

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
     *
     * <p>V14 起多一个 {@code deletedAt IS NULL}: 评分统计与评论列表必须说同一件事,
     * 否则会出现「列表里一条评论都没有, 均分却有 8.5」—— 被移除的短评还在给作品打分。
     */
    @Query("SELECT r.rating, COUNT(r) FROM Review r WHERE r.subjectId = ?1 AND "
            + ReviewQueries.ALIVE + " GROUP BY r.rating")
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
     *
     * <p>V14 起带 {@code deletedAt IS NULL}: 三个调用方(点赞、回复、举报)都是**写路径**,
     * 对一条已被移除的短评动手应该回 404「评论不存在」—— 从用户侧看它确实不存在了。
     * 点赞那条尤其要守: 一条被移除的短评还能涨赞数, 恢复之后赞数就比赞的条数多。
     */
    @Query("SELECT r FROM Review r JOIN FETCH r.user WHERE r.id = :id AND " + ReviewQueries.ALIVE)
    Optional<Review> findByIdWithUser(@Param("id") Long id);

    /**
     * 这条短评**在架上**吗 —— 写路径的守门人.
     *
     * <p>与继承来的 {@code existsById} 只差一个 {@code deletedAt IS NULL}, 但两者要问的
     * 是两个不同的问题, 别当成重复:
     * <ul>
     *   <li>这个(带过滤): 「这条评论还该被操作吗」—— 取消点赞、看回复列表、举报、看举报明细
     *       都问这个;</li>
     *   <li>{@code existsById}(不带): 「这一行还在不在库里」—— 只在冲突分支里用, 用来
     *       分辨「撞了外键(评论没了)」和「撞了唯一约束(本来就举报过)」, 那是个关于
     *       **物理行**的问题, 见 {@code ReviewLikeService.like} 与
     *       {@code ReviewReportService.report} 的 catch 里那两处。</li>
     * </ul>
     *
     * <p>派生方法名里的 {@code DeletedAtIsNull} 跟着字段名走, 与
     * {@link ReviewQueries#ALIVE} 表达同一件事 —— 两条写法的存在只是因为
     * 「JPQL 里拼字符串」和「派生查询」用的不是同一套语法。
     */
    boolean existsByIdAndDeletedAtIsNull(Long id);

    // ==================== 管理端评论列表 ====================
    //
    // 改前这里是**一条** findAllWithUser(Pageable): 无筛选、无排序参数、调用方一律传
    // Pageable.unpaged(), 于是整张 review 表进 JVM 再原样塞进一个 JSON 数组。
    // 那条方法连同它的调用方(AdminService.getAllReviews)这一轮一起删掉了 ——
    // 留着它就是留一把「谁都能把整张评论表读进内存」的枪, 而它已经没有任何调用方。
    //
    // 现在的形状与 UserRepository 那五条一一对应: 一条计数 + 六条取页, 排序字面写在
    // JPQL 里, 所以**下面每个方法的 Pageable 都不带 Sort**。
    //
    // 七个方法的参数表**逐字相同**(keywordPattern / minRating / maxRating / reported),
    // 这不是巧合: 它们共用同一份 WHERE_ADMIN。少传一个参数的话 Hibernate 在建仓 bean
    // 时就会报「参数未绑定」—— 那属于"应用根本起不来"那一类, 拦得住, 但也要知道
    // 是为什么拦住的。
    //
    // reported 是**基本类型**: 见 ReviewQueries.FILTER_REPORTED 里那段说明。

    /**
     * 匹配的评论条数(管理端). 与六条取页共用同一份 WHERE, 见 {@link ReviewQueries#COUNT_ADMIN}.
     *
     * <p>参数没有 {@code :epoch} 之类: 计数不需要排序. 参数名的差异由 {@code ReviewQueries}
     * 里两条语句各自声明, 不是漏写.
     */
    @Query(ReviewQueries.COUNT_ADMIN)
    long countAdminReviews(@Param("keywordPattern") String keywordPattern,
                           @Param("minRating") Integer minRating,
                           @Param("maxRating") Integer maxRating,
                           @Param("reported") boolean reported);

    /** 管理端取页, 默认序: 主键倒序(最新在前). 六条只差 ORDER BY 常量 */
    @Query(ReviewQueries.ADMIN_ID_DESC)
    List<Review> findAdminReviewPageByIdDesc(@Param("keywordPattern") String keywordPattern,
                                             @Param("minRating") Integer minRating,
                                             @Param("maxRating") Integer maxRating,
                                             @Param("reported") boolean reported,
                                             Pageable pageable);

    @Query(ReviewQueries.ADMIN_ID_ASC)
    List<Review> findAdminReviewPageByIdAsc(@Param("keywordPattern") String keywordPattern,
                                            @Param("minRating") Integer minRating,
                                            @Param("maxRating") Integer maxRating,
                                            @Param("reported") boolean reported,
                                            Pageable pageable);

    @Query(ReviewQueries.ADMIN_LIKES_DESC)
    List<Review> findAdminReviewPageByLikesDesc(@Param("keywordPattern") String keywordPattern,
                                                @Param("minRating") Integer minRating,
                                                @Param("maxRating") Integer maxRating,
                                                @Param("reported") boolean reported,
                                                Pageable pageable);

    @Query(ReviewQueries.ADMIN_LIKES_ASC)
    List<Review> findAdminReviewPageByLikesAsc(@Param("keywordPattern") String keywordPattern,
                                               @Param("minRating") Integer minRating,
                                               @Param("maxRating") Integer maxRating,
                                               @Param("reported") boolean reported,
                                               Pageable pageable);

    @Query(ReviewQueries.ADMIN_REPLIES_DESC)
    List<Review> findAdminReviewPageByRepliesDesc(@Param("keywordPattern") String keywordPattern,
                                                  @Param("minRating") Integer minRating,
                                                  @Param("maxRating") Integer maxRating,
                                                  @Param("reported") boolean reported,
                                                  Pageable pageable);

    @Query(ReviewQueries.ADMIN_REPLIES_ASC)
    List<Review> findAdminReviewPageByRepliesAsc(@Param("keywordPattern") String keywordPattern,
                                                 @Param("minRating") Integer minRating,
                                                 @Param("maxRating") Integer maxRating,
                                                 @Param("reported") boolean reported,
                                                 Pageable pageable);
}
