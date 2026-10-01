package com.animetracker.repository;

import com.animetracker.entity.ReviewReport;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ReviewReportRepository extends JpaRepository<ReviewReport, Long> {

    /**
     * 这一批评论各自有几条待处理举报、最近那条是什么 —— 管理端列表的**批量**聚合.
     *
     * <p>为什么不是每条评论一次 count: 那是 N+1, 20 行 20 次往返, 而答案全在同一张表里.
     * 一条 {@code review_id IN (...)} 全部问完 —— 与
     * {@link ReviewLikeRepository#findLikedReviewIds} 是同一个形状、同一条理由.
     *
     * <p><b>投影而不是实体 + {@code JOIN FETCH rr.reporter}</b>: 这一页只需要「有几次」
     * 与「最近那个理由」, 举报人只在**明细面板**里才需要 —— 为了一个计数把举报人整行
     * 读进来是白读. 也顺带绕开了「{@code rr.getReview().getId()} 会不会触发一次懒加载」
     * 这个只能靠 Hibernate 实现细节回答的问题({@code rr.review.id} 是外键列上的访问,
     * 不产生 join, 见上面那条).
     *
     * <p>返回 {@code Object[]} 是 {@code {reviewId, reason, createdAt}} 三元组.
     * 按 {@code rr.id DESC} 排, 于是**同一个 reviewId 的第一行就是最近那条** ——
     * 调用方只认第一行, 不需要在 Java 里比时间({@code created_at} 可空, 比时间还得处理
     * null, 而 id 是主键, 天然可比且唯一).
     *
     * <p>只数 {@code PENDING}: 被忽略掉的举报不该继续在列表上显示成一个待办标记.
     *
     * <p>调用方必须自己挡掉空集合 —— 理由同 {@link ReviewLikeRepository#findLikedReviewIds}.
     */
    @Query("SELECT rr.review.id, rr.reason, rr.createdAt FROM ReviewReport rr"
            + " WHERE rr.review.id IN :reviewIds AND rr.status = 'PENDING'"
            + " ORDER BY rr.id DESC")
    List<Object[]> findPendingSummaries(@Param("reviewIds") Collection<Long> reviewIds);

    /**
     * 这条评论的举报明细, 最新的在前. 取多少条由 Pageable 决定(见
     * {@code ReviewReportService.MAX_DETAIL_SHOWN}).
     *
     * <p>两个关联都 fetch: 面板上每一行都要显示「谁举报的」, 「谁处理的」在有处理人时
     * 也要显示 —— 不 fetch 就是两处 N+1.
     *
     * <p>{@code handler} 用 <b>LEFT</b> JOIN: 未处理的行这一列是 null, 内连接会把
     * **未处理的举报整类丢掉** —— 而它们恰恰是这个面板最该显示的那些.
     *
     * <p>{@code reporter} 用内连接是安全的: {@code reporter_id} 是 NOT NULL + 外键.
     */
    @Query("SELECT rr FROM ReviewReport rr JOIN FETCH rr.reporter LEFT JOIN FETCH rr.handler"
            + " WHERE rr.review.id = :reviewId ORDER BY rr.id DESC")
    List<ReviewReport> findDetails(@Param("reviewId") Long reviewId, Pageable pageable);

    /**
     * 这条评论一共有过几条举报(不分状态) —— 明细面板上「共 N 条」那个数.
     *
     * <p>与取明细分开数, 是因为明细**封顶**(见 {@code MAX_DETAIL_SHOWN}): 不单独数一次,
     * 面板上就分不清「就这 50 条」和「有 300 条, 只给你看 50 条」.
     */
    long countByReviewId(Long reviewId);

    // 没有「按 id 取详情」那条自定义查询: dismiss 只需要报告本身与处理人, 而处理人就是
    // 调用方传进来的那个 User(不是从这条报告里读出来的), 所以继承来的 findById 足够。
    // 这里**刻意不加**一条 JOIN FETCH reporter/review 的版本 —— 那两条关联在 dismiss
    // 这条路上一个字段都不读, fetch 过来纯属白读。
}
