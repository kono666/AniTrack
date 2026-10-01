package com.animetracker.repository;

import com.animetracker.entity.ReviewReply;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReviewReplyRepository extends JpaRepository<ReviewReply, Long> {

    /**
     * 某条短评下的回复, 按时间正序(这是「楼」的读法: 从上往下按发生顺序读).
     *
     * <p>{@code JOIN FETCH rr.user} 的理由与 {@code ReviewRepository} 那两条相同 ——
     * 每一条都要显示回复人的名字, 不 fetch 就是 N+1. {@code JOIN FETCH rr.review} 则
     * **故意不做**: 列表接口不需要评论本身(它就在上一层的上下文里), 而每条回复都把
     * 整条评论(含 TEXT 正文与作者)读进来是纯浪费.
     *
     * <p><b>ORDER BY 写成三段式, 且整条里不出现 NULL.</b> {@code created_at} 是可空的
     * (V1 那一列的写法), 而 {@code ORDER BY x DESC} 时 NULL 排哪, H2 与 PostgreSQL 正好
     * 相反 —— 这里虽然分页用不上(一条评论下的回复量很小), 但顺序在两个库上必须一致,
     * 否则同一份数据在开发档与线上读出来的楼不一样. 末尾的 {@code rr.id} 让全序成立:
     * 批量写进去的回复时间戳可能撞上, 没有兜底键时两次请求的顺序不定.
     *
     * <p>{@code :epoch} 的值无所谓, 它只是为了让"ORDER BY 里没有 NULL"字面成立
     * (第一键已经把缺值的行分到最后一组), 与 {@code ReviewQueries} 同一写法.
     */
    @Query("SELECT rr FROM ReviewReply rr JOIN FETCH rr.user WHERE rr.review.id = :reviewId"
            + " ORDER BY CASE WHEN rr.createdAt IS NULL THEN 1 ELSE 0 END ASC,"
            + " COALESCE(rr.createdAt, :epoch) ASC, rr.id ASC")
    List<ReviewReply> findReplies(@Param("reviewId") Long reviewId,
                                  @Param("epoch") LocalDateTime epoch,
                                  Pageable pageable);

    // findReceivedReplies 原本在这里(V8)。V11 之后「我收到的回复」由通知表接管, 它
    // 的列表查询在 NotificationRepository 里 —— 那条查询同时还装着"赞了我的评论"
    // 与"赞了我的回复"两类, 而这三类共用一份"未读/排序/分页"的逻辑。

    /**
     * 带作者的回复(编辑与删除之后要把这一条回给前端, 也要判权限).
     *
     * <p>{@code rr.user} 要 fetch: 判"我是不是作者"只需要 id(外键列, 不产生查询), 但
     * 判完还要把这条回复渲染给前端, 那时名字是必须的 —— 分开写就等于同一个查询写两遍.
     *
     * <p>{@code rr.review} 也 fetch: {@link com.animetracker.service.ReviewReplyService}
     * 判删除权限时要问"这条回复挂的那条短评是不是我写的", 那是 {@code review.user_id},
     * 同样只是外键列. 但它同时要用 {@code review.id} 去减 reply_count —— 也只是外键列.
     * fetch 它是因为**没有环境事务**时(这个 service 刻意不带类级 @Transactional)访问
     * 懒加载会炸, 而多读一行评论比在这里赌"用不到"便宜。
     *
     * <p>V14 起多一个 {@code rr.review.deletedAt IS NULL}: 三个调用方(赞回复、编辑回复、
     * 删回复)都是用户侧动作, 而**评论被移除之后它下面的回复跟着一起看不见** ——
     * 回复行本身一条没动(软删碰的是 review 那一行), 所以恢复评论时它们原样回来。
     * 不带这个条件的话, 拿一个猜到的 replyId 仍然能对着一条谁都看不见的回复点赞、
     * 编辑、删除。
     *
     * <p>列表那条 {@link #findReplies} 没有加同样的条件, 因为它的调用方
     * {@code ReviewReplyService.getReplies} 在进门处就问了"这条评论在架上吗" ——
     * 把条件写进那条语句只会给热路径多一个 join, 而那个 join 的每一行都已经判过了。
     */
    @Query("SELECT rr FROM ReviewReply rr JOIN FETCH rr.user JOIN FETCH rr.review"
            + " WHERE rr.id = :id AND rr.review.deletedAt IS NULL")
    Optional<ReviewReply> findByIdWithUser(@Param("id") Long id);

    /** 回复的赞数 +1 / -1, 形状与理由与 {@code ReviewRepository} 那两条赞数逐字相同 */
    @Transactional
    @Modifying
    @Query("UPDATE ReviewReply rr SET rr.likeCount = rr.likeCount + 1 WHERE rr.id = :id")
    void incrementLikeCount(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE ReviewReply rr SET rr.likeCount = rr.likeCount - 1 WHERE rr.id = :id AND rr.likeCount > 0")
    int decrementLikeCount(@Param("id") Long id);

    /** 投影查询把计数读回来(不能 findById —— 批量 UPDATE 绕过持久化上下文, 理由同 ReviewRepository) */
    @Query("SELECT rr.likeCount FROM ReviewReply rr WHERE rr.id = :id")
    Long readLikeCount(@Param("id") Long id);
}
