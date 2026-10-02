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
    private final LoginEventService loginEventService;

    /**
     * 一个结构合法的 BCrypt 哈希, 只在「用户名不存在」时用来陪着空跑一次密码校验.
     * 详见 login() 里的说明. 它是启动时用编码器现算的, 不写死在代码里,
     * 免得哪天换了编码算法(比如 BCrypt 强度变了)它就成了非法数据.
     */
    private final String absentUserHash;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       LoginProtectionProperties loginProps,
                       LoginEventService loginEventService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.loginProps = loginProps;
        this.loginEventService = loginEventService;
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

    /**
     * 登录.
     *
     * <p><b>{@code ip} 是从外面传进来的, 不是在这里取的</b> —— 取它的正确姿势
     * ({@code http.getRemoteAddr()}, 以及为什么绝不能自己去读 {@code X-Forwarded-For})
     * 是 HTTP 层的事, 写在 {@code UserController.login} 的注释里. 这一层只负责把它
     * 转手交给 {@link LoginEventService}.
     *
     * <p><b>每一条出口都恰好在 {@code login_event} 里留一条记录</b> —— 成功、用户名不存在、
     * 密码错、被锁、被禁用各一条, 一次请求对应一行, 没有例外也没有重复. 这条不变式值得
     * 单独说, 是因为它有个特别容易踩的反例: 密码错误那一支里, 记事件必须写在
     * {@code recordFailure} **之前** —— 后者到达阈值时会抛出「已锁定」, 写在它后面的话,
     * 把账号打锁的那第 5 次尝试恰好不会落库, 而它正是最该被看见的那一次.
     */
    public Map<String, Object> login(LoginRequest req, String ip) {
        String username = req.getUsername().trim();
        User user = userRepository.findByUsername(username).orElse(null);

        if (user == null) {
            burnPasswordCheck(req.getPassword());
            // userId 给 null: 用户名不存在时没有用户可挂, 但这一行仍然要记 ——
            // 「同一个 IP 在一分钟里打了一堆不存在的用户名」正是扫账号的形状.
            loginEventService.recordAttempt(null, ip, false);
            throw BusinessException.badRequest(LOGIN_FAILED);
        }

        // 锁定检查放在密码校验之前: 锁定期内连「密码对不对」都不该被回答,
        // 否则锁定就只是限速, 不是锁定.
        if (user.isLocked()) {
            loginEventService.recordAttempt(user.getId(), ip, false);
            throw BusinessException.tooManyRequests(lockMessage(user.getLockedUntil()));
        }

        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            // 先记事件, 再进 recordFailure —— 顺序不能反, 理由见方法注释.
            loginEventService.recordAttempt(user.getId(), ip, false);
            // 达到阈值时 recordFailure 自己会抛出「已锁定」, 下面那行就不会执行
            recordFailure(user);
            throw BusinessException.badRequest(LOGIN_FAILED);
        }

        // 密码正确之后才看账号状态.
        // 顺序反过来就会把「这个账号存在、而且被禁用了」告诉一个还没通过密码校验的人.
        if ("DISABLED".equals(user.getStatus())) {
            loginEventService.recordAttempt(user.getId(), ip, false);
            throw BusinessException.forbidden("账号已被禁用，请联系管理员");
        }

        markLoginSuccess(user);
        loginEventService.recordAttempt(user.getId(), ip, true);
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
     * 登录成功时: 清空失败痕迹 + 记下这次登录的时刻.
     *
     * <p><b>这里仍然走「改实体 + save」, 与 recordFailure 刻意不同</b> —— 不是因为这条路更安全,
     * 而是因为它**不需要**更安全: 它写的是 0, 而 0 正是「连续失败」这个语义在成功后应有的
     * 值. 极端时序下(成功与一次并发失败撞上)最多抹掉那一次失败, 而按「连续」的定义, 中间
     * 成功过一次本来就该重新计数. 失败路径不一样: 它写回的是**过期的旧计数**, 那是纯粹
     * 的数据丢失, 所以那条必须交给数据库自增.
     *
     * <p>{@code lastLoginAt} 也走同一次 save, 理由更硬: 它只能这么写. 若照 recordFailure
     * 写一条 {@code @Modifying} 批量 UPDATE, 那条语句会**绕过持久化上下文** —— 同一请求里
     * 手里这个 User 仍是受管实体、内存里的 lastLoginAt 还是 null, 紧接着任何一次
     * {@code save(user)} 都会把 last_login_at 写回 NULL. 那正是 recordFailure 注释警告的
     * 同一类陈旧写, 只是受害列不同. (丢更新在这里本来也不是问题: 这一列是「最新一次赢」.)
     *
     * <p><b>为什么去掉了曾经的「干净账号直接 return」早退</b>: 留着它, 只有「之前失败过或
     * 被锁过」的账号才会被写, 于是 last_login_at 对绝大多数账号永远是 NULL —— 后台那一列
     * 整片是「-」, 而不报任何错、没有任何用例会红. 这是一处**按设计反转的既有行为**,
     * 提交说明里点了名: 代价是每次登录多一条 UPDATE.
     */
    private void markLoginSuccess(User user) {
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(LocalDateTime.now());
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

    // ========== 改密码 ==========

    /**
     * 用户改自己的密码, 成功时**顺带回一张新 token**.
     *
     * <p><b>为什么必须验旧密码.</b> 这个端点认的是 Bearer token, 而 token 可能在很多
     * 地方留着(别人电脑上没退的登录、被 XSS 偷走的一张)。只看「登录态」就允许改密码的话,
     * 一次凭证泄漏就升级成**永久账号接管** —— 攻击者改掉密码, 真正的用户再也进不来。
     * 验过一次旧密码, 那张 token 能做的事就仅限于「在本人不在场时改密」这一件,
     * 而它被挡住了。
     *
     * <p><b>为什么成功要回一张新 token。</b> 改密会把 {@code passwordChangedAt} 写到现在,
     * 于是**改密之前签发的 token 全部作废** {@code (JwtAuthFilter.isStaleAfterPasswordChange)}。
     * 当前这台设备手上那张正是其中之一 —— 不回新的, 用户改完密码立刻被登出, 那看起来
     * 就是个 bug(「我改了个密码, 网站把我踢了」)。回一张新的, 当前会话无缝续上,
     * 而**别处的**旧 token 全部失效, 这正是改密码该有的效果。
     *
     * <p>时序上要注意: 新 token 的 {@code iat} 是**秒**级, 而 {@code passwordChangedAt}
     * 是微秒。两者落在同一秒是常态, 所以那边比较时必须先把微秒截掉 ——
     * 那条注释写在 {@code JwtAuthFilter} 里, 这里再提一次是因为**这个方法的返回值就是
     * 那个坑的另一半**: 它回的 token 必须能立刻用。
     *
     * <p>不加 {@code @Transactional}: 这里只有一次 {@code save}, 单条 save 自身就是原子的
     * (与 {@code AdminService} 不带类级事务是同一条判断)。
     */
    public Map<String, Object> changePassword(User user, ChangePasswordRequest req) {
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPassword())) {
            // 与登录失败不同, 这里可以明说是"原密码不对": 调用方已经通过鉴权了,
            // 不存在"用这个接口枚举用户名"的问题
            throw BusinessException.badRequest("原密码不正确");
        }
        if (passwordEncoder.matches(req.getNewPassword(), user.getPassword())) {
            // 不加这一条的话, 这次改动会把所有别的登录踢掉、而密码一个字符都没变 ——
            // 用户以为自己加固了账号, 实际只是被登出了一次
            throw BusinessException.badRequest("新密码不能与原密码相同");
        }

        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        // 与 setPassword 同一时刻写: 这两件事分开就没有意义了 —— 只改密码不记时刻,
        // 旧 token 照样能用; 只记时刻不改密码, 那是把所有会话平白踢掉
        user.setPasswordChangedAt(LocalDateTime.now());
        userRepository.save(user);

        return buildAuthPayload(user);
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
