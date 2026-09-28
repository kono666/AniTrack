package com.animetracker.dto;

import com.animetracker.dto.RequestDTO.LoginRequest;
import com.animetracker.dto.RequestDTO.RegisterRequest;
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
}
