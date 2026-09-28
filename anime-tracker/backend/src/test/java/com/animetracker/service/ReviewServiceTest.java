package com.animetracker.service;

import com.animetracker.dto.RequestDTO.ReviewRequest;
import com.animetracker.entity.Review;
import com.animetracker.entity.User;
import com.animetracker.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 短评写入在「撞上唯一约束」前后的分支, 理由与 {@link TrackServiceTest} 相同.
 *
 * <p>单独再写一遍不是重复劳动: 这两条路径是各自实现的, 谁抄漏了 catch 或漏了重查,
 * 只有各自的用例看得见.
 *
 * <p>末尾另有一组评论列表的分页参数用例. 分页参数怎么夹, 从返回值上是看不出来的
 * (传 0 和传 1 拿到的都是第一页), 所以那几条断言的是真正下推给仓储的那个 Pageable.
 */
class ReviewServiceTest {

    private ReviewRepository reviewRepository;
    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ReviewRepository.class);
        reviewService = new ReviewService(reviewRepository, new IsolatedInsert());
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
        when(reviewRepository.findPageBySubjectIdWithUser(any(), any()))
                .thenReturn(List.of(review(1L, 9L, "bob")));

        reviewService.getSubjectReviews(1L, 200, page, limit);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewRepository, atLeastOnce()).findPageBySubjectIdWithUser(any(), captor.capture());
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
        when(reviewRepository.findPageBySubjectIdWithUser(any(), any()))
                .thenReturn(List.of(review(1L, 9L, "bob"), review(2L, 1L, "alice")));

        List<Map<String, Object>> result = reviewService.getSubjectReviews(1L, 200, 1, 20);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsEntry("username", "bob")
                .containsEntry("userId", 9L)
                .containsEntry("isOwner", false);
        assertThat(result.get(1)).containsEntry("isOwner", true);
    }

    @Test
    @DisplayName("一条评论都没有时返回空列表, 不报错")
    void emptyListIsFine() {
        when(reviewRepository.findPageBySubjectIdWithUser(any(), any())).thenReturn(List.of());

        assertThat(reviewService.getSubjectReviews(1L, 200, 1, 20)).isEmpty();
    }
}
