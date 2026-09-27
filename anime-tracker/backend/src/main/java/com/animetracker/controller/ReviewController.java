package com.animetracker.controller;

import com.animetracker.config.CurrentUser;
import com.animetracker.dto.ApiResponse;
import com.animetracker.dto.RequestDTO.ReviewRequest;
import com.animetracker.entity.User;
import com.animetracker.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 添加或更新评论 */
    @PostMapping
    public ApiResponse<Map<String, Object>> saveReview(
            @CurrentUser User user,
            @Valid @RequestBody ReviewRequest req) {
        reviewService.saveReview(user, req);
        Map<String, Object> data = new HashMap<>();
        data.put("subjectId", req.getSubjectId());
        data.put("rating", req.getRating());
        return ApiResponse.success("评论已保存", data);
    }

    /** 删除评论 */
    @DeleteMapping("/{reviewId}")
    public ApiResponse<Void> deleteReview(
            @CurrentUser User user,
            @PathVariable Long reviewId) {
        reviewService.deleteReview(user, reviewId);
        return ApiResponse.success("评论已删除", null);
    }

    /** 获取某番剧的评论列表（未登录也可查看） */
    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> getSubjectReviews(
            @CurrentUser User user,
            @RequestParam Integer subjectId) {
        Long userId = user != null ? user.getId() : 0L;
        return ApiResponse.success(reviewService.getSubjectReviews(userId, subjectId));
    }

    /** 获取番剧评分统计 */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getRatingStats(@RequestParam Integer subjectId) {
        return ApiResponse.success(reviewService.getRatingStats(subjectId));
    }

    /** 获取用户对某番剧的评论 */
    @GetMapping("/my")
    public ApiResponse<Map<String, Object>> getUserReview(
            @CurrentUser User user,
            @RequestParam Integer subjectId) {
        return ApiResponse.success(reviewService.getUserReview(user, subjectId));
    }
}
