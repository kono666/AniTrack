package com.animetracker.controller;

import com.animetracker.config.AuthRateLimiter;
import com.animetracker.config.CurrentUser;
import com.animetracker.dto.ApiResponse;
import com.animetracker.dto.RequestDTO.*;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
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

    public UserController(UserService userService, AuthRateLimiter authRateLimiter) {
        this.userService = userService;
        this.authRateLimiter = authRateLimiter;
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
