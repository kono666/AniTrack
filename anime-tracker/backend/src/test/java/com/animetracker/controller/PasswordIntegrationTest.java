package com.animetracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 两条改密路径的对外契约: 用户改自己的({@code PUT /api/user/password})与管理员重置他人的
 * ({@code PUT /api/admin/users/{id}/password}), 以及它们共同的后果 —— <b>改密之前签发的 token
 * 立刻失效</b>。
 *
 * <p><b>为什么这一组必须走 HTTP。</b> "旧 token 失效"这件事横跨三层: 服务端要真的把
 * {@code password_changed_at} 写进库、{@link com.animetracker.config.JwtAuthFilter} 要每次
 * 请求都读它、而 {@code SecurityConfig} 那条 {@code anyRequest().authenticated()} 要把它
 * 变成一个 401。任何一层单独测都测不出这条链 —— 尤其最后那一层: 过滤器里"不认这张 token"
 * 与"回 401"是两件事(见下面 {@link #aStaleTokenIsDeclinedNotExploded} 的注释)。
 *
 * <p><b>关于那个 1.1 秒的休眠。</b> 判断规则的分辨率是**秒**: JWT 的 {@code iat} 只有秒,
 * 而 {@code password_changed_at} 是微秒, 比较时后者被截到秒。于是「改密之前签发的 token
 * 会失效」这句话, 只有当那张 token 落在**更早的那一秒**里才成立 —— 同一秒内签发的旧 token
 * 会活下来(这是该规则明确接受的窗口, 理由写在 {@code JwtAuthFilter})。所以
 * {@link #oldTokenDiesImmediatelyAfterChangingThePassword} 必须先睡过一秒, 否则它会随
 * 执行速度在"同一秒"与"跨秒"之间摇摆, 变成一条随机红的用例。
 *
 * <p><b>那个窗口本身也被单独钉住了</b> —— {@link #aTokenFromTheSameSecondSurvives} 用解出来
 * 的 {@code iat} 反推时刻去摆位, 不含任何休眠, 因此它是确定性的: 它同时证明"同一秒不算过期"
 * 与"下一秒就算过期", 也就是截断那一步到底做没做对。
 *
 * <p>数据自己灌: dev 只有 admin / test 两个账号, 而这里每个用例都要一个可以改密码的用户,
 * 拿真账号当靶子会让用例之间互相干扰(密码改一次就再也登不进去了)。登录按 IP 限流,
 * 这里要反复登录(改密之后必须重新登一次才算验完), 所以把阈值放开 —— 与
 * {@code ReviewReplyIntegrationTest} 同一条做法。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-password;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false",
        "app.security.rate-limit.login-per-minute=1000",
        "app.security.rate-limit.register-per-minute=1000"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PasswordIntegrationTest {

    /** 灌进去的用户名前缀. 清理时用 {@code pw%} —— `_` 在 LIKE 里是通配符, 能用就别用 */
    private static final String PREFIX = "pw_";

    private static final String OLD_PASSWORD = "oldpass123";
    private static final String NEW_PASSWORD = "newpass456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static String adminToken;

    @BeforeEach
    void seedAndLogin() throws Exception {
        jdbc.update("DELETE FROM admin_action_log");
        jdbc.update("DELETE FROM \"user\" WHERE username LIKE 'pw%'");

        if (adminToken == null) {
            adminToken = login("admin", "admin123");
        }
    }

    // ========== 工具 ==========

    /** 灌一个能登录的普通用户, 返回 id */
    private long seedUser(String username) {
        jdbc.update("INSERT INTO \"user\" (username, password, email, role, status, created_at) "
                        + "VALUES (?, ?, ?, 'USER', 'ACTIVE', ?)",
                username, passwordEncoder.encode(OLD_PASSWORD),
                username + "@example.com", Timestamp.valueOf(LocalDateTime.now()));
        return jdbc.queryForObject(
                "SELECT id FROM \"user\" WHERE username = ?", Long.class, username);
    }

    /** 登录并返回 token. 200 是硬断言 —— 一条"登录失败了但后面全都通过"的用例没有意义 */
    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    /** 登录并**不要求**成功: 用来断言"旧密码已经登不进去了" */
    private int loginStatus(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andReturn().getResponse().getStatus();
    }

    private ResultActions changePassword(String token, String oldPassword, String newPassword)
            throws Exception {
        return mockMvc.perform(put("/api/user/password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("oldPassword", oldPassword, "newPassword", newPassword))));
    }

    private ResultActions adminReset(String token, long targetUserId, String newPassword)
            throws Exception {
        return mockMvc.perform(put("/api/admin/users/" + targetUserId + "/password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("newPassword", newPassword))));
    }

    /** 这张 token 现在还能不能换到身份 —— 用 {@code /api/user/me} 探一下 */
    private int meStatus(String token) throws Exception {
        return mockMvc.perform(get("/api/user/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    private String json(Map<String, String> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private String passwordChangedAtOf(String username) {
        return jdbc.queryForObject("SELECT password_changed_at FROM \"user\" WHERE username = ?",
                String.class, username);
    }

    /**
     * 把这一行的 {@code password_changed_at} 摆到某个确定时刻.
     *
     * <p>换算用 {@link ZoneId#systemDefault()} —— 与 {@code JwtAuthFilter} 里
     * {@code changedAt.atZone(ZoneId.systemDefault())} 是同一个口径。两边若用了不同的时区,
     * 下面那两条断言会以"差几个小时"的形式错开, 而不是简单地红。
     */
    private void setPasswordChangedAt(String username, Instant when) {
        jdbc.update("UPDATE \"user\" SET password_changed_at = ? WHERE username = ?",
                Timestamp.valueOf(LocalDateTime.ofInstant(when, ZoneId.systemDefault())), username);
    }

    /**
     * 解开 token 的 payload, 取出 {@code iat}(秒).
     *
     * <p>这一步在测试里是必要的而不是炫技: 那条规则的分辨率就是秒, 要**确定性地**验证它
     * 边界在哪, 就只能知道这张 token 落在哪一秒里。补齐 base64url 的 padding 是因为
     * JWT 段是不带 padding 的, 而 JDK 的解码器不保证接受缺 padding 的输入。
     */
    private long issuedAtOf(String token) throws Exception {
        String segment = token.split("\\.")[1];
        String padded = segment + "=".repeat((4 - segment.length() % 4) % 4);
        String payload = new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
        return objectMapper.readTree(payload).path("iat").asLong();
    }

    // ========== 入口: 匿名与越权 ==========

    @Test
    @DisplayName("匿名改密: 401")
    void anonymousCannotChangePassword() throws Exception {
        mockMvc.perform(put("/api/user/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("oldPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("匿名重置他人: 401")
    void anonymousCannotResetAnyone() throws Exception {
        mockMvc.perform(put("/api/admin/users/1/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("newPassword", NEW_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 普通用户调管理员那条路: 403, <b>而且目标一个字节都没变</b>。
     *
     * <p>只断言状态码是不够的: 一个"先改密码、再抛 403"的实现同样回 403, 而那时整个账号
     * 已经被接管了。所以这里必须再验一次那个人还能用原密码登录。
     */
    @Test
    @DisplayName("普通用户重置他人: 403, 且目标密码没被改")
    void nonAdminCannotResetOthers() throws Exception {
        long target = seedUser(PREFIX + "victim");
        String token = login(PREFIX + "victim", OLD_PASSWORD);

        adminReset(token, target, NEW_PASSWORD).andExpect(status().isForbidden());

        assertThat(loginStatus(PREFIX + "victim", OLD_PASSWORD)).isEqualTo(200);
        assertThat(loginStatus(PREFIX + "victim", NEW_PASSWORD)).isEqualTo(400);
        assertThat(passwordChangedAtOf(PREFIX + "victim")).isNull();
    }

    // ========== 用户改自己的 ==========

    /**
     * 旧密码不对 → 400, 而且**密码没被改**。
     *
     * <p>「没被改」那一半是这条用例的全部价值: 密码列改坏了不会报错, 只会让用户再也
     * 登不进来 —— 而他手上只有那个已经不管用的旧密码。
     */
    @Test
    @DisplayName("原密码不正确: 400, 旧密码仍然能登录")
    void rejectsWrongOldPassword() throws Exception {
        seedUser(PREFIX + "alice");
        String token = login(PREFIX + "alice", OLD_PASSWORD);

        changePassword(token, "not-my-password", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("原密码不正确"));

        assertThat(loginStatus(PREFIX + "alice", OLD_PASSWORD)).isEqualTo(200);
        assertThat(passwordChangedAtOf(PREFIX + "alice")).isNull();
    }

    /** 新密码也要过同一份 PasswordPolicy —— 两道各来一次(太短、没有数字) */
    @Test
    @DisplayName("新密码不满足策略: 400, 且密码没被改")
    void rejectsWeakNewPassword() throws Exception {
        seedUser(PREFIX + "bob");
        String token = login(PREFIX + "bob", OLD_PASSWORD);

        changePassword(token, OLD_PASSWORD, "abc123")
                .andExpect(status().isBadRequest());
        changePassword(token, OLD_PASSWORD, "abcdefgh")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("密码必须同时包含字母和数字"));

        assertThat(loginStatus(PREFIX + "bob", OLD_PASSWORD)).isEqualTo(200);
    }

    /**
     * 成功那一路: 旧密码换掉、新密码能用、<b>响应里那张新 token 立刻能用</b>。
     *
     * <p>最后一条不是锦上添花: 改密会把当前设备手上那张 token 一起作废, 不回新的就等于
     * "改完密码立刻被登出"。少了它, 一个"改密后把所有人(包括本人)踢下线"的实现
     * 也能让下面那条"旧 token 失效"通过。
     *
     * <p><b>注意这里没有断言"新 token 与旧 token 不是同一串"</b> —— 它很可能逐字相同, 而
     * 那不是 bug。JWT 是无状态的: 同一秒内、同一个用户签出来的 payload 完全一样, 于是
     * HMAC 也一样。真正要验的是**它还算数**(下面那条 200), 而不是它是另一串字符。
     */
    @Test
    @DisplayName("改密成功: 回一张立刻可用的新 token; 新密码能登录, 旧密码不能")
    void changingThePasswordSwapsThePasswordAndKeepsYouSignedIn() throws Exception {
        seedUser(PREFIX + "carol");
        String oldToken = login(PREFIX + "carol", OLD_PASSWORD);

        String body = changePassword(oldToken, OLD_PASSWORD, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("密码修改成功"))
                .andReturn().getResponse().getContentAsString();
        String freshToken = objectMapper.readTree(body).path("data").path("token").asText();

        assertThat(freshToken).isNotBlank();
        assertThat(meStatus(freshToken))
                .as("响应里那张新 token 必须立刻可用, 否则用户改完密码就被自己踢下线了")
                .isEqualTo(200);
        assertThat(loginStatus(PREFIX + "carol", NEW_PASSWORD)).isEqualTo(200);
        assertThat(loginStatus(PREFIX + "carol", OLD_PASSWORD)).isEqualTo(400);
    }

    // ========== 旧 token 立刻失效 ==========

    /**
     * <b>这条是整组唯一的哨兵: 改密之后, 改密之前签发的那张 token 立刻不认了。</b>
     *
     * <p>没有这条, "改密码"在服务端等于没发生 —— 而这件事的两个常见触发点(用户怀疑
     * 账号被盗、管理员强制重置)要的正是把别人手里那把钥匙作废。
     *
     * <p>先睡过一秒再改, 理由见类注释: 规则的分辨率是秒, 不睡的话这张 token 有一半的
     * 概率与改密落在同一秒里, 于是它**本来就该**活着, 用例会随机红。
     */
    @Test
    @DisplayName("改密之后, 旧 token 调 /api/user/me 立刻 401")
    void oldTokenDiesImmediatelyAfterChangingThePassword() throws Exception {
        seedUser(PREFIX + "dave");
        String oldToken = login(PREFIX + "dave", OLD_PASSWORD);
        assertThat(meStatus(oldToken)).as("改密之前它是好用的 —— 否则下面那条 401 说明不了任何事")
                .isEqualTo(200);

        Thread.sleep(1100);
        changePassword(oldToken, OLD_PASSWORD, NEW_PASSWORD).andExpect(status().isOk());

        assertThat(meStatus(oldToken))
                .as("改密之前签发的 token 必须立刻失效")
                .isEqualTo(401);
        // 401 是 SecurityConfig 那条 anyRequest().authenticated() 给的 —— 前端据此清登录态
        // 并跳登录页, 与"会话过期"的表现完全一致
        assertThat(login(PREFIX + "dave", NEW_PASSWORD)).isNotBlank();
    }

    /**
     * <b>那个"同一秒"的窗口: 该活的活, 该杀的杀。</b>
     *
     * <p>用解出来的 {@code iat} 反推时刻去摆位, 于是这条不含任何等待、两次断言都是确定的:
     * <ul>
     *   <li>{@code password_changed_at} 落在**同一秒**里(仅微秒更晚) —— 那张 token 放行。
     *       这正是"改密 → 拿响应里新 token 调下一个接口"的常态, 把截断那一步漏掉的实现
     *       会在这里红;</li>
     *   <li>挪到**下一秒** —— 同一张 token 立刻 401。没有后半条的话, 一个"永远放行"的
     *       实现也能通过前半条。</li>
     * </ul>
     */
    @Test
    @DisplayName("同一秒内签发的 token 不算过期; 挪到下一秒就作废")
    void aTokenFromTheSameSecondSurvives() throws Exception {
        String username = PREFIX + "erin";
        seedUser(username);
        String token = login(username, OLD_PASSWORD);
        long iat = issuedAtOf(token);

        // 改密时刻落在这一秒之内、但比 token 晚半秒 —— 真实改密就是这个样子
        setPasswordChangedAt(username, Instant.ofEpochSecond(iat, 500_000_000L));
        assertThat(meStatus(token))
                .as("同一秒内签发的 token 不能被判成过期, 否则改完密码的那台设备会立刻掉线")
                .isEqualTo(200);

        // 同一张 token, 把改密时刻挪到下一秒
        setPasswordChangedAt(username, Instant.ofEpochSecond(iat + 1));
        assertThat(meStatus(token))
                .as("更早那一秒签发的 token 必须作废 —— 否则截断那一步等于把整条规则废掉了")
                .isEqualTo(401);
    }

    /**
     * 失效的 token 换来的是 **401, 而不是 500**。
     *
     * <p>这一条看着多余, 其实是那个设计决定的证伪点: {@code JwtAuthFilter} 对过期 token
     * 的处理是"**不建身份**而继续过滤器链", 不是当场写 401 返回。两种做法在受保护端点上
     * 看起来一样, 但后者会把"用新密码重新登录"这一次请求也堵死 —— 登录请求由前端的请求
     * 拦截器带着旧 token 发出, 会被过滤器拦在到达 controller 之前, 用户得提交两次。
     */
    @Test
    @DisplayName("失效 token 走的是「未登录」那条路: 401 而不是 500, 也不影响公开端点")
    void aStaleTokenIsDeclinedNotExploded() throws Exception {
        String username = PREFIX + "frank";
        seedUser(username);
        String token = login(username, OLD_PASSWORD);
        setPasswordChangedAt(username, Instant.ofEpochSecond(issuedAtOf(token) + 10));

        mockMvc.perform(get("/api/user/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("请先登录"));

        // 带着那张废 token 去登录照样成功 —— 这正是"不当场 401"换来的
        mockMvc.perform(post("/api/user/login")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", OLD_PASSWORD))))
                .andExpect(status().isOk());
    }

    // ========== 管理员重置他人 ==========

    /**
     * 管理员重置: 目标被迫重新登录(这正是重置该有的效果), 而操作留下一条账。
     *
     * <p>账那半边与另外四个破坏性动作同等重要: 重置不改变权限, 却是唯一一个能把人挡在
     * 门外的非权限动作 —— 它作废那个人手上所有 token, 而能不能再进来取决于有没有人把
     * 新密码告诉他。
     */
    @Test
    @DisplayName("管理员重置他人: 200, 目标旧 token 401, 新密码可登录, 并记一条 USER_PASSWORD_RESET")
    void adminResetsSomeoneElsesPassword() throws Exception {
        long target = seedUser(PREFIX + "grace");
        String victimToken = login(PREFIX + "grace", OLD_PASSWORD);
        assertThat(meStatus(victimToken)).isEqualTo(200);

        // 同样要睡过一秒: 不然这张 token 与重置落在同一秒里, 它本来就该活着(见类注释)
        Thread.sleep(1100);
        adminReset(adminToken, target, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("密码已重置"));

        assertThat(meStatus(victimToken))
                .as("被盗账号要夺回来, 靠的就是这一下 —— 旧 token 必须立刻作废")
                .isEqualTo(401);
        assertThat(loginStatus(PREFIX + "grace", NEW_PASSWORD)).isEqualTo(200);

        // 账本那一行
        String detail = jdbc.queryForObject(
                "SELECT detail FROM admin_action_log WHERE action = 'USER_PASSWORD_RESET'", String.class);
        assertThat(detail).isEqualTo("重置了用户 " + PREFIX + "grace 的密码");
        assertThat(jdbc.queryForObject(
                "SELECT actor_name FROM admin_action_log WHERE action = 'USER_PASSWORD_RESET'",
                String.class)).isEqualTo("admin");
        assertThat(detail).as("账本是长期留存、多人可读的, 新密码本身不能进去")
                .doesNotContain(NEW_PASSWORD);
    }

    /** 不能重置自己: 自己那条路要验旧密码, 是另一件事 */
    @Test
    @DisplayName("管理员重置自己: 400 并指回个人中心, 且不记账")
    void adminCannotResetThemselves() throws Exception {
        long admin = jdbc.queryForObject(
                "SELECT id FROM \"user\" WHERE username = 'admin'", Long.class);

        adminReset(adminToken, admin, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请使用个人中心的修改密码"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log", Integer.class))
                .as("失败的动作不许留账 —— 账本里出现一条没发生过的事, 整本账就不可信了")
                .isZero();
        assertThat(loginStatus("admin", "admin123"))
                .as("管理员自己的密码必须原封不动")
                .isEqualTo(200);
    }

    /** 目标不存在 → 404, 同样不记账 */
    @Test
    @DisplayName("重置一个不存在的用户: 404")
    void resettingAnUnknownUserIsNotFound() throws Exception {
        adminReset(adminToken, 999999L, NEW_PASSWORD).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log", Integer.class)).isZero();
    }

    /** 请求体形状不对(缺 newPassword / 太弱)是 400, 不是 500 */
    @Test
    @DisplayName("重置的入参校验: 缺字段与太弱都是 400")
    void resetValidatesItsBody() throws Exception {
        long target = seedUser(PREFIX + "henry");

        mockMvc.perform(put("/api/admin/users/" + target + "/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请输入新密码"));

        adminReset(adminToken, target, "short1").andExpect(status().isBadRequest());

        assertThat(loginStatus(PREFIX + "henry", OLD_PASSWORD)).isEqualTo(200);
    }

    /**
     * 改密只动自己那一行。
     *
     * <p>这条防的是那种一次改一片的写法: 忘了 {@code WHERE id = ?} 的 {@code UPDATE}、
     * 或者顺手把某个缓存里的整批实体一起 {@code save} 了。**它不会报错**, 表现是"某一天
     * 忽然有一批人登不进来", 而那时已经过了很多次改密、无从追溯是哪一次干的。
     */
    @Test
    @DisplayName("改密只动自己那一行: 别人的密码与 token 都不受影响")
    void changingYourPasswordLeavesOtherAccountsAlone() throws Exception {
        seedUser(PREFIX + "ivan");
        seedUser(PREFIX + "judy");
        String ivanToken = login(PREFIX + "ivan", OLD_PASSWORD);
        String judyToken = login(PREFIX + "judy", OLD_PASSWORD);

        changePassword(ivanToken, OLD_PASSWORD, NEW_PASSWORD).andExpect(status().isOk());

        assertThat(loginStatus(PREFIX + "judy", OLD_PASSWORD))
                .as("另一个人的密码必须原封不动")
                .isEqualTo(200);
        assertThat(passwordChangedAtOf(PREFIX + "judy"))
                .as("别人的 password_changed_at 也不该被写上 —— 写上了等于把他也踢下线")
                .isNull();
        assertThat(meStatus(judyToken))
                .as("别人的 token 必须照样能用")
                .isEqualTo(200);
    }
}
