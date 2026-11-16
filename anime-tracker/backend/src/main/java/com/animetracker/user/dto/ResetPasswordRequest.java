package com.animetracker.user.dto;

import com.animetracker.util.PasswordPolicy;
import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 管理员重置别人的密码.
 *
 * <p>没有 {@code oldPassword} —— 这正是「重置」与「修改」的区别: 管理员是在用户
 * 拿不出旧密码(忘了、账号被盗)时替他换一把。代价是这个端点权限很重, 所以它
 * 在服务端必须留一条账(见 {@code AdminService.resetPassword})。
 */
@Data
public class ResetPasswordRequest {
    @NotBlank(message = "请输入新密码")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
            message = PasswordPolicy.LENGTH_MESSAGE)
    @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
    private String newPassword;
}
