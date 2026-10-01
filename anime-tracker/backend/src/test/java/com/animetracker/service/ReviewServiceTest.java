package com.animetracker.service;

import com.animetracker.dto.RequestDTO.ReviewRequest;
import com.animetracker.entity.Review;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.ReviewLikeRepository;
import com.animetracker.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 短评写入在「撞上唯一约束」前后的分支, 理由与 {@link TrackServiceTest} 相同.
 *
 * <p>单独再写一遍不是重复劳动: 这两条路径是各自实现的, 谁抄漏了 catch 或漏了重查,
 * 只有各自的用例看得见.
 *
 * <p>接着是一组评论列表的分页参数用例. 分页参数怎么夹, 从返回值上是看不出来的
 * (传 0 和传 1 拿到的都是第一页), 所以那几条断言的是真正下推给仓储的那个 Pageable.
 *
 * <p>末尾是评分统计的用例. 那里的关键是「统计没有把评论读进内存」这一条 ——
 * 内存遍历与数据库聚合算出来的数字完全一样, 只有断言「那个会读实体的方法没被调用」
 * 才分得开这两者.
 */
class ReviewServiceTest {

    private ReviewRepository reviewRepository;
    private ReviewLikeRepository reviewLikeRepository;
    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ReviewRepository.class);
        reviewLikeRepository = mock(ReviewLikeRepository.class);
        reviewService = new ReviewService(reviewRepository, reviewLikeRepository, new IsolatedInsert());
        when(reviewRepository.saveAndFlush(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static User user() {
        return User.builder().id(1L).username("alice").role("USER").status("ACTIVE").build();
    }

    private static ReviewRequest req(int subjectId, int rating, String content) {
        ReviewRequest req = new ReviewRequest();
        req.setSubjectId(subjectId);
        req.setRating(rating);
        req.setContent(content);
        return req;
    }

    @Test
    @DisplayName("没评过: 新建一条")
    void createsRowWhenNoneExists() {
        when(reviewRepository.findByUserAndSubjectId(any(), any())).thenReturn(Optional.empty());

        Review saved = reviewService.saveReview(user(), req(200, 8, "好看"));

        assertThat(saved.getId()).isNull();
        assertThat(saved.getRating()).isEqualTo(8);
        assertThat(saved.getContent()).isEqualTo("好看");
    }

    @Test
    @DisplayName("评过: 覆盖原来那条, 不新增")
    void updatesExistingRowInsteadOfInserting() {
        Review existing = Review.builder().id(7L).subjectId(200).rating(3).content("旧").build();
        when(reviewRepository.findByUserAndSubjectId(any(), any()))
                .thenReturn(Optional.of(existing));

        Review saved = reviewService.saveReview(user(), req(200, 10, "新"));

        assertThat(saved.getId()).isEqualTo(7L);
        assertThat(saved.getRating()).isEqualTo(10);
        assertThat(saved.getContent()).isEqualTo("新");
    }

    @Test
    @DisplayName("并发落败: 插入撞了唯一约束, 回头改对手那行")
    void fallsBackToUpdatingTheWinnersRowOnConflict() {
        Review winner = Review.builder().id(7L).subjectId(200).rating(3).content("对手写的").build();
        when(reviewRepository.findByUserAndSubjectId(any(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenThrow(new DataIntegrityViolationException("uk_review_user_subject"))
                .thenAnswer(inv -> inv.getArgument(0));

        Review saved = reviewService.saveReview(user(), req(200, 10, "我写的"));

        assertThat(saved.getId()).isEqualTo(7L);
        assertThat(saved.getContent()).isEqualTo("我写的");
    }

    @Test
    @DisplayName("插入炸了但重查还是没有: 原样抛出")
    void rethrowsWhenTheConflictWasNotOurs() {
        when(reviewRepository.findByUserAndSubjectId(any(), any())).thenReturn(Optional.empty());
        DataIntegrityViolationException foreign =
                new DataIntegrityViolationException("fk_review_user");
        when(reviewRepository.saveAndFlush(any(Review.class))).thenThrow(foreign);

        assertThatThrownBy(() -> reviewService.saveReview(user(), req(200, 8, "x")))
                .isSameAs(foreign);
    }

    // ========== 评论列表的分页参数 ==========

    private static Review review(Long id, Long userId, String username) {
        return Review.builder().id(id).subjectId(200).rating(8).content("还行")
                .user(User.builder().id(userId).username(username).role("USER").status("ACTIVE").build())
                .build();
    }

    /**
     * 记下这次调用真正传下去的 Pageable, 这是唯一能看出"夹没夹"的地方.
     *
     * <p>用 atLeastOnce + getValue(最后一个): 有两条用例要在同一个方法里连撞两个
     * 越界值, times(1) 会在第二次调用时判定"调用太多"而失败.
     */
    private Pageable capturePageable(int page, int limit) {
        when(reviewRepository.findPageBySubjectIdWithUser(any(), any(), any()))
                .thenReturn(List.of(review(1L, 9L, "bob")));

        reviewService.getSubjectReviews(1L, 200, page, limit, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewRepository, atLeastOnce())
                .findPageBySubjectIdWithUser(any(), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("分页参数原样下推: page/limit 转成 offset 与 size")
    void passesPagingDownToTheRepository() {
        Pageable pageable = capturePageable(3, 15);

        assertThat(pageable.getPageNumber()).as("第 3 页 -> 从 0 数起是 2").isEqualTo(2);
        assertThat(pageable.getOffset()).isEqualTo(30);
        assertThat(pageable.getPageSize()).isEqualTo(15);
    }

    @Test
    @DisplayName("page=0 或负数当成第 1 页, 而不是 PageRequest 直接抛异常")
    void clampsPageToAtLeastOne() {
        // PageRequest.of(-1, n) 会抛 IllegalArgumentException —— 那对外就是一个 500,
        // 而调用方只是页码从 0 开始了
        assertThat(capturePageable(0, 20).getPageNumber()).isZero();
        assertThat(capturePageable(-5, 20).getPageNumber()).isZero();
    }

    @Test
    @DisplayName("limit 超过上限夹到上限, 免得一个请求换走任意大的结果集")
    void clampsLimitToTheMaximum() {
        assertThat(capturePageable(1, 5000).getPageSize())
                .isEqualTo(ReviewService.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("limit 非正数退化成默认页大小, 而不是 1 条")
    void nonPositiveLimitFallsBackToTheDefaultSize() {
        assertThat(capturePageable(1, 0).getPageSize()).isEqualTo(ReviewService.DEFAULT_PAGE_SIZE);
        assertThat(capturePageable(1, -3).getPageSize()).isEqualTo(ReviewService.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("列表项带上作者名与 isOwner, 且不因为批量取作者而变样")
    void mapsAuthorFieldsAndOwnership() {
        when(reviewRepository.findPageBySubjectIdWithUser(any(), any(), any()))
                .thenReturn(List.of(review(1L, 9L, "bob"), review(2L, 1L, "alice")));

        List<Map<String, Object>> result = reviewService.getSubjectReviews(1L, 200, 1, 20, null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsEntry("username", "bob")
                .containsEntry("userId", 9L)
                .containsEntry("isOwner", false);
        assertThat(result.get(1)).containsEntry("isOwner", true);
    }

    @Test
    @DisplayName("一条评论都没有时返回空列表, 不报错")
    void emptyListIsFine() {
        when(reviewRepository.findPageBySubjectIdWithUser(any(), any(), any())).thenReturn(List.of());

        assertThat(reviewService.getSubjectReviews(1L, 200, 1, 20, null)).isEmpty();
    }

    // ========== 评分统计 ==========

    /** 仓储那边 GROUP BY 出来的一行: [分数, 条数]. 类型跟着 Hibernate 走 —— Integer 与 Long. */
    private static Object[] group(int rating, long n) {
        return new Object[]{rating, n};
    }

    private Map<String, Object> statsOf(Object[]... rows) {
        when(reviewRepository.countByRating(any())).thenReturn(List.of(rows));
        return reviewService.getRatingStats(200);
    }

    @Test
    @DisplayName("均分/条数/分布都由分组计数算出来")
    void computesStatsFromGroupedCounts() {
        Map<String, Object> stats = statsOf(group(8, 3), group(9, 1), group(10, 1));

        assertThat(stats).containsOnlyKeys("average", "count", "distribution");
        assertThat(stats.get("count")).isEqualTo(5L);
        // (8*3 + 9 + 10) / 5 = 43 / 5
        assertThat(stats.get("average")).isEqualTo(8.6);

        int[] distribution = (int[]) stats.get("distribution");
        assertThat(distribution).hasSize(10);
        assertThat(distribution[7]).as("8 分 3 条").isEqualTo(3);
        assertThat(distribution[8]).as("9 分 1 条").isEqualTo(1);
        assertThat(distribution[9]).as("10 分 1 条").isEqualTo(1);
        assertThat(distribution[0]).as("1 分没人打").isZero();
        assertThat(Arrays.stream(distribution).sum()).isEqualTo(5);
    }

    /**
     * 这条是本次改动的核心: 统计**不能**再把评论本身读出来.
     *
     * <p>从返回值上看不出差别 —— 两种写法算出的数字一模一样 —— 所以只能断言
     * "那个会把每行评论读成实体的方法一次都没被调用". 少了它, 将来有人把实现改回
     * 内存遍历, 其余用例照样全绿.
     */
    @Test
    @DisplayName("统计不再把该番的评论读进内存, 只问数据库要分组计数")
    void neverLoadsTheReviewsThemselves() {
        statsOf(group(8, 3));

        verify(reviewRepository, never()).findBySubjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(any());
        verify(reviewRepository).countByRating(200);
    }

    @Test
    @DisplayName("没人评分: 均分 0、条数 0、十档全 0, 而不是 500 或 null")
    void emptySubjectGivesZeros() {
        Map<String, Object> stats = statsOf();

        assertThat(stats.get("average")).isEqualTo(0.0);
        assertThat(stats.get("count")).isEqualTo(0L);
        assertThat((int[]) stats.get("distribution")).containsExactly(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("均分保留一位小数, 且是四舍五入 (1.25 -> 1.3)")
    void roundsToOneDecimal() {
        // (1*3 + 2) / 4 = 1.25 —— Math.round(12.5) = 13, 不是截断
        Map<String, Object> stats = statsOf(group(1, 3), group(2, 1));

        assertThat(stats.get("average")).isEqualTo(1.3);
    }

    /**
     * 库里的分数理论上只该是 1~10 (DTO 上有 @Min/@Max), 但注解管不住手工改库和
     * 历史数据, 而表上没有 CHECK 约束. 改前的 distribution[rating - 1] 碰上越界值
     * 直接数组越界 -> 500, 详情页跟着打不开. 这里要求它活下来.
     */
    @Test
    @DisplayName("越界的历史分数不进直方图, 但不会让接口崩, 也仍然算进均分与条数")
    void outOfRangeLegacyRatingSurvives() {
        Map<String, Object> stats = statsOf(group(0, 1), group(8, 2), group(11, 1));

        assertThat(stats.get("count")).as("越界的也算条数").isEqualTo(4L);
        // (0 + 16 + 11) / 4 = 6.75 -> 6.8
        assertThat(stats.get("average")).isEqualTo(6.8);

        int[] distribution = (int[]) stats.get("distribution");
        assertThat(distribution[7]).isEqualTo(2);
        assertThat(Arrays.stream(distribution).sum()).as("只有 1~10 分进直方图").isEqualTo(2);
    }

    // ========== 管理员移除之后(V14 的软删) ==========

    /** 一条被管理员移除过的评论: 行还在, {@code deletedAt} 有值 */
    private static Review removed(Long id) {
        return Review.builder().id(id).subjectId(200).rating(8).content("被移除的")
                .user(User.builder().id(1L).username("alice").role("USER").status("ACTIVE").build())
                .deletedAt(java.time.LocalDateTime.now())
                .build();
    }

    /**
     * <b>被移除的那一条不能被"再发一次"复活。</b>
     *
     * <p>查重那一步刻意不过滤删除标记(理由在仓储那一侧), 所以这条路必然撞上一条已移除的
     * 旧行。放行的后果是: 管理员的处置被用户一个保存动作悄悄撤销, 而账本里那条
     * REVIEW_DELETE 还写着 —— 这类"两边都还在、事情却已经失效"的错最难被发现。
     *
     * <p>{@code never().saveAndFlush} 是必须的: 只断言状态码的话, 一个"先写库再抛异常"
     * 的实现照样绿, 而那条评论已经活过来了。
     */
    @Test
    @DisplayName("重发一条已被管理员移除的评论: 400, 而且一个字都没写进库")
    void refusesToSaveOverARemovedReview() {
        when(reviewRepository.findByUserAndSubjectId(any(), any()))
                .thenReturn(Optional.of(removed(7L)));

        assertThatThrownBy(() -> reviewService.saveReview(user(), req(200, 10, "我改主意了")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该作品的评论已被管理员移除");
        verify(reviewRepository, never()).saveAndFlush(any(Review.class));
    }

    /**
     * 同一条规矩的另一半: 并发落败后回头改**对手那行**时也要判一次。
     *
     * <p>两条路各写了一遍 {@code rejectIfRemoved}, 所以各要一条用例 —— 只测上面那条的话,
     * 漏掉这一个调用点的实现是绿的, 而它正好是最不常走到、也最难在真机上复现的那条。
     */
    @Test
    @DisplayName("并发落败后回头改对手那行, 那行也已被移除: 同样 400")
    void refusesWhenTheConflictWinnerIsRemoved() {
        when(reviewRepository.findByUserAndSubjectId(any(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(removed(7L)));
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenThrow(new DataIntegrityViolationException("uk_review_user_subject"));

        assertThatThrownBy(() -> reviewService.saveReview(user(), req(200, 10, "我写的")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该作品的评论已被管理员移除");
    }

    /**
     * <b>作者自己那条删除是硬删, 所以对一条已被移除的评论必须回 404。</b>
     *
     * <p>放行的话, "用户点了一下删除"就把管理员的处置**永久固化**了 —— 那行真的没了,
     * 恢复接口再也恢复不出来, CASCADE 还会把底下的回复与赞一起带走。而管理员之所以改成
     * 软删, 图的就是"这一步是可撤销的"。
     *
     * <p>{@code never().delete} 是骨干: 404 很容易被一个"先删再抛"的实现凑出来。
     */
    @Test
    @DisplayName("作者删一条已被移除的评论: 404, 而且没有真的删行")
    void authorCannotHardDeleteARemovedReview() {
        when(reviewRepository.findById(7L)).thenReturn(Optional.of(removed(7L)));

        assertThatThrownBy(() -> reviewService.deleteReview(user(), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("评论不存在");
        verify(reviewRepository, never()).delete(any(Review.class));
    }

    /**
     * {@code removed} 这个旗标存在的全部意义: 让「写过但被移除了」与「从没写过」分得开。
     *
     * <p>两者都回 404 或者都回 {@code exists=false} 的话, 详情页会给一个被移除过的用户
     * 摆出一张填好的表单 —— 他点保存, 拿到 400, 而他完全不知道为什么。
     */
    @Test
    @DisplayName("我的评论: 被移除时 exists 与 removed 都是 true; 没写过时 removed 是 false")
    void getUserReviewDistinguishesRemovedFromAbsent() {
        when(reviewRepository.findByUserAndSubjectId(any(), any()))
                .thenReturn(Optional.of(removed(7L)));
        Map<String, Object> mine = reviewService.getUserReview(user(), 200);
        assertThat(mine.get("exists")).isEqualTo(true);
        assertThat(mine.get("removed"))
                .as("这一条不能省 —— 少了它, 前端只能看到一个 exists=true 的正常评论")
                .isEqualTo(true);

        when(reviewRepository.findByUserAndSubjectId(any(), any())).thenReturn(Optional.empty());
        Map<String, Object> none = reviewService.getUserReview(user(), 201);
        assertThat(none.get("exists")).isEqualTo(false);
        assertThat(none.get("removed")).isEqualTo(false);
        assertThat(none).doesNotContainKey("content");
    }
}
