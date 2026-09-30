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

    /**
     * 「谁回复了我」: 我写的短评下面、**别人**发的回复, 最新的在前.
     *
     * <p>两个条件都不多余: {@code r.user.id = :userId} 是"我写的短评"(而不是"我发的
     * 回复"), {@code rr.user.id <> :userId} 排掉自己回自己的 —— 不排的话, 每自己回一条
     * 就会给自己发一条通知, 这个列表会迅速变成自己跟自己说话的流水账.
     *
     * <p>{@code JOIN FETCH rr.review} 在这里**必须做**: 列表每一项都要给出"是哪条番剧的
     * 哪条评论", 而 {@code Review.user} 是 LAZY 的 —— 不过它指到的只是外键列, 判
     * {@code r.user.id} 不产生查询. 真正要读出来的是 {@code subjectId}.
     *
     * <p>倒序这里用 {@code rr.id DESC} 而不是别处的 {@code ASC}: 这一条不是分页切片
     * (它封顶 30 条, 见 {@code ReviewReplyService.MAX_RECEIVED_SHOWN}), 所以"并列时谁在前"
     * 没有漏行风险, 按 id 倒序与"最新在前"的读法一致.
     */
    @Query("SELECT rr FROM ReviewReply rr JOIN FETCH rr.user JOIN FETCH rr.review r"
            + " WHERE r.user.id = :userId AND rr.user.id <> :userId"
            + " ORDER BY CASE WHEN rr.createdAt IS NULL THEN 1 ELSE 0 END ASC,"
            + " COALESCE(rr.createdAt, :epoch) DESC, rr.id DESC")
    List<ReviewReply> findReceivedReplies(@Param("userId") Long userId,
                                          @Param("epoch") LocalDateTime epoch,
                                          Pageable pageable);

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
     */
    @Query("SELECT rr FROM ReviewReply rr JOIN FETCH rr.user JOIN FETCH rr.review WHERE rr.id = :id")
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
