package com.animetracker.service;

import com.animetracker.dto.RequestDTO.ReviewRequest;
import com.animetracker.entity.Review;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.ReviewRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final IsolatedInsert isolatedInsert;

    public ReviewService(ReviewRepository reviewRepository, IsolatedInsert isolatedInsert) {
        this.reviewRepository = reviewRepository;
        this.isolatedInsert = isolatedInsert;
    }

    /**
     * 添加或更新评论.
     *
     * 与 {@link TrackService#saveTracking} 同一套写法, 理由也一样:
     * review 表上有 (user_id, subject_id) 唯一约束, 并发下的落败方重查一次改成更新,
     * 而不是把 500 抛给用户. 插入同样要套 {@link IsolatedInsert}, 否则 Agent 工具
     * 那条路上的外层事务会被冲突毒化, 补救全部作废.
     */
    public Review saveReview(User user, ReviewRequest req) {
        Optional<Review> existing = reviewRepository.findByUserAndSubjectId(user, req.getSubjectId());
        if (existing.isPresent()) {
            return applyAndSave(existing.get(), req);
        }
        try {
            return isolatedInsert.attempt(() -> applyAndSave(Review.builder()
                    .user(user)
                    .subjectId(req.getSubjectId())
                    .build(), req));
        } catch (DataIntegrityViolationException e) {
            Review winner = reviewRepository.findByUserAndSubjectId(user, req.getSubjectId())
                    .orElseThrow(() -> e);
            return applyAndSave(winner, req);
        }
    }

    /** 落字段并立刻 flush, 让约束冲突必定在 try 块内抛出. 理由同 TrackService. */
    private Review applyAndSave(Review review, ReviewRequest req) {
        review.setRating(req.getRating());
        review.setContent(req.getContent());
        return reviewRepository.saveAndFlush(review);
    }

    /** 删除评论 */
    public void deleteReview(User user, Long reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> BusinessException.notFound("评论不存在"));
        if (!review.getUser().getId().equals(user.getId())) {
            throw BusinessException.forbidden("无权删除他人评论");
        }
        reviewRepository.delete(review);
    }

    /** 获取某番剧的所有评论 */
    public List<Map<String, Object>> getSubjectReviews(Long userId, Integer subjectId) {
        List<Review> reviews = reviewRepository.findBySubjectIdOrderByCreatedAtDesc(subjectId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Review r : reviews) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.getId());
            map.put("userId", r.getUser().getId());
            map.put("username", r.getUser().getUsername());
            map.put("avatar", r.getUser().getAvatar());
            map.put("rating", r.getRating());
            map.put("content", r.getContent());
            map.put("createdAt", r.getCreatedAt());
            map.put("isOwner", r.getUser().getId().equals(userId));
            result.add(map);
        }
        return result;
    }

    /** 获取番剧评分统计 */
    public Map<String, Object> getRatingStats(Integer subjectId) {
        List<Review> reviews = reviewRepository.findBySubjectIdOrderByCreatedAtDesc(subjectId);
        double avg = reviews.stream().mapToInt(Review::getRating).average().orElse(0);
        long count = reviews.size();

        int[] distribution = new int[10];
        for (Review r : reviews) {
            distribution[r.getRating() - 1]++;
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("average", Math.round(avg * 10.0) / 10.0);
        stats.put("count", count);
        stats.put("distribution", distribution);
        return stats;
    }

    /** 获取用户自己的评论 */
    public Map<String, Object> getUserReview(User user, Integer subjectId) {
        Optional<Review> review = reviewRepository.findByUserAndSubjectId(user, subjectId);
        Map<String, Object> result = new HashMap<>();
        result.put("exists", review.isPresent());
        review.ifPresent(r -> {
            result.put("id", r.getId());
            result.put("rating", r.getRating());
            result.put("content", r.getContent());
            result.put("createdAt", r.getCreatedAt());
        });
        return result;
    }
}
