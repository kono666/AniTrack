package com.animetracker.controller;

import com.animetracker.config.AuthRateLimiter;
import com.animetracker.config.CurrentUser;
import com.animetracker.dto.ApiResponse;
import com.animetracker.dto.RequestDTO.*;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.service.ReviewReplyService;
import com.animetracker.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;
    private final AuthRateLimiter authRateLimiter;
    private final ReviewReplyService reviewReplyService;

    public UserController(UserService userService, AuthRateLimiter authRateLimiter,
                          ReviewReplyService reviewReplyService) {
        this.userService = userService;
        this.authRateLimiter = authRateLimiter;
        this.reviewReplyService = reviewReplyService;
    }

    /**
     * 注册.
     *
     * <p>限流检查刻意放在方法体第一行 —— @Valid 在进方法体之前就跑完了, 所以
     * 「请求体不合法」的请求不会被计入配额. 只有真的会走到 BCrypt 的请求才消耗它,
     * 理由详见 {@link AuthRateLimiter} 的类注释.
     *
     * <p>这里用的是 getRemoteAddr(): 反向代理后面要拿到真实访客 IP, 靠的是
     * server.forward-headers-strategy: native(见 application.yml 的 server 块),
     * 由 Tomcat 在进应用之前按 X-Forwarded-For 改写这个返回值. 也就是说
     * 「代理的头可不可信」由容器判断, 这一层只管取用 —— 自己读 X-Forwarded-For
     * 是错的, 那个头客户端随便就能写.
     */
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody RegisterRequest req,
                                                    HttpServletRequest http) {
        authRateLimiter.checkRegister(http.getRemoteAddr());
        return ApiResponse.success("注册成功", userService.register(req));
    }

    /** 登录. 限流位置与理由同 register. */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req,
                                                 HttpServletRequest http) {
        authRateLimiter.checkLogin(http.getRemoteAddr());
        return ApiResponse.success("登录成功", userService.login(req));
    }

    /**
     * 获取用户信息 (管理可查任意, 普通用户只能查自己).
     *
     * <p>两个分支都用 {@code throw BusinessException} 而不是直接 return 一个
     * code 是 401/403 的 ApiResponse —— 区别在**HTTP 状态码**上. 直接返回的那个写法
     * 只把数字写在响应体里, HTTP 状态仍是 200: 前端拦截器看的是状态码, 于是这种
     * 「接口层拒绝」在客户端眼里是一次**成功**(拿到的 data 是 null), curl、
     * 监控、以及任何按状态码判断的调用方也都会当成成功. 抛异常则交给
     * {@link com.animetracker.config.GlobalExceptionHandler}, 状态码与响应体里的 code
     * 一致, 响应体的形状一个字都没变.
     *
     * <p>这也是全项目唯一一处这样写的地方(其余 22 处拒绝都走 BusinessException,
     * 过滤器链上的拒绝本来就是真的 401/403). 改成一致之后, 「没登录」与「没权限」
     * 在 HTTP 这一层与 SecurityConfig 给出的语义就完全对齐了.
     *
     * <p>顺带把两个条件拆开: 未登录与越权是两件事, 混在一个 if 里会让「回 401 还是
     * 403」取决于条件的写法, 而不是取决于发生了什么.
     */
    @GetMapping("/info/{userId}")
    public ApiResponse<Map<String, Object>> getUserInfo(
            @PathVariable Long userId,
            @CurrentUser User currentUser) {
        // 走不到这里: 这个路径不在 SecurityConfig 的免登录名单里, 匿名请求在过滤器链
        // 上就被拦成 401 了. 留着是兜底 —— 万一哪天它被放行, 这里也必须拒绝而不是
        // 因为 currentUser 为 null 而抛 NPE.
        if (currentUser == null) {
            throw BusinessException.unauthorized("未登录");
        }
        // 非管理员只能查自己
        if (!"ADMIN".equals(currentUser.getRole()) && !currentUser.getId().equals(userId)) {
            throw BusinessException.forbidden("无权查看该用户信息");
        }
        User user = userService.getUserById(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("email", user.getEmail());
        data.put("avatar", user.getAvatar());
        data.put("role", user.getRole());
        data.put("status", user.getStatus());
        data.put("createdAt", user.getCreatedAt());
        return ApiResponse.success(data);
    }

    /**
     * 「谁回复了我」: 我写的短评下面、别人发的回复(V8).
     *
     * <p><b>为什么落在 UserController 而不是 ReviewReplyController</b>: 分法按的是
     * **路径前缀的所有权** —— 全仓每个控制器各占一个前缀({@code /api/review}、
     * {@code /api/track}、…), 这个端点是 {@code /api/user/...}, 归这里。数据本身当然
     * 由 {@link ReviewReplyService} 出, 这个类只负责"它挂在哪个地址上"。
     *
     * <p>它**不在** SecurityConfig 的公开清单里, 这是有意的: 这一块答的是"回给我的",
     * 必须知道"我"是谁. 匿名访问在过滤器链上就是 401, 走不到这个方法 —— 下面这个
     * 判空是兜底(与 {@link #getUserInfo} 同一个理由), 不是主要防线。
     *
     * <p>没有 total、没有分页控件、没有已读状态 —— 用户拍板的就是这一档最简版,
     * 封顶条数见 {@code ReviewReplyService.MAX_RECEIVED_SHOWN}。
     */
    @GetMapping("/received-replies")
    public ApiResponse<Map<String, Object>> getReceivedReplies(@CurrentUser User user) {
        if (user == null) {
            throw BusinessException.unauthorized("未登录");
        }
        return ApiResponse.success(reviewReplyService.getReceivedReplies(user));
    }

    /**
     * 改自己的密码.
     *
     * <p><b>响应里回的是一张新 token, 不是一句「修改成功」。</b> 改密会让改密之前签发的
     * token 全部作废({@code JwtAuthFilter.isStaleAfterPasswordChange}), 而当前这台设备
     * 手上那张正是其中之一 —— 只回一句成功, 用户改完密码立刻被登出, 那看起来就是个
     * bug。回一张新的, 当前会话无缝续上, 别处的旧 token 照常失效。前端要把 data.token
     * 存回去, 不存就等于自己把自己登出了。
     *
     * <p>它**不在** SecurityConfig 的公开清单里(与 {@link #getReceivedReplies} 同一条
     * 理由: 要改的是「我」的密码, 必须知道我是谁), 下面判空是兜底不是主防线。
     */
    @PutMapping("/password")
    public ApiResponse<Map<String, Object>> changePassword(@CurrentUser User user,
                                                          @Valid @RequestBody ChangePasswordRequest req) {
        if (user == null) {
            throw BusinessException.unauthorized("未登录");
        }
        return ApiResponse.success("密码修改成功", userService.changePassword(user, req));
    }

    /** 获取当前登录用户信息 */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> getCurrentUser(@CurrentUser User user) {
        if (user == null) {
            return ApiResponse.error("未登录");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("email", user.getEmail());
        data.put("avatar", user.getAvatar());
        data.put("role", user.getRole());
        data.put("status", user.getStatus());
        data.put("createdAt", user.getCreatedAt());
        return ApiResponse.success(data);
    }
}
