package com.animetracker.dto;

import com.animetracker.util.PasswordPolicy;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/**
 * 接口入参.
 *
 * 每个校验注解都带 message, 不是可省略的装饰: GlobalExceptionHandler 会把
 * 校验结果直接作为提示语返回给前端展示, 不打 message 就会把
 * 「must not be blank」这种英文默认文案丢到用户脸上.
 */
public class RequestDTO {

    @Data
    public static class RegisterRequest {
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 50, message = "用户名长度需在 3-50 个字符之间")
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

    @Data
    public static class LoginRequest {
        @NotBlank(message = "请输入用户名")
        private String username;
        @NotBlank(message = "请输入密码")
        private String password;
    }

    @Data
    public static class TrackRequest {
        @NotNull(message = "缺少番剧 id")
        private Integer subjectId;
        @NotBlank(message = "缺少追番状态")
        private String status;          // want_to_watch / watching / watched / on_hold / dropped
        private Integer progress;       // 当前观看进度(集数)
        @Min(value = 0, message = "评分不能低于 0")
        @Max(value = 10, message = "评分不能高于 10")
        private Integer score;          // 个人评分
        private String notes;           // 备注
    }

    @Data
    public static class ReviewRequest {
        @NotNull(message = "缺少番剧 id")
        private Integer subjectId;
        @NotNull(message = "请先评分")
        @Min(value = 1, message = "评分不能低于 1")
        @Max(value = 10, message = "评分不能高于 10")
        private Integer rating;
        private String content;
    }

    /** Agent 对话请求 */
    @Data
    public static class AgentChatRequest {
        @NotBlank(message = "提问内容不能为空")
        @Size(max = 4000, message = "提问内容过长")
        private String message;

        /** 人格: user-assistant / admin-analyst, 不传则按用户端处理 */
        private String persona;

        /** 已有会话 id. 登录用户传了它, 历史就以服务端记录为准 */
        private Long conversationId;

        /** 历史消息, 仅未登录访客需要带; 会被 HistorySanitizer 清洗 */
        private List<HistoryItem> history;
    }

    /** 前端回传的历史消息条目 */
    @Data
    public static class HistoryItem {
        private String role;
        private String content;
    }

    @Data
    public static class SearchRequest {
        private String keyword;
        private String type;            // 1=book, 2=anime, 3=music, 4=game, 6=real
        private Integer page = 1;
        @Min(value = 1, message = "每页条数不能小于 1")
        @Max(value = 50, message = "每页条数不能超过 50")
        private Integer limit = 20;
    }
}
