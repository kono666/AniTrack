package com.animetracker.user.dto;

import com.animetracker.user.util.PasswordPolicy;
import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 用户改自己的密码.
 *
 * <p><b>新密码的三个注解与 {@link RegisterRequest#password} 逐字相同</b>, 都引用
 * {@code PasswordPolicy} 的常量而不是抄数值 —— 口令策略只能有一份定义, 否则会出现
 * 「注册时要求 8 位、改密码时只要求 6 位」这种谁也不会去核对的裂缝。
 *
 * <p>{@code oldPassword} 只校验非空, **不校验强度**: 它是用来证明「你是你」的,
 * 而库里可能存着一个按更老的策略设出来的密码。拿新策略去卡它, 只会把那些用户
 * 关在改密码的门外。
 */
@Data
public class ChangePasswordRequest {
    @NotBlank(message = "请输入原密码")
    private String oldPassword;

    @NotBlank(message = "请输入新密码")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
            message = PasswordPolicy.LENGTH_MESSAGE)
    @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
    private String newPassword;
}
