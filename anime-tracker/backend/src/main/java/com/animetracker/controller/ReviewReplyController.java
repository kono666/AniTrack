package com.animetracker.controller;

import com.animetracker.config.CurrentUser;
import com.animetracker.dto.ApiResponse;
import com.animetracker.dto.RequestDTO.ReplyRequest;
import com.animetracker.entity.User;
import com.animetracker.service.ReviewReplyService;
import com.animetracker.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 短评下的回复, 以及回复的赞.
 *
 * <p><b>为什么路径分成两组前缀({@code /api/review/{id}/replies} 与
 * {@code /api/reply/{id}}).</b> 前一组是"在某条评论下", 它答的是"这条评论有哪些回复";
 * 后一组是"针对某条回复", 而一条回复的 id 已经唯一确定了它, 不需要再带上评论 id ——
 * 带上反而多一个必须与库对上的参数(传错就是操作了别人那条评论下的回复, 或者 404).
 *
 * <p>公开的那几条({@code GET .../replies} 与 {@code GET .../likes})必须出现在
 * {@code SecurityConfig} 的公开清单里, 否则未登录访客看得见评论、一点就 401 ——
 * 那不是权限设计, 是漏配。
 */
@RestController
public class ReviewReplyController {

    private final ReviewReplyService reviewReplyService;

    public ReviewReplyController(ReviewReplyService reviewReplyService) {
        this.reviewReplyService = reviewReplyService;
    }

    /** 某条评论下的回复(未登录也可看) */
    @GetMapping("/api/review/{reviewId}/replies")
    public ApiResponse<List<Map<String, Object>>> getReplies(
            @CurrentUser User user,
            @PathVariable Long reviewId) {
        Long userId = user != null ? user.getId() : ReviewService.ANONYMOUS_USER_ID;
        return ApiResponse.success(reviewReplyService.getReplies(userId, reviewId));
    }

    /** 发一条回复 */
    @PostMapping("/api/review/{reviewId}/replies")
    public ApiResponse<Map<String, Object>> addReply(
            @CurrentUser User user,
            @PathVariable Long reviewId,
            @Valid @RequestBody ReplyRequest req) {
        return ApiResponse.success("回复已发布",
                reviewReplyService.addReply(user, reviewId, req.getContent()));
    }

    /**
     * 改一条回复的正文. 只有作者能改 —— 这一条**不**把权限放宽给评论作者:
     * 删是"我的地盘我做主"(一条辱骂回复不能等人来处理), 改是替别人说话, 性质不同。
     */
    @PutMapping("/api/reply/{replyId}")
    public ApiResponse<Map<String, Object>> editReply(
            @CurrentUser User user,
            @PathVariable Long replyId,
            @Valid @RequestBody ReplyRequest req) {
        return ApiResponse.success("回复已更新",
                reviewReplyService.editReply(user, replyId, req.getContent()));
    }

    /** 删一条回复. 权限三选一: 回复作者 ∪ 评论作者 ∪ 管理员(判据在 service 里) */
    @DeleteMapping("/api/reply/{replyId}")
    public ApiResponse<Void> deleteReply(
            @CurrentUser User user,
            @PathVariable Long replyId) {
        reviewReplyService.deleteReply(user, replyId);
        return ApiResponse.success("回复已删除", null);
    }

    /** 赞一条回复. **幂等**: 已经赞过再点一次仍是 200 + {@code liked=true}, 计数不变 */
    @PostMapping("/api/reply/{replyId}/like")
    public ApiResponse<Map<String, Object>> likeReply(
            @CurrentUser User user,
            @PathVariable Long replyId) {
        return ApiResponse.success("已点赞", reviewReplyService.likeReply(user, replyId));
    }

    /** 取消回复的赞. 同样幂等 */
    @DeleteMapping("/api/reply/{replyId}/like")
    public ApiResponse<Map<String, Object>> unlikeReply(
            @CurrentUser User user,
            @PathVariable Long replyId) {
        return ApiResponse.success("已取消", reviewReplyService.unlikeReply(user, replyId));
    }

    /** 谁赞了这条回复(未登录也可看) */
    @GetMapping("/api/reply/{replyId}/likes")
    public ApiResponse<Map<String, Object>> getReplyLikers(@PathVariable Long replyId) {
        return ApiResponse.success(reviewReplyService.getReplyLikers(replyId));
    }
}
