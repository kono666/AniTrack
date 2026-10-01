package com.animetracker.repository;

import com.animetracker.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内通知的读与写。
 *
 * <p><b>每条查询都带 {@code recipient.id = :recipientId}, 一处例外都没有</b> —— 这不是
 * 顺手加的过滤条件, 而是这张表的访问规则本身: 通知是私事, 任何一条"忘了带收件人"的
 * 查询都是一个越权读。写口只有 {@code NotificationService.record} 一个(三个写入点都
 * 从那里过), 所以这里没有公开的"按 actor 查""按 review 查"之类的口子。
 *
 * <p>取页用的是 {@code JOIN FETCH} 而不是实体映射上的 {@code EAGER}: 列表要显示
 * "谁干的"(actor)、"这是哪条番剧下的评论"(review.subjectId)、以及评论/回复的摘要。
 * 三者都是 to-one, 所以 {@code JOIN FETCH} 与 {@code Pageable} 可以共存 ——
 * 分页遇上集合抓取那套 "firstResult/maxResults specified with collection fetch"
 * 的坑在这里不存在。
 *
 * <p>{@code review} / {@code reply} 用 {@code LEFT JOIN}: 三种类型里 {@code reply_id}
 * 只有两种有(赞评论那条没有), 写成 INNER JOIN 会把整整一类通知从列表里抹掉 ——
 * 而那种错没有任何报错, 只是"我明明收到过赞, 列表里没有"。
 *
 * <p>{@code recipient} 不 fetch: 收件人永远是当前登录的人, 调用方手上就有,
 * 再 JOIN 一次只是白读一行。
 *
 * <p>V14 起还多一条: <b>除了写路径, 每条查询都带 {@link #REVIEW_STILL_VISIBLE}</b>
 * (收件人之外的第二个不变量)。原因与写法都在那个常量上。
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * 「这条通知指向的那条评论还在架上」—— 收件人之外的第二个不变量, 读与写共用。
     *
     * <p><b>为什么通知也要跟着评论的软删走。</b> V11 给 {@code review_id} 挂的是
     * {@code ON DELETE CASCADE}: 评论**硬删**时通知跟着没了。V14 把管理员的删除改成软删,
     * 那条级联从此不再触发, 于是会留下一批指向"用户看不见的评论"的通知 —— 收件人点进去
     * 是一条 404, 而这就是软删必须自己带上读过滤的原因(与 {@code ReviewQueries.ALIVE}
     * 同一条理由)。**恢复评论时它们原样回来**, 因为一行都没删。
     *
     * <p><b>为什么写成 {@code NOT EXISTS} 而不是 {@code LEFT JOIN n.review r}。</b>
     * 写成 join 有两种坏法, 都不报错:
     * <ul>
     *   <li>写成本意上的内连接(或者让 Hibernate 把 {@code n.review.deletedAt} 这条路径
     *       隐式地连成一个内连接): {@code reply_id} 那两类之外还有 {@code REVIEW_LIKE}
     *       与 {@code REPLY_LIKE}, 前者 {@code review_id} 有值、后者为 null —— 内连接会
     *       把**整整一类通知**从列表里抹掉。这个坑类注释里已经记过一次;</li>
     *   <li>{@code markAllRead} 是一条**批量 UPDATE**, 而 JPQL 的 UPDATE 不允许 join
     *       (UPDATE 的 from 子句只有目标实体本身), 想把条件写在那里就只能靠子查询。</li>
     * </ul>
     *
     * <p><b>{@code review_id} 为 null 那一类不需要单独开一个分支</b>, 这正是写成
     * {@code NOT EXISTS} 的好处: {@code n.review.id} 是外键列上的访问(不产生 join,
     * 见 {@code ReviewReportRepository} 里同一句说明), 为空时 {@code r.id = NULL}
     * 恒不成立 → 子查询查不到 → {@code NOT EXISTS} 为真 → 这一行留下。
     * 写成 {@code n.review IS NULL OR n.review.deletedAt IS NULL} 也行, 但那要依赖
     * "Hibernate 会不会为后半句连一个 join", 而那个问题的答案是实现细节。
     *
     * <p>{@code r.deletedAt IS NOT NULL} 用否定式而不是 {@code IS NULL}, 是为了让
     * "null 的外键"与"评论已被移除"两条路合并成同一个判断 —— 反过来说,
     * 这里数的是**被藏起来的**, 不是"活着的"。
     */
    String REVIEW_STILL_VISIBLE =
            "NOT EXISTS (SELECT 1 FROM Review r WHERE r.id = n.review.id AND r.deletedAt IS NOT NULL)";

    /**
     * 与 {@link #findPage} **同一份 WHERE** 的计数。两处的条件必须一起改
     * (两个都带 `recipientId` 与 {@link #REVIEW_STILL_VISIBLE})。
     */
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.recipient.id = :recipientId"
            + " AND " + REVIEW_STILL_VISIBLE)
    long countPage(@Param("recipientId") Long recipientId);

    /**
     * 取一页, 最新的在最上面。
     *
     * <p>第二排序键 {@code id DESC} 不是装饰: {@code created_at} 是应用侧
     * ({@code @PrePersist}) 写进去的, 同一毫秒内落两行完全可能, 而没有稳定序时翻页会
     * 重复或丢行(与 {@code AdminActionLogRepository.findPage} 同一条理由)。
     */
    @Query("SELECT n FROM Notification n JOIN FETCH n.actor "
            + "LEFT JOIN FETCH n.review LEFT JOIN FETCH n.reply "
            + "WHERE n.recipient.id = :recipientId AND " + REVIEW_STILL_VISIBLE + " "
            + "ORDER BY n.createdAt DESC, n.id DESC")
    List<Notification> findPage(@Param("recipientId") Long recipientId, Pageable pageable);

    /**
     * 未读数。红点每次进页面都要问一次, 所以它是一条**只回一个数字**的 COUNT ——
     * 不取行、不 fetch、不排序。
     *
     * <p>它没有自己的索引, 靠的是 {@code idx_notification_recipient_created} 的最左前缀
     * (理由写在 V11 的头部): 未读是"每次查看就清零"的小集合, 收件人这一层已经把它
     * 收窄到一个人的几十行。
     *
     * <p>它必须与 {@link #findPage} 说同一件事({@link #REVIEW_STILL_VISIBLE} 也要带),
     * 否则会出现「列表里一条未读都没有, 导航栏红点却挂着 1」—— 而红点点开是空的。
     * 这个数就是导航栏那个红点的全部依据。
     */
    @Query("SELECT COUNT(n) FROM Notification n "
            + "WHERE n.recipient.id = :recipientId AND n.readAt IS NULL AND " + REVIEW_STILL_VISIBLE)
    long countUnread(@Param("recipientId") Long recipientId);

    /**
     * 把某人的未读全部标成已读。返回真的改了几行 —— 这个数没人显示, 但它是
     * "标记已读"这条路径唯一能被断言的东西(见 {@code NotificationReadTest})。
     *
     * <p>{@code AND n.readAt IS NULL} 不能省: 没有它, 第二次调用会把第一次的已读时间
     * 一起改写, 于是"什么时候读的"变成一个每次打开页面都在变的值。带上它之后这条语句
     * 天然幂等 —— 重复调用改 0 行, 时间戳保持第一次那个。
     *
     * <p>直接 UPDATE 而不是"查出来逐条 set": 未读可能有几十上百条, 逐条就是把它们全
     * 加载进持久化上下文再逐行写回, 而这里要改的只是同一个字段。
     *
     * <p>{@code @Transactional} 是批量 UPDATE 的硬要求(缺了它 Spring Data 直接抛
     * {@code TransactionRequiredException}), 与 {@code ReviewRepository} 里那几条
     * 计数器语句同一个写法。
     *
     * <p>V14 起也带 {@link #REVIEW_STILL_VISIBLE}, 与它标记的那些行(即 {@link #findPage}
     * 看得见的那批)严格是同一批。这样选的好处: 被藏起来的那几条**保持未读**,
     * 于是评论被恢复之后它们以未读的样子回来 —— 用户从没看见过它们, 不该被算成"看过了"。
     * (若这里不过滤, 恢复之后它们显示成已读, 而用户根本不知道自己错过了什么。)
     *
     * <p>返回值里因此会少算被藏起来的那些 —— 这正是要的: 它与 {@link #countUnread}
     * 配成一对, 两个数都只数用户看得见的。
     */
    @Transactional
    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now "
            + "WHERE n.recipient.id = :recipientId AND n.readAt IS NULL AND " + REVIEW_STILL_VISIBLE)
    int markAllRead(@Param("recipientId") Long recipientId, @Param("now") LocalDateTime now);
}
