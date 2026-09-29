package com.animetracker.service;

import com.animetracker.config.JwtUtil;
import com.animetracker.config.LoginProtectionProperties;
import com.animetracker.dto.RequestDTO.*;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /**
     * 登录失败对外统一用这一句话.
     *
     * 「用户不存在」「密码错误」必须回一样的文案和一样的 HTTP 状态码.
     * 一旦两者可区分, 这个接口就变成了用户名枚举器 —— 攻击者拿一份常见用户名
     * 字典挨个试, 就能筛出哪些账号真实存在, 再针对性爆破.
     */
    private static final String LOGIN_FAILED = "用户名或密码错误";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginProtectionProperties loginProps;

    /**
     * 一个结构合法的 BCrypt 哈希, 只在「用户名不存在」时用来陪着空跑一次密码校验.
     * 详见 login() 里的说明. 它是启动时用编码器现算的, 不写死在代码里,
     * 免得哪天换了编码算法(比如 BCrypt 强度变了)它就成了非法数据.
     */
    private final String absentUserHash;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       LoginProtectionProperties loginProps) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.loginProps = loginProps;
        this.absentUserHash = passwordEncoder.encode("anitrack-absent-user-placeholder");
    }

    // ========== 注册 ==========

    /** 注册 */
    public Map<String, Object> register(RegisterRequest req) {
        String username = req.getUsername().trim();
        String email = normalizeEmail(req.getEmail());

        // 这两次查询是为了给出「到底是用户名重复还是邮箱重复」这种能看懂的提示;
        // 真正兜底的是数据库上的唯一索引, 见下面的 catch.
        if (userRepository.existsByUsername(username)) {
            throw BusinessException.badRequest("用户名已存在");
        }
        if (userRepository.existsByEmail(email)) {
            throw BusinessException.badRequest("该邮箱已被注册");
        }

        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(req.getPassword()))
                .email(email)
                .failedAttempts(0)
                .build();

        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // 上面两次 exists 查询和这次写入之间存在时间差, 并发提交同一个用户名
            // 或邮箱时两次查询都会通过, 最后靠唯一索引拦下. 但抛出来的是
            // DataIntegrityViolationException, 落到兜底处理器会变成 500
            // 「服务器内部错误」—— 用户看到这个只会一脸茫然. 翻译成人话.
            throw BusinessException.badRequest("用户名或邮箱已被注册");
        }

        return buildAuthPayload(user);
    }

    /**
     * 邮箱归一化: 去空白 + 转小写.
     *
     * 转小写不是洁癖, 是为了让数据库那个唯一索引真的有效: 邮箱的域名部分本来就
     * 不区分大小写, 而唯一索引是区分大小写的. 不做这一步, "A@x.com" 和 "a@x.com"
     * 会被当成两个不同邮箱存进去, 同一个人也就注册出两个账号.
     */
    private static String normalizeEmail(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    // ========== 登录 ==========

    /** 登录 */
    public Map<String, Object> login(LoginRequest req) {
        String username = req.getUsername().trim();
        User user = userRepository.findByUsername(username).orElse(null);

        if (user == null) {
            burnPasswordCheck(req.getPassword());
            throw BusinessException.badRequest(LOGIN_FAILED);
        }

        // 锁定检查放在密码校验之前: 锁定期内连「密码对不对」都不该被回答,
        // 否则锁定就只是限速, 不是锁定.
        if (user.isLocked()) {
            throw BusinessException.tooManyRequests(lockMessage(user.getLockedUntil()));
        }

        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            // 达到阈值时 recordFailure 自己会抛出「已锁定」, 下面那行就不会执行
            recordFailure(user);
            throw BusinessException.badRequest(LOGIN_FAILED);
        }

        // 密码正确之后才看账号状态.
        // 顺序反过来就会把「这个账号存在、而且被禁用了」告诉一个还没通过密码校验的人.
        if ("DISABLED".equals(user.getStatus())) {
            throw BusinessException.forbidden("账号已被禁用，请联系管理员");
        }

        clearFailures(user);
        return buildAuthPayload(user);
    }

    /**
     * 用户名不存在时也走一遍 BCrypt 计算.
     *
     * BCrypt 是故意设计得慢的 (单次约 100ms). 如果「用户名不存在」这一支直接返回,
     * 它和「密码错误」的响应耗时会差出一个数量级 —— 攻击者完全不用看返回内容,
     * 只统计响应时间就能把用户名枚举出来, 上面统一文案的努力就白费了.
     *
     * 代价是不存在的用户名也要吃掉一次 BCrypt 计算. 这不构成新的风险:
     * 攻击者本来就能拿 admin 这种必然存在的用户名把 CPU 打满, 多这一个分支
     * 并不能帮到他们.
     */
    private void burnPasswordCheck(String rawPassword) {
        if (absentUserHash == null) {
            // 只有测试里注入了 mock 编码器才会走到这里
            return;
        }
        passwordEncoder.matches(rawPassword, absentUserHash);
    }

    /**
     * 记一次失败; 达标则锁定并直接把「已锁定」抛出去.
     *
     * <p><b>为什么不是「读出来 +1 再 save」</b>: 那是读-改-写, 并发下互相覆盖 ——
     * 两个请求同时读到 3, 各自写回 4, 两次失败只记了一次. 后果不是「计数偏小」
     * 这么轻: 阈值是 5, 攻击者只要并发提交, 计数就涨得比尝试次数慢, 爆破窗口被
     * 拉长, 而日志上一切正常. 现在自增交给一条 UPDATE, 判断读的是**自增之后**的值.
     *
     * <p>刻意不去动 {@code user} 这个受管实体(不再 setFailedAttempts + save):
     * 实体在一级缓存里, 我们对它做的修改会在事务提交/自动 flush 时被写回数据库,
     * 那条写回带着**过期的**计数, 正好把刚刚自增出来的值盖掉. 走 repository 的
     * 批量 UPDATE, 内存里那份就是只读的.
     *
     * <p>自增和「读回来判断」仍是两条语句, 中间可能插进别人的一次失败. 但方向是
     * 安全的: 最多把计数算大一点、早锁一次; 而漏锁才是危险的那一侧.
     */
    private void recordFailure(User user) {
        userRepository.incrementFailedAttempts(user.getId());

        int attempts = userRepository.readFailedAttempts(user.getId());
        if (attempts >= loginProps.getMaxFailures()) {
            LocalDateTime until = LocalDateTime.now().plusMinutes(loginProps.getLockMinutes());
            // 计数清零和锁定必须一起做, 理由见 UserRepository.lockAndResetFailures
            userRepository.lockAndResetFailures(user.getId(), until);

            log.warn("账号 {} 连续登录失败 {} 次, 已锁定至 {}",
                    user.getUsername(), attempts, until);
            throw BusinessException.tooManyRequests(lockMessage(until));
        }
    }

    /**
     * 登录成功时清空失败痕迹.
     *
     * <p>这里仍然走「改实体 + save」, 与 recordFailure 刻意不同 —— 不是因为这条路更安全,
     * 而是因为它**不需要**更安全: 它写的是 0, 而 0 正是「连续失败」这个语义在成功后应有的
     * 值. 极端时序下(成功与一次并发失败撞上)最多抹掉那一次失败, 而按「连续」的定义, 中间
     * 成功过一次本来就该重新计数. 失败路径不一样: 它写回的是**过期的旧计数**, 那是纯粹
     * 的数据丢失, 所以那条必须交给数据库自增.
     */
    private void clearFailures(User user) {
        boolean clean = user.failedAttemptsOrZero() == 0 && user.getLockedUntil() == null;
        if (clean) {
            // 绝大多数登录都属于这种情况, 省掉一次没必要的写库
            return;
        }
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
    }

    /**
     * 给用户看的锁定提示, 剩余时间向上取整到分钟 (剩 30 秒也说「1 分钟」).
     *
     * <p>入参是**时刻**而不是 User: 锁定那一刻我们手里还没有一个带 lockedUntil 的
     * 受管实体(见 recordFailure, 现在不去改实体), 而一个接受 LocalDateTime 的方法
     * 两个调用点都能用.
     */
    private String lockMessage(LocalDateTime until) {
        long seconds = Math.max(0, Duration.between(LocalDateTime.now(), until).toSeconds());
        long minutes = Math.max(1, (long) Math.ceil(seconds / 60.0));
        return "密码错误次数过多，账号已锁定，请 " + minutes + " 分钟后再试";
    }

    // ========== 其它 ==========

    /** 获取用户信息 */
    public User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
    }

    /**
     * 登录 / 注册成功后返回给前端的凭据与用户信息.
     *
     * 注册原本只回 token/id/username/role 四个字段, 和登录的回包是两个形状.
     * 同一个「登录态」有两种结构是隐患: 前端 store 拿到注册结果后缺 email、
     * 缺 status, 以后任何依赖这些字段的界面(比如个人页)在「刚注册完」和
     * 「重新登录后」两个时刻表现会不一样. 统一成一个出口.
     */
    private Map<String, Object> buildAuthPayload(User user) {
        Map<String, Object> data = new HashMap<>();
        data.put("token", jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole()));
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("email", user.getEmail());
        data.put("avatar", user.getAvatar());
        data.put("role", user.getRole());
        data.put("status", user.getStatus());
        return data;
    }
}
