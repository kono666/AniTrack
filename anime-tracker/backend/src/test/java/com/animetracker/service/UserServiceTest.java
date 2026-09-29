package com.animetracker.service;

import com.animetracker.config.JwtUtil;
import com.animetracker.config.LoginProtectionProperties;
import com.animetracker.dto.RequestDTO.LoginRequest;
import com.animetracker.dto.RequestDTO.RegisterRequest;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册与登录的规则测试.
 *
 * 集中钉住两类「写错了不会报错、只会静默失效」的规则:
 *
 * 一是登录失败锁定. 它的失效方式特别隐蔽 —— 计数、锁定、清零三段里少写一段,
 * 代码照样跑, 只是防护没了; 或者反过来, 锁定期满后没清零, 用户会被永久挡在门外.
 * 这里管的是控制流(什么时候锁、锁了之后还说不说话), 至于计数自增在并发下会不会
 * 丢 —— 那是 mock 看不见的属性, 交给 {@code WriteConflictIntegrationTest} 用真库跑.
 *
 * 二是「不给攻击者额外信息」. 用户不存在和密码错误必须完全不可区分,
 * 被禁用的账号也不能在密码校验通过之前暴露. 这些是靠返回值和调用顺序保证的,
 * 而调用顺序这种东西在重构时最容易被顺手改掉.
 */
class UserServiceTest {

