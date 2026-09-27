package com.animetracker.controller;

import com.animetracker.config.CurrentUser;
import com.animetracker.dto.ApiResponse;
import com.animetracker.dto.RequestDTO.*;
import com.animetracker.entity.User;
import com.animetracker.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.success("注册成功", userService.register(req));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
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
