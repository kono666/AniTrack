package com.animetracker.dto;

import com.animetracker.util.PasswordPolicy;
import com.animetracker.util.UsernamePolicy;
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

    @Data
    public static class LoginRequest {
        @NotBlank(message = "请输入用户名")
        private String username;
        @NotBlank(message = "请输入密码")
        private String password;
    }

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
    public static class ChangePasswordRequest {
        @NotBlank(message = "请输入原密码")
        private String oldPassword;

        @NotBlank(message = "请输入新密码")
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
                message = PasswordPolicy.LENGTH_MESSAGE)
        @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
        private String newPassword;
    }

    /**
     * 管理员重置别人的密码.
     *
     * <p>没有 {@code oldPassword} —— 这正是「重置」与「修改」的区别: 管理员是在用户
     * 拿不出旧密码(忘了、账号被盗)时替他换一把。代价是这个端点权限很重, 所以它
     * 在服务端必须留一条账(见 {@code AdminService.resetPassword})。
     */
    @Data
    public static class ResetPasswordRequest {
        @NotBlank(message = "请输入新密码")
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
                message = PasswordPolicy.LENGTH_MESSAGE)
        @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
        private String newPassword;
    }

    @Data
    public static class TrackRequest {

        /**
         * 合法的追番状态, 正则是唯一的源头.
         *
         * 写成字面量而不是用 String.join 从列表拼出来, 是因为 @Pattern 的 regexp
         * 必须是**编译期常量**, 拼出来的不算. 于是反过来让正则当源头, STATUSES 从它
         * 切出来 —— 两处各写一份的话迟早会有一边被改漏, 而漏掉的表现是
         * 「接口放行了一个统计口径和 Agent 都不认的状态」: 数据存进去了,
         * 但 getUserStats 的五个计数里一个都不含它, 用户看到的是「追番了但总数没变」.
         *
         * 以前这份清单只存在于 Agent 工具里(TrackingTools), 网页接口这一侧完全没有 ——
         * 也就是说同一个非法值走 Agent 会被拒、走 HTTP 会被收下.
         */
        public static final String STATUS_REGEX = "want_to_watch|watching|watched|on_hold|dropped";

        public static final List<String> STATUSES = List.of(STATUS_REGEX.split("\\|"));

        @NotNull(message = "缺少番剧 id")
        private Integer subjectId;

        @NotBlank(message = "缺少追番状态")
        @Pattern(regexp = STATUS_REGEX, message = "追番状态不在允许的取值里")
        private String status;

        @Min(value = 0, message = "观看进度不能为负数")
        private Integer progress;       // 当前观看进度(集数)

        @Min(value = 0, message = "评分不能低于 0")
        @Max(value = 10, message = "评分不能高于 10")
        private Integer score;          // 个人评分

        @Size(max = 2000, message = "备注不能超过 2000 个字符")
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

        /**
         * 上限按数据库那一列的类型给: content 是 TEXT/VARCHAR 无界, 不限的话
         * 一次请求就能塞进任意大的字符串, 直接进库、进列表接口、进 Agent 上下文.
         */
        @Size(max = 5000, message = "评论内容不能超过 5000 个字符")
        private String content;
    }

    /**
     * 发一条回复 / 改一条回复 —— 两个接口用同一个请求体, 因为字段完全一样.
     *
     * <p><b>这里比 {@link ReviewRequest} 多一个 @NotBlank, 不是抄漏了.</b> 评论是
     * 「评分 + 可选文字」, 只打分不写字是合法用法(详情页上显示成「（无文字）」);
     * 而回复**只有**文字, 空回复没有任何含义。数据库那一列也是 NOT NULL(V8),
     * 接口层是开口、库是最终防线, 与 README 设计要点 11 同一口径。
     */
    @Data
    public static class ReplyRequest {
        @NotBlank(message = "回复内容不能为空")
        @Size(max = 5000, message = "回复内容不能超过 5000 个字符")
        private String content;
    }

    /**
     * 举报一条评论.
     *
     * <p><b>{@code reason} 这里只用 {@code @NotBlank} 挡空值, 白名单校验在 service 里</b>
     * —— 合法取值是 {@code ReviewReport.REASONS} 那四个, 而 {@code @Pattern} 里再抄一份
     * 就等于同一个约定有两处定义, 改了实体不改 DTO 的话两边会慢慢分叉. 这与
     * {@code TrackRequest.STATUS_REGEX} 那里的取舍**正好相反**: 那个正则之所以敢抄一份,
     * 是因为它同时被前端用来渲染选项; 这里的四个理由是**界面文案**(垃圾广告/辱骂攻击/
     * 剧透/其他), 中英文都要在前端单独写一遍, 抄不到这里来.
     *
     * <p>{@code detail} 上限 500 与建表那一列一致 —— 接口层先拦, 库是最终防线.
     */
    @Data
    public static class ReportRequest {
        @NotBlank(message = "请选择举报理由")
        private String reason;
        @Size(max = 500, message = "补充说明不能超过 500 个字符")
        private String detail;
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
