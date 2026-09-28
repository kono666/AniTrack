package com.animetracker.controller;

import com.animetracker.config.AuthRateLimiter;
import com.animetracker.config.CurrentUser;
import com.animetracker.dto.ApiResponse;
import com.animetracker.dto.RequestDTO.*;
import com.animetracker.entity.User;
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
     * <p>这里用的是 getRemoteAddr(): 它拿到的是「直连的那一方」. 反向代理后面
     * 那是代理的 IP, 所有访客会共用一份配额 —— 真实访客 IP 要靠 server 的
     * forward-headers-strategy 还原, 那是部署侧的事, 这一层判断不了.
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

    /** 获取用户信息 (管理可查任意, 普通用户只能查自己) */
    @GetMapping("/info/{userId}")
    public ApiResponse<Map<String, Object>> getUserInfo(
            @PathVariable Long userId,
            @CurrentUser User currentUser) {
        // 非管理员只能查自己
        if (currentUser == null || (!"ADMIN".equals(currentUser.getRole()) && !currentUser.getId().equals(userId))) {
            return ApiResponse.forbidden("无权查看该用户信息");
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