    /** 编码器是对 mock, 让它固定返回这个串, 好断言「登录失败时确实陪跑了一次校验」 */
    private static final String DUMMY_HASH = "$2a$10$placeholderplaceholderplaceholderplaceholderplaceholde";

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private LoginProtectionProperties loginProps;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtUtil = mock(JwtUtil.class);
        loginProps = new LoginProtectionProperties();
        loginProps.setMaxFailures(5);
        loginProps.setLockMinutes(15);

        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
        when(jwtUtil.generateToken(any(), anyString(), anyString())).thenReturn("signed-token");
        // save 要回它的入参: register/login 会拿返回值继续用
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userService = new UserService(userRepository, passwordEncoder, jwtUtil, loginProps);
    }

    // ========== 工具 ==========

    private static User existingUser(String username, String email, String rawPasswordHash) {
        return User.builder()
                .id(1L)
                .username(username)
                .password(rawPasswordHash)
                .email(email)
                .role("USER")
                .status("ACTIVE")
                .failedAttempts(0)
                .build();
    }

    private static LoginRequest loginReq(String username, String password) {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }

    private static RegisterRequest registerReq(String username, String email, String password) {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(username);
        req.setEmail(email);
        req.setPassword(password);
        return req;
    }

    private User savedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }

    // ========== 注册: 邮箱 ==========

    @Test
    @DisplayName("邮箱已被注册时拒绝")
    void rejectsDuplicateEmail() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("a@x.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.register(registerReq("alice", "a@x.com", "abcd1234")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("邮箱已被注册");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("用户名已被占用时拒绝, 且优先于邮箱判断")
    void rejectsDuplicateUsername() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> userService.register(registerReq("alice", "a@x.com", "abcd1234")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名已存在");
    }

    /**
     * 邮箱要归一化: 去空白 + 转小写.
     *
     * 不归一化的话, 「A@X.com」和「a@x.com」会被当成两个不同邮箱存进去,
     * 数据库那个大小写敏感的唯一索引就形同虚设, 同一个人能注册出两个账号.
     */
    @Test
    @DisplayName("邮箱入库前去掉空白并转小写, 查重用的也是归一化后的值")
    void normalizesEmail() {
        when(userRepository.existsByEmail("a@x.com")).thenReturn(false);

        userService.register(registerReq("  alice  ", "  A@X.com  ", "abcd1234"));

        verify(userRepository).existsByEmail("a@x.com");
        User saved = savedUser();
        assertThat(saved.getEmail()).isEqualTo("a@x.com");
        assertThat(saved.getUsername()).isEqualTo("alice");
    }

    /**
     * exists 查询和写入之间有并发窗口, 最终靠唯一索引拦下.
     * 但抛出来的是 DataIntegrityViolationException, 不翻译的话用户看到的是 500.
     */
    @Test
    @DisplayName("撞上唯一索引时翻译成可读的 400, 而不是 500")
    void translatesUniqueConstraintViolation() {
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> userService.register(registerReq("alice", "a@x.com", "abcd1234")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被注册");
    }

    @Test
    @DisplayName("注册成功回包与登录回包结构一致")
    void registerPayloadMatchesLoginShape() {
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        Map<String, Object> data = userService.register(registerReq("alice", "a@x.com", "abcd1234"));

        assertThat(data).containsKeys("token", "id", "username", "email", "avatar", "role", "status");
        assertThat(data.get("token")).isEqualTo("signed-token");
    }

    // ========== 登录: 统一错误信息 ==========

    /**
     * 用户不存在和密码错误必须完全一样.
     * 一旦可区分, 这个接口就成了用户名枚举器.
     */
    @Test
    @DisplayName("用户不存在与密码错误的报错完全一致")
    void unknownUserAndWrongPasswordAreIndistinguishable() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("alice"))
                .thenReturn(Optional.of(existingUser("alice", "a@x.com", DUMMY_HASH)));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        String ghostMessage = messageOf(() -> userService.login(loginReq("ghost", "abcd1234")));
        String wrongMessage = messageOf(() -> userService.login(loginReq("alice", "abcd1234")));

        assertThat(ghostMessage).isEqualTo(wrongMessage);
        assertThat(ghostMessage).isEqualTo("用户名或密码错误");
    }

    /**
     * 用户名不存在时也要空跑一次密码校验.
     *
     * BCrypt 单次约 100ms, 若这一支直接返回, 「存在」和「不存在」的响应耗时会差
     * 一个数量级 —— 统一文案就白做了, 攻击者只看耗时照样能枚举用户名.
     */
    @Test
    @DisplayName("用户名不存在时也走一遍 BCrypt 校验, 抹平响应耗时差异")
    void burnsPasswordCheckForUnknownUser() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.login(loginReq("ghost", "abcd1234")))
                .isInstanceOf(BusinessException.class);

        verify(passwordEncoder).matches(eq("abcd1234"), eq(DUMMY_HASH));
    }

    // ========== 登录: 失败计数与锁定 ==========

    /**
     * 计数走的是数据库里那条自增, 不是「读出来 +1 再 save」.
     *
     * <p>这里只能验到「确实发了那条 UPDATE」——自增本身有没有原子性, mock 看不见,
     * 那个属性由 SQL 保证, 在 {@code WriteConflictIntegrationTest} 里用真库并发跑.
     * 但「有没有走那条路」值得钉住: 退回读-改-写之后, 这段测试**照样绿**
     * (它只断言行为), 所以这里必须显式验调用.
     */
    @Test
    @DisplayName("密码错误时发一条自增 UPDATE, 且不去改写受管实体")
    void countsFailedAttempts() {
        User user = existingUser("alice", "a@x.com", DUMMY_HASH);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(userRepository.readFailedAttempts(1L)).thenReturn(1);   // 自增之后读回来的值

        assertThatThrownBy(() -> userService.login(loginReq("alice", "wrong123")))
                .isInstanceOf(BusinessException.class);

        verify(userRepository).incrementFailedAttempts(1L);
        // 关键: 不能顺手 save 这个实体. 一级缓存里那份带着**过期**的计数,
        // 它的写回会把刚自增出来的值盖掉 —— 那正是要修掉的丢失更新.
        verify(userRepository, never()).save(any());
    }

    /** 差一次不锁: 阈值是 5, 第 4 次失败之后还能继续试 */
    @Test
    @DisplayName("失败次数在阈值以下时不锁定")
    void doesNotLockBelowThreshold() {
        User user = existingUser("alice", "a@x.com", DUMMY_HASH);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(userRepository.readFailedAttempts(1L)).thenReturn(4);

        assertThatThrownBy(() -> userService.login(loginReq("alice", "wrong123")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名或密码错误");

        verify(userRepository, never()).lockAndResetFailures(any(), any());
    }

    /**
     * 判断读的必须是**自增之后**的值.
     *
     * 自增和判断是两条语句, 顺序写反(先读后自增)时, 第 5 次失败读到的是 4,
     * 于是不锁, 一直要到第 6 次才锁 —— 阈值静默地从 5 变成 6. 这里把
     * 「读回来的值达到阈值就锁」这件事钉死.
     */
    @Test
    @DisplayName("连续失败达到阈值时锁定, 并把计数清零")
    void locksAfterReachingThreshold() {
        User user = existingUser("alice", "a@x.com", DUMMY_HASH);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(userRepository.readFailedAttempts(1L)).thenReturn(5);   // 已经错了 5 次

        assertThatThrownBy(() -> userService.login(loginReq("alice", "wrong123")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(429))
                .hasMessageContaining("已锁定");

        ArgumentCaptor<LocalDateTime> until = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).lockAndResetFailures(eq(1L), until.capture());
        assertThat(until.getValue()).isAfter(LocalDateTime.now());
        // 锁定时长来自配置(15 分钟), 不是写死的数字
        assertThat(until.getValue()).isBefore(LocalDateTime.now().plusMinutes(16));
        // 计数清零与锁定是同一条 UPDATE(留给真库那边验), 这里只钉「走到锁定分支后
        // 不会再额外 save 一次实体」, 理由同 countsFailedAttempts
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("锁定期内即使密码正确也拒绝, 且不签发 token")
    void rejectsCorrectPasswordWhileLocked() {
        User user = existingUser("alice", "a@x.com", DUMMY_HASH);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> userService.login(loginReq("alice", "abcd1234")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(429))
                .hasMessageContaining("已锁定");

        // 关键: 锁定期内连密码对不对都不该被回答
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(jwtUtil, never()).generateToken(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("锁定时间已过就自动放行, 不需要定时任务清扫")
    void lockExpiresAutomatically() {
        User user = existingUser("alice", "a@x.com", DUMMY_HASH);
        user.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        Map<String, Object> data = userService.login(loginReq("alice", "abcd1234"));

        assertThat(data.get("token")).isEqualTo("signed-token");
        // 过期的锁定时间戳要顺手清掉, 否则管理端会把它显示成「已锁定」
        assertThat(savedUser().getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("登录成功清空失败痕迹")
    void clearsFailuresOnSuccess() {
        User user = existingUser("alice", "a@x.com", DUMMY_HASH);
        user.setFailedAttempts(3);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        userService.login(loginReq("alice", "abcd1234"));

        User saved = savedUser();
        assertThat(saved.getFailedAttempts()).isZero();
        assertThat(saved.getLockedUntil()).isNull();
    }

    /** 「连续」失败: 中间成功一次就该重新计数 */
    @Test
    @DisplayName("登录成功一次就把失败计数清零, 下次容错次数重新算")
    void successResetsCounterForNextRound() {
        User user = existingUser("alice", "a@x.com", DUMMY_HASH);
        user.setFailedAttempts(4);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        userService.login(loginReq("alice", "abcd1234"));

        assertThat(savedUser().getFailedAttempts()).isZero();
    }

    /** 干净的账号登录成功时不该产生一次多余的写库 */
    @Test
    @DisplayName("无失败记录时登录成功不写库")
    void doesNotWriteWhenNothingToClear() {
        User user = existingUser("alice", "a@x.com", DUMMY_HASH);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        userService.login(loginReq("alice", "abcd1234"));

        verify(userRepository, never()).save(any());
    }

    // ========== 登录: 禁用账号 ==========

    @Test
    @DisplayName("已禁用账号在密码正确时才返回 403")
    void disabledAccountReportedOnlyAfterPasswordCheck() {
        User user = existingUser("alice", "a@x.com", DUMMY_HASH);
        user.setStatus("DISABLED");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> userService.login(loginReq("alice", "abcd1234")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403))
                .hasMessageContaining("禁用");
    }

    /**
     * 密码错误时不能泄露「这个账号存在且被禁用」.
     * 顺序写反了就会把账号状态告诉一个还没通过密码校验的人.
     */
    @Test
    @DisplayName("已禁用账号在密码错误时只回通用错误, 不暴露禁用状态")
    void disabledAccountNotLeakedOnWrongPassword() {
        User user = existingUser("alice", "a@x.com", DUMMY_HASH);
        user.setStatus("DISABLED");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> userService.login(loginReq("alice", "wrong123")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名或密码错误");
    }

    @Test
    @DisplayName("用户名两侧空白被忽略")
    void trimsUsernameOnLogin() {
        User user = existingUser("alice", "a@x.com", DUMMY_HASH);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        userService.login(loginReq("  alice  ", "abcd1234"));

        verify(userRepository).findByUsername("alice");
    }

    /** 取业务异常里的提示语, 用来比较两条错误路径是否完全一致 */
    private static String messageOf(Runnable action) {
        try {
            action.run();
            throw new AssertionError("预期抛出 BusinessException, 但正常返回了");
        } catch (BusinessException e) {
            return e.getMessage();
        }
    }
}
