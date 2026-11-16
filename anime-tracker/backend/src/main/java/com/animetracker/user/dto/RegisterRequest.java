package com.animetracker.user.dto;

import com.animetracker.util.PasswordPolicy;
import com.animetracker.util.UsernamePolicy;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank(message = "用户名不能为空")
    @Size(min = UsernamePolicy.MIN_LENGTH, max = UsernamePolicy.MAX_LENGTH,
            message = UsernamePolicy.LENGTH_MESSAGE)
    @Pattern(regexp = UsernamePolicy.REGEX, message = UsernamePolicy.MESSAGE)
    private String username;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱不能超过 100 个字符")
    private String email;

    @NotBlank(message = "密码不能为空")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
            message = PasswordPolicy.LENGTH_MESSAGE)
    @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
    private String password;
}
