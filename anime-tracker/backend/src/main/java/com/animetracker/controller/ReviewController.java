package com.animetracker.controller;

import com.animetracker.config.CurrentUser;
import com.animetracker.dto.ApiResponse;
import com.animetracker.dto.RequestDTO.ReviewRequest;
import com.animetracker.entity.User;
import com.animetracker.service.ReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 评论接口.
 *
 * <p>类上这个 @Validated 是下面几个查询参数注解生效的前提 —— 没有它,
 * 方法参数上的 @Min/@Max 会被静默忽略(校验看着写了, 一次都不跑), 与
 * {@link BangumiController} 是同一件事.
 */
@Validated
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

    /**
     * 获取某番剧的评论列表（未登录也可查看）.
     *
     * <p><b>响应结构刻意没动</b>: 仍然是 {@code ApiResponse<List<...>>}, 那个 list 里
     * 每一项的键也一个没变 —— 前端 AnimeDetail.vue 读的就是 {@code res.data.data}
     * 这个数组, 换成 {list,total,page} 会让评论整块白掉. 这是与
     * {@code /api/bangumi/filter} 那次的区别: 那个接口当时还没有任何调用方.
     *
     * <p>代价是响应里没有 total, 前端也就无从知道"后面还有没有". 分页控件与 total
     * 由批次 6 一起加 —— 到那时才会真正改结构, 而且是有前端配合的改.
     *
     * <p>不传分页参数时是第 1 页 20 条. 这一点与改前**不完全等价**: 评论超过 20 条的
     * 番剧, 老调用方会少看到后面的评论(而 /api/review/stats 的 count 仍是总数,
     * 所以这个不一致是看得见的, 不是静默丢数据). 现实里一部番要凑够 20 条不同用户的
     * 评论才会碰到, 而"不封顶地一次倒出全部"是必须先堵上的那个洞.
     */
    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> getSubjectReviews(
            @CurrentUser User user,
            @RequestParam Integer subjectId,
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码从 1 开始") Integer page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页条数不能小于 1")
            @Max(value = 50, message = "每页条数不能超过 50") Integer limit) {
        Long userId = user != null ? user.getId() : 0L;
        return ApiResponse.success(reviewService.getSubjectReviews(userId, subjectId, page, limit));
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
