package com.animetracker.repository;

import com.animetracker.entity.ReviewLike;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface ReviewLikeRepository extends JpaRepository<ReviewLike, Long> {

    /**
     * 点赞行是否存在. 幂等判据, 也用于"我赞过没有"的单条查询.
     *
     * <p>走的是 {@code uk_review_like_review_user} 那棵唯一索引, 不是全表扫描.
     */
    boolean existsByReviewIdAndUserId(Long reviewId, Long userId);

    /**
     * 取消点赞: 删掉那一行, 返回真正被删的行数(0 或 1).
     *
     * <p><b>为什么是显式的 {@code @Modifying @Query} 而不是派生的
     * {@code deleteByReviewIdAndUserId}.</b> 派生删除在 Spring Data JPA 里是
     * 「先 SELECT 出实体、再逐个 DELETE」—— 也就是一次点赞要发**两条**语句
     * (查出一条、删一条), 而且那条 SELECT 会把整行读成实体. 显式 JPQL 是一条语句,
     * 不读实体. 返回值还顺带回答了"本来有没有": 调用方据此决定要不要跟着把计数减一,
     * 减错了就是永久性偏差.
     *
     * <p>没有外层事务时自己开一个 —— 它与 {@code ReviewRepository.decrementLikeCount}
     * 必须落在同一个事务里, 否则会出现"赞取消了、数没减"的半截状态.
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM ReviewLike rl WHERE rl.review.id = :reviewId AND rl.user.id = :userId")
    int deleteByReviewIdAndUserId(@Param("reviewId") Long reviewId, @Param("userId") Long userId);

    /**
     * 这一批短评里, 我赞过哪些 —— 列表页拼 {@code likedByMe} 用的**批量**查询.
     *
     * <p>为什么不是每条评论一次 {@code existsByReviewIdAndUserId}: 那是 N+1,
     * 20 条评论 20 次往返, 而这 20 次的答案就在同一张表里. 一条
     * {@code WHERE user_id = ? AND review_id IN (...)} 全部问完.
     *
     * <p>{@code rl.review.id} 是外键列上的访问, Hibernate 直接读 FK 列, **不会**多出一个
     * join —— 所以它既能放进 IN 的条件里, 也能直接当返回值(不需要把 Review 读出来).
     *
     * <p>调用方必须自己挡掉空集合(见 {@code ReviewService.getSubjectReviews}):
     * 空集合传进来会被 Hibernate 渲染成 {@code 1=0}, 那是它的实现选择而不是语言保证,
     * 与 {@code AnimeQueries} 里那个哨兵参数是同一处讨论.
     */
    @Query("SELECT rl.review.id FROM ReviewLike rl"
            + " WHERE rl.user.id = :userId AND rl.review.id IN :reviewIds")
    List<Long> findLikedReviewIds(@Param("userId") Long userId,
                                  @Param("reviewIds") Collection<Long> reviewIds);

    /**
     * 谁赞了这条短评, 按点赞时间正序. 取多少条由 Pageable 决定(见
     * {@code ReviewLikeService.MAX_LIKERS_SHOWN}).
     *
     * <p>JOIN FETCH 的理由与 {@code ReviewRepository.findPageBySubjectIdWithUser} 相同:
     * 每一条都要读点赞人的用户名, 不 fetch 就是 N+1.
     *
     * <p>排序带上 {@code rl.id} 兜底: {@code created_at} 可空(V1 那一列的写法),
     * 而"{@code ORDER BY x} 时 NULL 排哪"在两个库上不一致 —— 这里虽然不分页,
     * 但同一次点赞批量写进去的行时间戳可能撞上, 没有兜底键时列表顺序在两次请求间不定.
     */
    @Query("SELECT rl FROM ReviewLike rl JOIN FETCH rl.user WHERE rl.review.id = :reviewId"
            + " ORDER BY rl.createdAt ASC, rl.id ASC")
    List<ReviewLike> findLikers(@Param("reviewId") Long reviewId, Pageable pageable);
}
