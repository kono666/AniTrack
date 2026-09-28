package com.animetracker.dto;

import com.animetracker.dto.RequestDTO.LoginRequest;
import com.animetracker.dto.RequestDTO.RegisterRequest;
import com.animetracker.dto.RequestDTO.ReviewRequest;
import com.animetracker.dto.RequestDTO.TrackRequest;
import com.animetracker.util.UsernamePolicy;
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
import java.util.regex.Pattern;
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

    /**
     * 用户名不再是"任意字符", 只允许 字母/数字/下划线/连字符.
     *
     * <p>这一组的第一条(尾部空格)是这条规则存在的直接原因: 改前
     * "admin " 与 "admin" 是两个不同的字符串, 唯一性检查拦不住,
     * 而在评论列表、用户表、Agent 的回答里它们长得一模一样 ——
     * 一个肉眼分不出真假的账号. 零宽字符(U+200B)是同一招的升级版.
     */
    @ParameterizedTest(name = "非法用户名被拒: {0}")
    @ValueSource(strings = {
            "admin ",            // 尾部空格: 看着与 admin 无异, 却是另一个账号
            " admin",
            "ad min",
            "ad\nmin",
            "ad\tmin",
            "admin@example",     // 长得像邮箱, 但这不是邮箱字段
            "admin/../x",
            "admin<script>",
            "管理员!",            // 中文可以, 标点不行
            "ゆき★",
            "🙂🙂🙂",            // emoji 不在 \p{L} 里 (它们是符号)
    })
    @DisplayName("含空格/换行/标点的用户名一律拒绝")
    void rejectsUsernamesWithUnsafeCharacters(String bad) {
        assertThat(messagesOn(register(bad, "a@x.com", "abcd1234"), "username"))
                .as("用户名 %s 不该通过校验", bad)
                .contains("用户名只能包含中文、字母、数字、下划线和连字符");
    }

    /**
     * 不可见字符这一组用码点拼出来, 不写进源码.
     *
     * <p>理由有两个. 一是可读性: 一个 U+200B 写进字符串字面量之后, 源码里
     * 那一行看起来就是 "admin" 加上一个引号, 谁都看不出在测什么, diff 里也
     * 看不出来. 二是 U+202E(从右向左覆盖符)会把**它后面的源码**倒着显示出来,
     * 那正是 trojan source 那类攻击的做法, 不该出现在自己的测试文件里.
     *
     * <p>其中 U+00A0(不换行空格)值得单独说: 前端提交前会 trim(),
     * 而 Java 的 String.trim() 只去掉 U+0020 及以下, 去不掉它 ——
     * 所以"前端 trim 过了"不能当作这道校验可以省掉的理由.
     */
    @Test
    @DisplayName("零宽字符 / bidi 覆盖符 / 控制字符 / 不换行空格都被拒")
    void rejectsInvisibleCharacters() {
        String[] invisibles = {
                "admin" + (char) 0x200B,   // 零宽空格: 页面上完全不显示
                "admin" + (char) 0x200C,   // 零宽非连接符
                "admin" + (char) 0x202E,   // 从右向左覆盖符: 能把后面的字符反过来显示
                "admin" + (char) 0x0007,   // 控制字符 BEL
                "admin" + (char) 0x00A0,   // 不换行空格: trim() 去不掉它
        };

        for (String bad : invisibles) {
            assertThat(messagesOn(register(bad, "a@x.com", "abcd1234"), "username"))
                    .as("用户名尾部带 U+%04X 时应当被拒", bad.charAt(bad.length() - 1) & 0xFFFF)
                    .contains("用户名只能包含中文、字母、数字、下划线和连字符");
        }
    }

    /**
     * 非拉丁文字必须放行.
     *
     * <p>用 {@code [A-Za-z0-9_]} 之类只认 ASCII 的写法能把上面那组全挡掉,
     * 代价是把中文和日文用户一起挡在门外 —— 对动漫站点来说这个代价太高.
     * 所以规则用的是 {@code \p{L}}(任意语言的字母), 这组用例就是钉住这一点.
     */
    @ParameterizedTest(name = "非 ASCII 用户名放行: {0}")
    @ValueSource(strings = {
            "绫波丽",
            "ゆきこ",          // 注意「ゆき」只有两字, 会先被长度规则拦下, 测不到字符集
            "アニメ好き",
            "한국어이름",
            "Ünïcödé",
            "张三丰",
            "user_01",
            "user-name",
            "abc",
    })
    @DisplayName("中文/日文/韩文/重音字母 等 Unicode 字母都允许")
    void acceptsUnicodeUsernames(String ok) {
        assertThat(messagesOn(register(ok, "a@x.com", "abcd1234"), "username"))
                .as("用户名 %s 应当通过校验", ok)
                .isEmpty();
    }

    /**
     * 长度边界: 50 通过, 51 拒绝.
     *
     * <p>上限对应数据库列宽, 差一位就会从"校验拒掉"退化成"插入时数据库报错",
     * 那时候用户拿到的是一句看不懂的 500.
     */
    @Test
    @DisplayName("用户名长度边界: 50 位通过, 51 位拒绝")
    void enforcesUsernameLengthBoundary() {
        assertThat(messagesOn(register("u".repeat(50), "a@x.com", "abcd1234"), "username")).isEmpty();
        assertThat(messagesOn(register("u".repeat(51), "a@x.com", "abcd1234"), "username"))
                .contains("用户名长度需在 3-50 个字符之间");
        assertThat(messagesOn(register("u".repeat(2), "a@x.com", "abcd1234"), "username"))
                .contains("用户名长度需在 3-50 个字符之间");
    }

    /**
     * 字符集必须整体匹配.
     *
     * <p>先纠正一条容易想当然的因果: 这个"整串"保证**不是** {@code ^}/{@code $}
     * 给的. Bean Validation 的 {@code @Pattern} 底层就是 {@code Matcher.matches()},
     * 也就是不管正则怎么写都要求整串匹配 —— 把 {@code UsernamePolicy.REGEX} 末尾的
     * {@code $} 删掉, 这个类里的用例会**全绿**(变异测试实测过).
     *
     * <p>那锚点还留着做什么? 挡住下一个拿这个常量去用的人: 换成 {@code find()}
     * 语义的话, "admin x" 会从开头匹配出 "admin" 判为合法. 校验器给不了这个保证,
     * 所以下面单独直接测正则本身.
     */
    @Test
    @DisplayName("用户名必须整体匹配, 合法前缀不算数")
    void usernameMustMatchTheWholeString() {
        assertThat(messagesOn(register("admin ", "a@x.com", "abcd1234"), "username")).isNotEmpty();
        assertThat(messagesOn(register("admin x", "a@x.com", "abcd1234"), "username")).isNotEmpty();
        assertThat(messagesOn(register(" admin", "a@x.com", "abcd1234"), "username")).isNotEmpty();

        // 直接钉正则的语义(注解那层已经被 matches() 兜住了, 测不出锚点的作用):
        // 用 find() 也找不出合法前缀, 且不匹配空串 —— 后者是 javadoc 里
        // 「全是空白会落在字符集规则上」那句话的依据
        Pattern policy = Pattern.compile(UsernamePolicy.REGEX);
        assertThat(policy.matcher("admin x").find())
                .as("find() 语义下也不该找出 'admin' 这样的前缀").isFalse();
        assertThat(policy.matcher("").matches())
                .as("REGEX 不匹配空串(用 + 而不是 *)").isFalse();
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
