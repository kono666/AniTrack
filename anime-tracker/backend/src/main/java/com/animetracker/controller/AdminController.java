package com.animetracker.controller;

import com.animetracker.config.CurrentUser;
import com.animetracker.dto.ApiResponse;
import com.animetracker.dto.RequestDTO.ResetPasswordRequest;
import com.animetracker.entity.User;
import com.animetracker.service.AdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 后台接口.
 *
 * <p><b>类上的 {@code @Validated} 不是装饰, 少了它下面那两个约束会被静默忽略.</b>
 * 参数级的 {@code @Min}/{@code @Max} 要靠方法级校验生效, 而方法级校验只在被
 * {@code @Validated} 标注过的 bean 上装配 —— 没有它, {@code ?limit=100000} 会一路
 * 走到 service, 接口照样 200, 看起来"约束写了但没起作用".
 * ({@code BangumiController} 的类注释记的是同一件事.)
 *
 * <p>{@code ConstraintViolationException} → 400 已由 {@code GlobalExceptionHandler}
 * 统一处理, 这里不用再加 handler.
 */
@Validated
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /** 仪表盘统计 */
    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> getDashboard(@CurrentUser User user) {
        adminService.checkAdmin(user);
        return ApiResponse.success(adminService.getDashboard());
    }

    /**
     * 用户列表: 关键词 / 角色 / 状态筛选 + 排序 + 分页.
     *
     * <p>返回的 {@code data} 是 {@code {list, total, page}} 而不是裸数组 —— 与
     * {@code /api/anime}、{@code /api/reviews} 的读路径同一个信封. 裸数组改成分页
     * 信封是一次**破坏性**的契约变更, 没有兼容窗口: 前后端同轮发布.
     *
     * <p>五个筛选参数都 {@code required = false}, 且**不做取值校验** —— 不在白名单里的
     * {@code role}/{@code status}/{@code sort} 由 service 当作"不筛"处理, 不返回 400
     * (理由见 {@code AdminService.getUserPage} 的注释). 这里只校验分页数字: 越界的
     * {@code limit} 是个**资源**问题(一次拉十万行), 而写错一个角色名不是.
     *
     * <p>注意 {@code page}/{@code limit} 的默认值写在 {@code defaultValue} 上而不是
     * 靠 {@code int} 的零值: 少了它, 不带分页参数的请求会拿到 {@code page=0},
     * 而 0 会被 {@code @Min(1)} 拦成 400 —— 「不带参数」变成错误是说不通的.
     */
    @GetMapping("/users")
    public ApiResponse<Map<String, Object>> getUserList(
            @CurrentUser User user,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") int limit) {
        adminService.checkAdmin(user);
        return ApiResponse.success(
                adminService.getUserPage(keyword, role, status, sort, order, page, limit));
    }

    /** 禁用/启用用户 */
    @PutMapping("/users/{targetUserId}/toggle")
    public ApiResponse<Void> toggleUser(@CurrentUser User user, @PathVariable Long targetUserId) {
        adminService.checkAdmin(user);
        adminService.toggleUserStatus(user, targetUserId);
        return ApiResponse.success("操作成功", null);
    }

    /**
     * 设置用户角色.
     *
     * 把当前登录的管理员一起传下去, 是为了让「不能改自己的角色」这条规则有判断依据 ——
     * 在 service 里拿不到登录态, 只能由这里递进去.
     *
     * 另外四个破坏性动作(toggle / unlock / resetPassword / deleteReview)现在也照这个形状
     * 把 actor 递下去, 理由从「判断依据」变成了「账本要记是谁按的」。五个一起看: 凡是改动了
     * 别人数据的动作, service 都必须拿到操作者。
     */
    @PutMapping("/users/{targetUserId}/role")
    public ApiResponse<Void> setUserRole(
            @CurrentUser User user,
            @PathVariable Long targetUserId,
            @RequestParam String role) {
        adminService.checkAdmin(user);
        adminService.setUserRole(user, targetUserId, role);
        return ApiResponse.success("角色已更新", null);
    }

    /**
     * 解除用户的登录失败锁定.
     *
     * 用 PUT 而不是 POST: 它是把账号状态改回「未锁定」这个确定状态,
     * 重复调用结果相同, 属于幂等操作.
     */
    @PutMapping("/users/{targetUserId}/unlock")
    public ApiResponse<Void> unlockUser(@CurrentUser User user, @PathVariable Long targetUserId) {
        adminService.checkAdmin(user);
        adminService.unlockUser(user, targetUserId);
        return ApiResponse.success("账号已解锁", null);
    }

    /**
     * 重置某个用户的密码.
     *
     * <p>用 PUT 而不是 POST: 它把「这个人的密码」设成一个确定的新值, 重复调用结果相同
     * (幂等), 与 {@link #unlockUser} 同一条理由。
     *
     * <p><b>没有 {@code oldPassword} 参数, 这是刻意的</b> —— 管理员是在用户拿不出旧密码
     * 时替他换一把, 要求管理员知道旧密码这个东西就没有意义了。代价靠 service 那侧的两条
     * 约束抵掉: 必须记一条 {@code USER_PASSWORD_RESET} 的账, 且不能用来重置自己
     * (理由见 {@code AdminService.resetPassword})。
     */
    @PutMapping("/users/{targetUserId}/password")
    public ApiResponse<Void> resetPassword(@CurrentUser User user,
                                           @PathVariable Long targetUserId,
                                           @Valid @RequestBody ResetPasswordRequest req) {
        adminService.checkAdmin(user);
        adminService.resetPassword(user, targetUserId, req.getNewPassword());
        return ApiResponse.success("密码已重置", null);
    }

    /**
     * 评论列表: 关键词 / 评分档位筛选 + 排序 + 分页.
     *
     * <p><b>返回值从 {@code ApiResponse<List<…>>} 变成了 {@code ApiResponse<Map<String,Object>>}
     * —— 这是一次破坏性契约变更, 没有兼容窗口, 前后端同轮发布。</b> 与
     * {@link #getUserList} 那次是同一件事: 裸数组装不下 total, 而分页控件要靠 total
     * 算页数。改前这一页是"把整张表读进 JVM 再原样吐出来", 用户表两行时看不出问题,
     * 真的几百条评论时它既不能搜、不能筛、不能排序, 而且每删一条都要把整张表重下一次。
     *
     * <p>四个筛选参数都 {@code required = false} 且**不做取值校验** —— 不在白名单里的
     * {@code rating}/{@code sort}/{@code order} 由 service 当作默认值处理, 不返回 400
     * (理由见 {@code AdminService.getUserPage} 的注释)。这里只校验分页数字: 越界的
     * {@code limit} 是个**资源**问题(一次拉十万行), 而写错一个档位键不是。
     *
     * <p>{@code page}/{@code limit} 的默认值写在 {@code defaultValue} 上而不是靠
     * {@code int} 的零值, 理由同 {@link #getUserList}: 少了它, 不带分页参数的请求会拿到
     * {@code page=0}, 被 {@code @Min(1)} 拦成 400 —— 「不带参数」变成错误是说不通的。
     */
    @GetMapping("/reviews")
    public ApiResponse<Map<String, Object>> getReviews(
            @CurrentUser User user,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String rating,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") int limit) {
        adminService.checkAdmin(user);
        return ApiResponse.success(
                adminService.getReviewPage(keyword, rating, sort, order, page, limit));
    }

    /** 删除评论 */
    @DeleteMapping("/reviews/{reviewId}")
    public ApiResponse<Void> deleteReview(@CurrentUser User user, @PathVariable Long reviewId) {
        adminService.checkAdmin(user);
        adminService.deleteAnyReview(user, reviewId);
        return ApiResponse.success("评论已删除", null);
    }

    /**
     * 操作日志: 谁在什么时候对谁做了什么.
     *
     * <p>只有一个 {@code action} 筛选, 没有排序参数 —— 账本只有「最新的在最上面」这一种
     * 读法(理由同 {@code AdminService.getActionPage})。{@code action} 不做取值校验:
     * 不认识的当「不筛」, 理由同 {@code getUserList} 那段注释。
     */
    @GetMapping("/actions")
    public ApiResponse<Map<String, Object>> getActionLog(
            @CurrentUser User user,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") int limit) {
        adminService.checkAdmin(user);
        return ApiResponse.success(adminService.getActionPage(action, page, limit));
    }
}
