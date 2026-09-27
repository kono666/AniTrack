package com.animetracker.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

public class RequestDTO {

    @Data
    public static class RegisterRequest {
        @NotBlank @Size(min = 3, max = 50)
        private String username;
        @NotBlank @Size(min = 6, max = 100)
        private String password;
        @Email
        private String email;
    }

    @Data
    public static class LoginRequest {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
    }

    @Data
    public static class TrackRequest {
        @NotNull
        private Integer subjectId;
        @NotBlank
        private String status;          // want_to_watch / watching / watched / on_hold / dropped
        private Integer progress;       // 当前观看进度(集数)
        @Min(0) @Max(10)
        private Integer score;          // 个人评分
        private String notes;           // 备注
    }

    @Data
    public static class ReviewRequest {
        @NotNull
        private Integer subjectId;
        @NotNull @Min(1) @Max(10)
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
        @Min(1) @Max(50)
        private Integer limit = 20;
    }
}
