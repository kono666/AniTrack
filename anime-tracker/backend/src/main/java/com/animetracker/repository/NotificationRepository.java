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
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 与 {@link #findPage} **同一份 WHERE** 的计数。两处的条件必须一起改 */
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.recipient.id = :recipientId")
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
            + "WHERE n.recipient.id = :recipientId "
            + "ORDER BY n.createdAt DESC, n.id DESC")
    List<Notification> findPage(@Param("recipientId") Long recipientId, Pageable pageable);

    /**
     * 未读数。红点每次进页面都要问一次, 所以它是一条**只回一个数字**的 COUNT ——
     * 不取行、不 fetch、不排序。
     *
     * <p>它没有自己的索引, 靠的是 {@code idx_notification_recipient_created} 的最左前缀
     * (理由写在 V11 的头部): 未读是"每次查看就清零"的小集合, 收件人这一层已经把它
     * 收窄到一个人的几十行。
     */
    @Query("SELECT COUNT(n) FROM Notification n "
            + "WHERE n.recipient.id = :recipientId AND n.readAt IS NULL")
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
     */
    @Transactional
    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now "
            + "WHERE n.recipient.id = :recipientId AND n.readAt IS NULL")
    int markAllRead(@Param("recipientId") Long recipientId, @Param("now") LocalDateTime now);
}
