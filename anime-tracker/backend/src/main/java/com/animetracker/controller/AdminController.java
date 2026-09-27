package com.animetracker.controller;

import com.animetracker.config.CurrentUser;
import com.animetracker.dto.ApiResponse;
import com.animetracker.entity.User;
import com.animetracker.service.AdminService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

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

    /** 获取所有用户 */
    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> getUserList(@CurrentUser User user) {
        adminService.checkAdmin(user);
        return ApiResponse.success(adminService.getUserList());
    }

    /** 禁用/启用用户 */
    @PutMapping("/users/{targetUserId}/toggle")
    public ApiResponse<Void> toggleUser(@CurrentUser User user, @PathVariable Long targetUserId) {
        adminService.checkAdmin(user);
        adminService.toggleUserStatus(targetUserId);
        return ApiResponse.success("操作成功", null);
    }

    /** 设置用户角色 */
    @PutMapping("/users/{targetUserId}/role")
    public ApiResponse<Void> setUserRole(
            @CurrentUser User user,
            @PathVariable Long targetUserId,
            @RequestParam String role) {
        adminService.checkAdmin(user);
        adminService.setUserRole(targetUserId, role);
        return ApiResponse.success("角色已更新", null);
    }

    /** 获取所有评论 */
    @GetMapping("/reviews")
    public ApiResponse<List<Map<String, Object>>> getAllReviews(@CurrentUser User user) {
        adminService.checkAdmin(user);
        return ApiResponse.success(adminService.getAllReviews());
    }

    /** 删除评论 */
    @DeleteMapping("/reviews/{reviewId}")
    public ApiResponse<Void> deleteReview(@CurrentUser User user, @PathVariable Long reviewId) {
        adminService.checkAdmin(user);
        adminService.deleteAnyReview(reviewId);
        return ApiResponse.success("评论已删除", null);
    }
}
