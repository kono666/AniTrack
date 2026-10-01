package com.animetracker.controller;

import com.animetracker.config.CurrentUser;
import com.animetracker.dto.ApiResponse;
import com.animetracker.dto.RequestDTO.ReportRequest;
import com.animetracker.dto.RequestDTO.ReviewRequest;
import com.animetracker.entity.User;
import com.animetracker.service.ReviewLikeService;
import com.animetracker.service.ReviewReportService;
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
    private final ReviewLikeService reviewLikeService;
    private final ReviewReportService reviewReportService;

    public ReviewController(ReviewService reviewService,
                            ReviewLikeService reviewLikeService,
                            ReviewReportService reviewReportService) {
        this.reviewService = reviewService;
        this.reviewLikeService = reviewLikeService;
        this.reviewReportService = reviewReportService;
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
     *
     * <p><b>{@code sort} 上没有 @Pattern/白名单校验</b>: 未知值由 service 退化成默认序,
     * 不返回 400. 理由写在 ReviewService.getSubjectReviews 上. 这里 {@code required=false},
     * 不传就是默认序 —— 与改前逐字一致的行为.
     */
    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> getSubjectReviews(
            @CurrentUser User user,
            @RequestParam Integer subjectId,
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码从 1 开始") Integer page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页条数不能小于 1")
            @Max(value = 50, message = "每页条数不能超过 50") Integer limit,
            @RequestParam(required = false) String sort) {
        Long userId = user != null ? user.getId() : ReviewService.ANONYMOUS_USER_ID;
        return ApiResponse.success(
                reviewService.getSubjectReviews(userId, subjectId, page, limit, sort));
    }

    /**
     * 点赞. **幂等** —— 已经赞过再点一次, 仍然是 200 + {@code liked=true}, 计数不变.
     *
     * <p><b>为什么是 POST/DELETE 两条, 而不是一个 toggle 端点.</b>
     *
     * <p>toggle 表达的是"翻一下当前状态", 它**不幂等**: 一次点击因为超时重发变成两次
     * 请求时, 结果会被翻回原样, 而两次都返回 200 —— 用户看到自己刚点的赞消失了,
     * 日志和服务端状态却都正常, 这是最难查的一类问题。
     *
     * <p>POST/DELETE 让客户端表达**目标状态**("我要它处于已赞/未赞"), 于是重发安全:
     * 发两次 POST 与发一次 POST 的结果相同。前端的乐观更新也更好写 —— 它知道自己
     * 要去的方向, 不需要先读一次当前状态。
     *
     * @return {@code {liked, likeCount}} —— 新计数由服务端给, 不让前端自己 +1
     */
    @PostMapping("/{reviewId}/like")
    public ApiResponse<Map<String, Object>> likeReview(
            @CurrentUser User user,
            @PathVariable Long reviewId) {
        return ApiResponse.success("已点赞", reviewLikeService.like(user, reviewId));
    }

    /** 取消点赞. 同样幂等: 没赞过再删一次, 返回 200 + {@code liked=false} */
    @DeleteMapping("/{reviewId}/like")
    public ApiResponse<Map<String, Object>> unlikeReview(
            @CurrentUser User user,
            @PathVariable Long reviewId) {
        return ApiResponse.success("已取消", reviewLikeService.unlike(user, reviewId));
    }

    /**
     * 谁赞了这条短评（未登录也可查看）.
     *
     * <p>公开的理由与评论列表一样: 它是评论的附属信息, 而评论列表本身是公开的。
     * <b>这条路径必须出现在 SecurityConfig 的公开清单里</b> —— 否则未登录访客看得见
     * 评论、却一点"谁赞了"就收到 401, 而那不是权限设计, 是漏配。
     *
     * <p>{@code total} 就是该短评的 {@code likeCount}(不另发 COUNT), 所以它与列表上
     * 显示的那个数字必然一致; {@code list} 最多 50 个名字, 见
     * {@code ReviewLikeService.MAX_LIKERS_SHOWN}。
     */
    @GetMapping("/{reviewId}/likes")
    public ApiResponse<Map<String, Object>> getReviewLikers(@PathVariable Long reviewId) {
        return ApiResponse.success(reviewLikeService.getLikers(reviewId));
    }

    /**
     * 举报一条评论（必须登录）.
     *
     * <p><b>这条路刻意不放进 SecurityConfig 的公开清单</b> —— 举报是写, 匿名举报既没有
     * 意义(没人能复核)也是一个现成的灌水入口. {@code anyRequest().authenticated()}
     * 已经兜住它, 不需要额外写一行; 写上去反而是个开口.
     *
     * <p>幂等: 同一个人对同一条评论重复举报回 200 且 {@code duplicate=true}, 不报错.
     * 理由见 {@code ReviewReportService.report}.
     *
     * <p>{@code detail} 是选填的补充说明. 两个字段的白名单/长度校验都在 service 与
     * {@link ReportRequest} 上, 这里只负责把 {@code @Valid} 挂上 —— 少了它,
     * {@code @NotBlank} 会被静默忽略.
     *
     * @return {@code {reported, duplicate}}
     */
    @PostMapping("/{reviewId}/report")
    public ApiResponse<Map<String, Object>> reportReview(
            @CurrentUser User user,
            @PathVariable Long reviewId,
            @Valid @RequestBody ReportRequest request) {
        return ApiResponse.success("已收到举报",
                reviewReportService.report(user, reviewId, request.getReason(), request.getDetail()));
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
