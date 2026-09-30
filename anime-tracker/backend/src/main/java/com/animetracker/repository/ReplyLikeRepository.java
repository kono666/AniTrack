package com.animetracker.repository;

import com.animetracker.entity.ReplyLike;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 回复的赞. 与 {@link ReviewLikeRepository} 是并列的两套, 不是同一套参数化出来的:
 * 两者的父行不同(短评 / 回复), 级联路径也不同(一级 / 两级), 合成一个泛型仓储只会
 * 把两个父行的 id 混进同一个方法签名里。
 *
 * <p>四个方法的语义与理由都与那个文件逐条对应, 这里不重复解释:
 * 显式 {@code @Modifying @Query} 的删除(而不是派生删除, 后者先 SELECT 再 DELETE
 * 且拿不到"本来有没有"这个答案)、批量取"我赞过哪些"、以及 JOIN FETCH 的名单查询。
 * 差别只有两处: 表名与父行, 以及名单封顶的常量出处。
 */
public interface ReplyLikeRepository extends JpaRepository<ReplyLike, Long> {

    /**
     * 这一条回复我赞过没有.
     *
     * <p>列表里的 {@code likedByMe} 走的是下面那条**批量**查询, 这一条只服务单条的场景
     * (编辑完一条回复要把它的最新状态回给前端)。走的是唯一约束
     * {@code uk_reply_like_reply_user} 那棵树, 不是全表扫描。
     */
    boolean existsByReplyIdAndUserId(Long replyId, Long userId);

    /**
     * 取消回复的赞: 删掉那一行, 返回真正被删的行数(0 或 1).
     *
     * <p>返回值是调用方判断"要不要跟着把计数减一"的唯一依据 —— 减错了就是永久性偏差,
     * 而 {@code reply_like} 上那条唯一约束的最左前缀 {@code reply_id} 同时服务了
     * 「谁赞了这条回复」, 所以这里不需要额外建索引(同 V7 对 {@code review_like} 的判断)。
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM ReplyLike rl WHERE rl.reply.id = :replyId AND rl.user.id = :userId")
    int deleteByReplyIdAndUserId(@Param("replyId") Long replyId, @Param("userId") Long userId);

    /**
     * 这一批回复里, 我赞过哪些 —— 拼 {@code likedByMe} 的**批量**查询, 不是每条一次.
     *
     * <p>调用方必须自己挡掉空集合(理由见 {@code ReviewLikeRepository.findLikedReviewIds}
     * 与 {@code AnimeQueries} 里那个哨兵参数): 空集合传进 JPQL 的 {@code IN} 没有合法写法.
     */
    @Query("SELECT rl.reply.id FROM ReplyLike rl"
            + " WHERE rl.user.id = :userId AND rl.reply.id IN :replyIds")
    List<Long> findLikedReplyIds(@Param("userId") Long userId,
                                 @Param("replyIds") Collection<Long> replyIds);

    /**
     * 谁赞了这条回复, 按点赞时间正序. 取多少条由 Pageable 决定.
     *
     * <p>{@code ORDER BY} 带 {@code rl.id} 兜底的理由同 {@code ReviewLikeRepository.findLikers}.
     */
    @Query("SELECT rl FROM ReplyLike rl JOIN FETCH rl.user WHERE rl.reply.id = :replyId"
            + " ORDER BY rl.createdAt ASC, rl.id ASC")
    List<ReplyLike> findLikers(@Param("replyId") Long replyId, Pageable pageable);
}
