package com.animetracker.service;

import com.animetracker.dto.RequestDTO.ReviewRequest;
import com.animetracker.entity.Review;
import com.animetracker.entity.User;
import com.animetracker.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 短评写入在「撞上唯一约束」前后的分支, 理由与 {@link TrackServiceTest} 相同.
 *
 * <p>单独再写一遍不是重复劳动: 这两条路径是各自实现的, 谁抄漏了 catch 或漏了重查,
 * 只有各自的用例看得见.
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
}
