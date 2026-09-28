package com.animetracker.dto;

import com.animetracker.dto.RequestDTO.LoginRequest;
import com.animetracker.dto.RequestDTO.RegisterRequest;
import com.animetracker.dto.RequestDTO.ReviewRequest;
import com.animetracker.dto.RequestDTO.TrackRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注册入参的校验规则测试.
 *
 * 为什么要专门测注解: 校验注解写错了**不会有任何编译错误**, 甚至不会有运行时
 * 报错 —— 少写一条 @Pattern, 接口照样返回 200, 只是弱密码能顺利注册进来.
 * 这种「规则悄悄消失」的问题, 只能靠一条条把规则断言出来才能发现.
 *
 * 这里用的是真的 Validator, 而不是 mock: 要验证的正是「注解本身有没有生效」,
 * 把 Validator 换成 mock 就等于把被测对象一起 mock 掉了.
 */
class RequestDTOValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    private static RegisterRequest register(String username, String email, String password) {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(username);
        req.setEmail(email);
        req.setPassword(password);
        return req;
    }

    /** 取某个字段上的全部提示语 */
    private static Set<String> messagesOn(Object bean, String field) {
        return validator.validate(bean).stream()
                .filter(v -> v.getPropertyPath().toString().equals(field))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    // ========== 合法输入 ==========

    @Test
    @DisplayName("合规的注册入参没有任何违规")
    void acceptsValidRegistration() {
        Set<ConstraintViolation<RegisterRequest>> violations =
                validator.validate(register("alice", "alice@example.com", "abcd1234"));

        assertThat(violations).isEmpty();
    }

    // ========== 密码强度 ==========

    @ParameterizedTest(name = "弱密码被拒: {0}")
    @ValueSource(strings = {
            "abc123",        // 6 位, 太短
            "abc1234",       // 7 位, 还是短
            "abcdefgh",      // 8 位但纯字母
            "12345678",      // 8 位但纯数字
            "abcdefg!",      // 8 位, 有字母有符号但没有数字
            "1234567@",      // 8 位, 有数字有符号但没有字母
            "中文密码一二三四五1234",  // 只有中文和数字 —— 规则要的是 ASCII 字母
    })
    @DisplayName("不满足「8 位且含字母和数字」的密码一律拒绝")
    void rejectsWeakPasswords(String weak) {
        Set<ConstraintViolation<RegisterRequest>> violations =
                validator.validate(register("alice", "alice@example.com", weak));

        assertThat(violations)
                .as("密码 %s 不该通过校验", weak)
                .isNotEmpty();
    }

    @ParameterizedTest(name = "强密码通过: {0}")
    @ValueSource(strings = {
            "abcd1234",
            "12345678a",
            "a1b2c3d4e5",
            "P@ssw0rd!",
            "我的密码abc123",       // 中文打头也可以, 只要里面确实有 ASCII 字母和数字
    })
    @DisplayName("满足规则的密码通过")
    void acceptsStrongPasswords(String strong) {
        assertThat(messagesOn(register("alice", "alice@example.com", strong), "password"))
                .as("密码 %s 应当通过校验", strong)
                .isEmpty();
    }

    /** 边界: 刚好 8 位应当通过, 7 位应当被拒 —— 下界是最容易写错的地方 */
    @Test
    @DisplayName("长度下界: 8 位通过, 7 位拒绝")
    void lengthBoundaryIsExclusiveBelowEight() {
        assertThat(messagesOn(register("alice", "a@x.com", "abcd123"), "password")).isNotEmpty();
        assertThat(messagesOn(register("alice", "a@x.com", "abcd1234"), "password")).isEmpty();
    }

    /**
     * 提示语要能直接展示给用户.
     * 断言的是写死的字符串而不是引用常量, 否则常量一改测试跟着改, 就等于没测.
     */
    @Test
    @DisplayName("过短的密码给出可读的中文提示")
    void givesReadableMessageForShortPassword() {
        assertThat(messagesOn(register("alice", "a@x.com", "abc1"), "password"))
                .contains("密码长度需在 8-100 个字符之间");
    }

    @Test
    @DisplayName("缺字母或缺数字时提示缺什么")
    void givesReadableMessageForMissingCharacterClass() {
        assertThat(messagesOn(register("alice", "a@x.com", "abcdefgh"), "password"))
                .contains("密码必须同时包含字母和数字");
        assertThat(messagesOn(register("alice", "a@x.com", "12345678"), "password"))
                .contains("密码必须同时包含字母和数字");
    }

    @Test
    @DisplayName("密码为空时提示不能为空")
    void rejectsBlankPassword() {
        assertThat(messagesOn(register("alice", "a@x.com", ""), "password"))
                .contains("密码不能为空");
        assertThat(messagesOn(register("alice", "a@x.com", null), "password"))
                .contains("密码不能为空");
    }

    // ========== 邮箱必填 ==========

    /**
     * 邮箱从「选填」改成必填是本轮的需求.
     * 这类改动最容易漏掉的地方就是校验注解 —— 前端把标签从「邮箱（选填）」
     * 改成了「邮箱」, 但后端如果还留着一个没有 @NotBlank 的 @Email,
     * 直接调接口依然能注册出空邮箱账号.
     */
    @Test
    @DisplayName("邮箱为空或只有空白时拒绝")
    void requiresEmail() {
        for (String blank : new String[]{null, "", "   "}) {
            assertThat(messagesOn(register("alice", blank, "abcd1234"), "email"))
                    .as("email = %s 应当被拒", blank)
                    .contains("邮箱不能为空");
        }
    }

    @Test
    @DisplayName("邮箱格式不正确时拒绝")
    void rejectsMalformedEmail() {
        for (String bad : new String[]{"not-an-email", "a@", "@x.com", "a b@x.com"}) {
            assertThat(messagesOn(register("alice", bad, "abcd1234"), "email"))
                    .as("email = %s 应当被拒", bad)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("邮箱过长时拒绝 (数据库列宽 100)")
    void rejectsOverlongEmail() {
        String tooLong = "a".repeat(96) + "@x.com";   // 102 字符

        assertThat(messagesOn(register("alice", tooLong, "abcd1234"), "email"))
                .contains("邮箱不能超过 100 个字符");
    }

    // ========== 用户名 ==========

    @Test
    @DisplayName("用户名过短或为空时拒绝")
    void validatesUsername() {
        assertThat(messagesOn(register("ab", "a@x.com", "abcd1234"), "username"))
                .contains("用户名长度需在 3-50 个字符之间");
        assertThat(messagesOn(register("  ", "a@x.com", "abcd1234"), "username"))
                .contains("用户名不能为空");
    }

    // ========== 登录入参 ==========

    @Test
    @DisplayName("登录只要求两项非空, 不套用注册的强度规则")
    void loginDoesNotReusePasswordStrength() {
        // 这一点很重要: 强度规则如果被套到登录上, 老用户会因为「密码不合新规」
        // 而永远登不进来 —— 密码校验只发生在注册和改密码时.
        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("abc");   // 又短又不含数字

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("登录缺少用户名或密码时给出可读提示")
    void loginRequiresBothFields() {
        LoginRequest req = new LoginRequest();

        assertThat(messagesOn(req, "username")).contains("请输入用户名");
        assertThat(messagesOn(req, "password")).contains("请输入密码");
    }

    // ========== 追番状态白名单 ==========
    //
    // 这一组的存在理由: 状态值以前只在 Agent 工具里校验, 网页接口这一侧完全没有.
    // 于是同一个非法值走 Agent 被拒、走 HTTP 被原样收下, 存进库之后
    // getUserStats 的五个计数里一个都不含它 —— 用户看到的是「追番了但总数没变」,
    // 没有任何一处会报错.

    private static TrackRequest track(Integer subjectId, String status) {
        TrackRequest req = new TrackRequest();
        req.setSubjectId(subjectId);
        req.setStatus(status);
        return req;
    }

    @Test
    @DisplayName("五个合法状态原样通过, 且不多不少")
    void acceptsExactlyTheFiveKnownStatuses() {
        // 断言写死字面量而不是引用常量: 常量一改测试跟着改就等于没测.
        // 少一个值意味着「某个状态再也存不进去」, 多一个值意味着
        // 「一个统计口径不认的状态能存进去」, 两者都要在这里拦住.
        assertThat(TrackRequest.STATUSES)
                .containsExactlyInAnyOrder("want_to_watch", "watching", "watched", "on_hold", "dropped");

        for (String status : TrackRequest.STATUSES) {
            assertThat(messagesOn(track(1, status), "status"))
                    .as("状态 %s 应当通过", status)
                    .isEmpty();
        }
    }

    @ParameterizedTest(name = "非法状态被拒: {0}")
    @ValueSource(strings = {
            "WATCHING",          // 大小写不同就是另一个字符串, 统计口径认的是小写字面量
            "want-to-watch",     // 连字符写成分隔号
            "want to watch",
            "want_to_watch ",    // 尾部空格
            " want_to_watch",
            "watchedX",          // 前缀对了但后面多了东西
            "Xwatched",
            "finished",          // 语义相近但不在口径里
            "0",
    })
    @DisplayName("白名单之外的状态一律拒绝, 而不是原样落库")
    void rejectsUnknownStatuses(String bogus) {
        assertThat(messagesOn(track(1, bogus), "status"))
                .as("状态 %s 不该通过", bogus)
                .contains("追番状态不在允许的取值里");
    }

    /**
     * 前缀/后缀多出字符必须被拒.
     *
     * <p>这条专门盯 @Pattern 的匹配语义: 它要求**整个**字符串匹配正则.
     * 哪天有人把正则写成 ".*want_to_watch.*" 或者改成 find() 语义,
     * 白名单就静默失效了 —— 其它用例仍然全绿, 只有这一条会响.
     */
    @Test
    @DisplayName("状态必须整体匹配, 前后多一个字符都不算白名单内")
    void statusMustMatchTheWholeString() {
        assertThat(messagesOn(track(1, "xwatched"), "status")).isNotEmpty();
        assertThat(messagesOn(track(1, "watchedx"), "status")).isNotEmpty();
        assertThat(messagesOn(track(1, "watchedwatched"), "status")).isNotEmpty();
    }

    @Test
    @DisplayName("状态为空或 null 时给出可读提示, 而不是把 null 写进 NOT NULL 的列")
    void rejectsMissingStatus() {
        for (String blank : new String[]{null, "", "   "}) {
            assertThat(messagesOn(track(1, blank), "status"))
                    .as("status = %s 应当被拒", blank)
                    .contains("缺少追番状态");
        }
    }

    // ========== 追番进度 ==========

    @Test
    @DisplayName("进度不能为负, 但 0 是合法的(还没开始看)")
    void validatesProgress() {
        TrackRequest req = track(1, "watching");

        req.setProgress(-1);
        assertThat(messagesOn(req, "progress")).contains("观看进度不能为负数");

        req.setProgress(0);
        assertThat(messagesOn(req, "progress")).isEmpty();

        req.setProgress(null);
        assertThat(messagesOn(req, "progress")).as("不传进度表示保持不变").isEmpty();
    }

    // ========== 备注长度 ==========

    /**
     * 上限对应数据库那一列的类型. 不封顶的话一次请求就能塞进任意大的字符串,
     * 直接进库、进列表接口、再进 Agent 的上下文窗口.
     */
    @Test
    @DisplayName("备注超过 2000 字符时拒绝, 刚好 2000 通过")
    void validatesNotesLength() {
        TrackRequest req = track(1, "watching");

        req.setNotes("备".repeat(2000));
        assertThat(messagesOn(req, "notes")).isEmpty();

        req.setNotes("备".repeat(2001));
        assertThat(messagesOn(req, "notes")).contains("备注不能超过 2000 个字符");

        req.setNotes(null);
        assertThat(messagesOn(req, "notes")).as("备注是选填的").isEmpty();
    }

    // ========== 评论内容长度 ==========

    private static ReviewRequest review(Integer subjectId, Integer rating, String content) {
        ReviewRequest req = new ReviewRequest();
        req.setSubjectId(subjectId);
        req.setRating(rating);
        req.setContent(content);
        return req;
    }

    @Test
    @DisplayName("评论正文超过 5000 字符时拒绝, 刚好 5000 通过")
    void validatesReviewContentLength() {
        assertThat(messagesOn(review(1, 8, "评".repeat(5000)), "content")).isEmpty();
        assertThat(messagesOn(review(1, 8, "评".repeat(5001)), "content"))
                .contains("评论内容不能超过 5000 个字符");
        assertThat(messagesOn(review(1, 8, null), "content"))
                .as("只打分不写正文是允许的")
                .isEmpty();
    }

    @Test
    @DisplayName("评分必须在 1-10 之间, 且必填")
    void validatesReviewRating() {
        assertThat(messagesOn(review(1, 0, "x"), "rating")).contains("评分不能低于 1");
        assertThat(messagesOn(review(1, 11, "x"), "rating")).contains("评分不能高于 10");
        assertThat(messagesOn(review(1, null, "x"), "rating")).contains("请先评分");
        assertThat(messagesOn(review(1, 10, "x"), "rating")).isEmpty();
        assertThat(messagesOn(review(1, 1, "x"), "rating")).isEmpty();
    }
}
