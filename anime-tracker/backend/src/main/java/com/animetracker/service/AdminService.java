package com.animetracker.service;

import com.animetracker.entity.AdminActionLog;
import com.animetracker.entity.Anime;
import com.animetracker.entity.User;
import com.animetracker.entity.Review;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.AdminActionLogRepository;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.UserRepository;
import com.animetracker.repository.ReviewRepository;
import com.animetracker.repository.TrackingRepository;
import com.animetracker.util.PageResults;
import com.animetracker.util.SearchPatterns;
import com.animetracker.util.TextSnippet;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理端的所有动作.
 *
 * <p><b>这个类刻意不带类级 {@code @Transactional}。</b> 两个原因:
 * <ul>
 *   <li>Agent 的工具链上, {@code ToolTransactionRunner} 已经给每次工具执行开了
 *       {@code REQUIRES_NEW}; 这里再加一层就是嵌套, 而且会让「工具失败了网页接口
 *       还能自愈」这条性质消失;</li>
 *   <li>读路径({@link #getUserPage} / {@link #getDashboard} 等)本来就不需要事务.</li>
 * </ul>
 * 需要「多个写同生共死」的地方, 用 {@link IsolatedInsert#attempt} 显式圈出那一段 ——
 * 本类的五个破坏性动作都是这个形状(动作 + 记账)。
 *
 * <p><b>五个破坏性动作都必须留下一条账。</b> 这条不变式的落点是 {@link #recordAction}:
 * 它没有 actor 就抛 403, 于是「有动作、没账本」在结构上不可能出现。别把记账挪到
 * controller 去 —— 那样「service 成功了、记账抛了」就变成一次无痕的封禁, 而且将来
 * 从 Agent 工具或别的入口调这五个方法时, 那条路径会绕过账本。
 */
@Service
public class AdminService {

    /**
     * 允许被写入 user.role 的值. 与鉴权侧认的字面量必须一致.
     *
     * <p>用 LinkedHashSet 而不是 Set.of: 这个集合会被拼进给用户看的提示语, 而
     * Set.of 的迭代顺序**每次启动都不一样**(不可变集合带了随机化的哈希盐),
     * 于是同一段代码在两次启动里会提示出「ADMIN / USER」和「USER / ADMIN」两种
     * 说法. 不是功能错误, 但会让报错截图和代码对不上, 也让「消息里有什么」这件事
     * 无法被测试钉住. 保持声明顺序即可.
     */
    private static final Set<String> ROLES =
            Collections.unmodifiableSet(new LinkedHashSet<>(List.of("USER", "ADMIN")));

    /** 账号状态的合法取值. 与鉴权侧、与 V1 建表注释里认的字面量一致 */
    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "DISABLED");

    /**
     * 状态筛选里的伪值「已锁定」.
     *
     * <p>它**不是** {@code status} 的第三个取值 —— 那一列只有 ACTIVE / DISABLED,
     * 而"锁定"是另一个字段({@code lockedUntil} 还没到期). 界面上为了只放一个下拉,
     * 把它做成了第四项; 落到 SQL 里它走 {@code FILTER_LOCKED}, 与 {@code FILTER_STATUS}
     * 互不叠加. 代价是**表达不了「已锁定 且 已禁用」**—— 这是拍板时接受的取舍.
     */
    private static final String STATUS_LOCKED = "LOCKED";

    /** 排序口径的白名单. 与前端 URL 上的 {@code sort} 同源 */
    private static final String SORT_CREATED = "createdAt";
    private static final String SORT_USERNAME = "username";

    private static final String ORDER_ASC = "asc";
    private static final String ORDER_DESC = "desc";

    /** 分页参数的默认与上限. 上限与 {@code AdminController} 上的 {@code @Max} 必须同值 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * {@code COALESCE(u.createdAt, :epoch)} 的兜底值.
     *
     * <p>它的**值无所谓** —— 排序的第一键已经把缺时间的行分到最后一组, 组内第二键
     * 彼此相等, 由 {@code u.id} 定序. 写它只是为了让"ORDER BY 里没有 NULL"这句话
     * 字面成立(理由见 {@code UserQueries.ORDER_CREATED_DESC}).
     */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    private final UserRepository userRepository;
    private final ReviewRepository reviewRepository;
    private final TrackingRepository trackingRepository;
    private final AnimeRepository animeRepository;
    private final AdminActionLogRepository adminActionLogRepository;
    private final IsolatedInsert isolatedInsert;
    private final PasswordEncoder passwordEncoder;

    public AdminService(UserRepository userRepository,
                        ReviewRepository reviewRepository,
                        TrackingRepository trackingRepository,
                        AnimeRepository animeRepository,
                        AdminActionLogRepository adminActionLogRepository,
                        IsolatedInsert isolatedInsert,
                        PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.reviewRepository = reviewRepository;
        this.trackingRepository = trackingRepository;
        this.animeRepository = animeRepository;
        this.adminActionLogRepository = adminActionLogRepository;
        this.isolatedInsert = isolatedInsert;
        this.passwordEncoder = passwordEncoder;
    }

    /** 校验管理员身份 */
    public void checkAdmin(User user) {
        if (user == null || !"ADMIN".equals(user.getRole())) {
            throw BusinessException.forbidden("无管理员权限");
        }
    }

    // ========== 用户管理 ==========

    /**
     * 全部用户(无筛选), 注册时间倒序.
     *
     * <p><b>为什么在有了 {@link #getUserPage} 之后还留着它.</b> 它唯一的调用方是
     * {@code AdminTools} 的两处, 而那两处要的是**全量**语义: 一处是把列表截前 30 条
     * 交给助手, 另一处(周报的 {@code summarizeUsers})是按 role/status 统计**全体**
     * 用户的构成. 把它们指到分页那条上, 周报的 byRole / byStatus 会静默变成
     * "前 20 个人的构成" —— 错得没有任何人会看出来. 所以两个入口并存是有意的,
     * 不是漏了合并.
     *
     * <p>走的是取页那条查询的无筛选 + {@code Pageable.unpaged()} 版本, 而不是另立一条
     * order by 语句: 否则"用户列表的默认顺序"会有两份物理定义, 迟早对不上.
     */
    public List<Map<String, Object>> getUserList() {
        return toAdminUserRows(userRepository.findUserPageByCreatedDesc(
                null, null, null, null, LocalDateTime.now(), EPOCH, Pageable.unpaged()));
    }

    /**
     * 管理端用户列表: 关键词 / 角色 / 状态筛选 + 排序 + 分页.
     *
     * <p><b>未知的 role / status / sort 一律走默认, 不返回 400.</b> 与
     * {@link #setUserRole} 对未知角色返回 400 的不对称是刻意的: 那条是**写** —— 写错的
     * 角色会造出一个权限与名字不符的账号, 而且没有任何东西会报出来; 这里读错一个值
     * 只是把结果集放宽一点. 与「页码给垃圾值就当第 1 页」是同一条道理: 手改过的 URL
     * 不该把页面变成一个错误屏.
     *
     * <p>反过来说, {@code role=ADMIN } (尾随空格)这类输入会被当成"不筛角色"而显示
     * 全部用户, 界面上却看着在筛 —— 已知的取舍, 见提交说明.
     *
     * @param keyword 关键词, 命中用户名或邮箱; 空白等于不筛
     * @param role    {@code USER} / {@code ADMIN}, 其他值等于不筛
     * @param status  {@code ACTIVE} / {@code DISABLED} / {@code LOCKED}(伪值), 其他值等于不筛
     * @param sort    {@code createdAt}(默认) / {@code username}
     * @param order   {@code asc} / {@code desc}; 不认识或没给时按该列的**自然首向**
     *                (时间给最新在前, 名字给 A→Z). 这一条不能简化成"不是 asc 就是 desc"
     *                —— 那样 {@code ?sort=username} 会变成倒序, 而前端正是把这个组合
     *                当作默认值、不写进 URL 的
     */
    public Map<String, Object> getUserPage(String keyword, String role, String status,
                                           String sort, String order, int page, int limit) {
        String keywordPattern = keywordPatternOf(keyword);
        String roleKey = roleOf(role);
        StatusFilter statusFilter = statusFilterOf(status);

        int safePage = Math.max(page, 1);
        // limit < 1 用默认值而不是 1: 一页一行既没有用, 又和"分页坏了"长得一模一样.
        // 上限那一侧正常由 AdminController 的 @Max(100) 先拦(400), 这里是兜底 ——
        // service 不该假设自己只被那一个入口调用.
        int safeLimit = limit < 1 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        LocalDateTime now = LocalDateTime.now();

        // count 先算: 越界页也要报真实 total. 报 0 的话前端按 ceil(total/limit) 算出来的
        // 翻页控件会凭空少几页, 用户从最后一页往回点就回不去了 —— 与
        // AnimeService.getFilteredPage 同一个口径.
        long matched = userRepository.countUsers(
                keywordPattern, roleKey, statusFilter.status(), statusFilter.locked(), now);
        int total = (int) Math.min(matched, Integer.MAX_VALUE);

        // (long) 那层不能省: page 接近 Integer.MAX_VALUE 时 int 乘法会溢出成负数,
        // 而库收到的是"从负数开始取一页" —— 两个库的表现既不统一也不报错.
        long offset = (long) (safePage - 1) * safeLimit;
        if (offset >= total || offset > PageResults.MAX_SQL_OFFSET) {
            return PageResults.of(Collections.emptyList(), total, safePage);
        }

        Pageable pageable = PageRequest.of(safePage - 1, safeLimit);
        boolean ascending = isAscending(sort, order);
        List<User> rows;
        if (SORT_USERNAME.equals(sort)) {
            rows = ascending
                    ? userRepository.findUserPageByUsernameAsc(
                            keywordPattern, roleKey, statusFilter.status(), statusFilter.locked(),
                            now, pageable)
                    : userRepository.findUserPageByUsernameDesc(
                            keywordPattern, roleKey, statusFilter.status(), statusFilter.locked(),
                            now, pageable);
        } else {
            rows = ascending
                    ? userRepository.findUserPageByCreatedAsc(
                            keywordPattern, roleKey, statusFilter.status(), statusFilter.locked(),
                            now, EPOCH, pageable)
                    : userRepository.findUserPageByCreatedDesc(
                            keywordPattern, roleKey, statusFilter.status(), statusFilter.locked(),
                            now, EPOCH, pageable);
        }
        return PageResults.of(toAdminUserRows(rows), total, safePage);
    }

    /**
     * {@code order} 是否升序.
     *
     * <p>没给(或给了不认识的值)时用**该列的自然首向**: 注册时间给最新在前, 用户名给
     * A→Z. 不能简化成"不是 asc 就是 desc": 前端把 {@code sort=username&order=asc}
     * 当作默认组合、**不写进 URL**, 于是分享出去的链接就是光秃秃的 {@code ?sort=username}
     * —— 那条规则会让它翻成倒序, 而点表头点出来的却是正序.
     */
    private static boolean isAscending(String sort, String order) {
        if (ORDER_ASC.equals(order)) {
            return true;
        }
        if (ORDER_DESC.equals(order)) {
            return false;
        }
        return SORT_USERNAME.equals(sort);
    }

    /**
     * 关键词 → LIKE 模式串.
     *
     * <p>空白或空串一律当作"没有关键词", 而不是"匹配全部" ——
     * {@code SearchPatterns.contains("")} 返回的是 {@code "%%"}(匹配全部), 与
     * {@code prefix("")} 返回 null 不同. 少了这道挡板, 一个空的关键词框会变成一条恒真的
     * OR 分支.
     *
     * <p>{@code trim()} 不是装饰: {@code "   "} 会变成 {@code "%   %"} —— 一个看着像
     * "没筛"、实际什么都不匹配的条件.
     */
    private static String keywordPatternOf(String keyword) {
        return (keyword == null || keyword.isBlank()) ? null : SearchPatterns.contains(keyword.trim());
    }

    /** 未知角色当成"不筛角色". 为什么读不报 400 而写报, 见 {@link #getUserPage} */
    private static String roleOf(String role) {
        if (role == null) {
            return null;
        }
        String v = role.trim().toUpperCase(Locale.ROOT);
        return ROLES.contains(v) ? v : null;
    }

    /**
     * 状态筛选解析出来的两个正交条件: 按 {@code status} 筛 与 按"当前已锁定"筛.
     *
     * <p>之所以要一个小类型而不是两个 String: 这两者**互斥**(LOCKED 是伪值, 不叠加
     * status), 用两个各自可空的局部变量表达, 很容易在某一处不小心让它们同时非空,
     * 那样 {@code status=LOCKED} 会变成"已锁定的活跃账号", 而列表上空无一人时
     * 没人分得清是"没有锁定的账号"还是"条件写拧了".
     */
    private record StatusFilter(String status, Boolean locked) {
        static final StatusFilter NONE = new StatusFilter(null, null);
        static final StatusFilter LOCKED_ONLY = new StatusFilter(null, Boolean.TRUE);
    }

    private static StatusFilter statusFilterOf(String status) {
        if (status == null || status.isBlank()) {
            return StatusFilter.NONE;
        }
        String v = status.trim().toUpperCase(Locale.ROOT);
        if (STATUS_LOCKED.equals(v)) {
            return StatusFilter.LOCKED_ONLY;
        }
        return USER_STATUSES.contains(v) ? new StatusFilter(v, null) : StatusFilter.NONE;
    }

    /**
     * {@code User} → 给管理端看的行.
     *
     * <p>抽出来是因为它现在有**两个**调用点({@link #getUserList} 与
     * {@link #getUserPage}), 而八个键里有一个是有讲究的, 见下面 {@code locked}.
     */
    private static List<Map<String, Object>> toAdminUserRows(List<User> users) {
        List<Map<String, Object>> result = new ArrayList<>(users.size());
        for (User u : users) {
            result.add(toAdminUserRow(u));
        }
        return result;
    }

    private static Map<String, Object> toAdminUserRow(User u) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", u.getId());
        map.put("username", u.getUsername());
        map.put("email", u.getEmail());
        map.put("role", u.getRole());
        map.put("status", u.getStatus());
        map.put("createdAt", u.getCreatedAt());
        // 给管理端看的锁定状态. 用 isLocked() 而不是「lockedUntil 非空」:
        // 过期的锁定时间戳仍然留在字段里, 按非空判断会把早已自动解锁的账号
        // 显示成「已锁定」, 管理员就会去点一个没有意义的解锁按钮.
        //
        // 这个字段与 SQL 侧的 FILTER_LOCKED 说的是同一件事, 判据也必须一致:
        // 那边写的是 lockedUntil > :now, 也就是这里的 isLocked().
        map.put("locked", u.isLocked());
        map.put("lockedUntil", u.isLocked() ? u.getLockedUntil() : null);
        return map;
    }

    /**
     * 禁用/启用用户.
     *
     * <p>{@code actor} 由 controller 递进来(同 {@link #setUserRole} 的理由: service 里
     * 拿不到登录态), 而账本必须记下是谁按的。
     *
     * <p>整个动作包在 {@link IsolatedInsert#attempt} 里, 与那条账**同一个事务**。
     * 拆成两段会出现「人封了、账没记」或反过来, 而且两种都不报错 —— 账本一旦漏记就再也
     * 补不回来, 所以让它们同生共死。
     *
     * <p>校验(找不到就 404、管理员账号不能动)在 {@code save} **之前**, 所以失败路径天然
     * 不产生账本行; 异常从 {@code attempt} 里抛出去时, 那个独立事务连带回滚。
     */
    public void toggleUserStatus(User actor, Long targetUserId) {
        isolatedInsert.attempt(() -> {
            User user = userRepository.findById(targetUserId)
                    .orElseThrow(() -> BusinessException.notFound("用户不存在"));
            if ("ADMIN".equals(user.getRole())) {
                throw BusinessException.badRequest("不能操作管理员账号");
            }
            boolean disabling = "ACTIVE".equals(user.getStatus());
            user.setStatus(disabling ? "DISABLED" : "ACTIVE");
            userRepository.save(user);
            recordAction(actor, disabling ? AdminActionLog.USER_BAN : AdminActionLog.USER_UNBAN,
                    AdminActionLog.TARGET_USER, user.getId(),
                    (disabling ? "禁用用户 " : "启用用户 ") + user.getUsername());
            return null;
        });
    }

    /**
     * 解除登录失败锁定.
     *
     * 存在的理由: 限时锁定虽然有「等 15 分钟自动解锁」这条出口, 但用户自己
     * 没有任何办法知道这一点, 也不知道等了多久. 而且如果有人恶意连试 5 次
     * 把某个账号锁上, 那 15 分钟里这个用户是完全无法自助恢复的.
     * 管理员需要一个能立刻解开的手动出口.
     */
    public void unlockUser(User actor, Long targetUserId) {
        isolatedInsert.attempt(() -> {
            User user = userRepository.findById(targetUserId)
                    .orElseThrow(() -> BusinessException.notFound("用户不存在"));
            int wasFailed = user.failedAttemptsOrZero();
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
            // 记下解锁**之前**的失败次数: 「解了几次锁」是管理员行为的画像, 而
            // 「为什么这个人总被锁」是另一件事 —— 后者只看解了几次是看不出来的,
            // 但如果连次数都不记, 事后连这条路都断了
            recordAction(actor, AdminActionLog.USER_UNLOCK, AdminActionLog.TARGET_USER, user.getId(),
                    "解除用户 " + user.getUsername() + " 的登录锁定（此前连续失败 " + wasFailed + " 次）");
            return null;
        });
    }

    /**
     * 修改用户角色.
     *
     * <p>以前这个方法只有「查出来、set、存回去」三步, 角色值原样落库. 三个口子
     * 合起来能把人锁在门外:
     *
     * <ul>
     *   <li>角色值不校验 —— 传个 "ADMIN " / "admin" / "SUPER" 都能存进去.
     *       而鉴权那一侧认的是字面量 "ADMIN", 于是库里出现一个「看名字像管理员、
     *       实际什么权限都没有」的账号, 没有任何地方会报错;</li>
     *   <li>能改自己 —— 管理员手滑把自己降成 USER, 当场失去管理端, 而且没有
     *       任何自助恢复的入口(改角色这个动作本身就要管理员权限);</li>
     *   <li>能把最后一个管理员降级 —— 结果同上, 只是需要别人来点这一下:
     *       整个系统再没有任何账号进得了管理端.</li>
     * </ul>
     *
     * <p>角色用白名单而不是黑名单: 这一列直接决定能拿到哪些接口, 黑名单漏一个值就是
     * 一个越权口子, 白名单漏了顶多是「某个合法值暂时用不了」, 失败方向是安全的.
     *
     * <p>残留的窗口: 「数到还剩一个管理员」和「写下去」之间不是原子的, 两个管理员
     * 同时降级对方时理论上都能通过检查. 要彻底堵死得靠数据库层的约束或加锁,
     * 而这里要防的是手滑和误操作, 不是两个管理员合谋把自己锁死, 所以没上那一层.
     */
    public void setUserRole(User actor, Long targetUserId, String role) {
        if (role == null || !ROLES.contains(role)) {
            throw BusinessException.badRequest(
                    "角色只能是 " + String.join(" / ", ROLES) + " 之一");
        }

        isolatedInsert.attempt(() -> {
            User target = userRepository.findById(targetUserId)
                    .orElseThrow(() -> BusinessException.notFound("用户不存在"));

            if (actor != null && actor.getId().equals(target.getId())) {
                throw BusinessException.badRequest("不能修改自己的角色");
            }

            // 不变式: 这次操作之后, 系统里至少还得剩下一个管理员
            if ("ADMIN".equals(target.getRole()) && !"ADMIN".equals(role)
                    && userRepository.countByRole("ADMIN") <= 1) {
                throw BusinessException.badRequest("这是最后一个管理员, 不能降级");
            }

            // 改之前那一侧也要记: 只记「改成了 ADMIN」的话, 事后分不清这是把谁提上来的,
            // 还是把谁降下去之后又提回来
            String from = target.getRole();
            target.setRole(role);
            userRepository.save(target);
            recordAction(actor, AdminActionLog.USER_ROLE, AdminActionLog.TARGET_USER, target.getId(),
                    "把用户 " + target.getUsername() + " 的角色从 " + from + " 改为 " + role);
            return null;
        });
    }

    // ========== 重置密码 ==========

    /**
     * 管理员替某个用户重置密码.
     *
     * <p><b>为什么不验旧密码。</b> 这正是「重置」与「修改」的分界: 用户自己改要走
     * {@code UserService.changePassword} 并验旧密码, 因为那条路只凭一张 token 就能进;
     * 管理员这条路是**管理权限**授权的, 而它的使用场景恰恰是用户拿不出旧密码的时候
     * (忘了、或者账号被盗需要夺回)。要求管理员知道旧密码, 这个功能就没有存在意义了。
     *
     * <p>代价是这个端点很重, 所以它有两道约束:
     * <ul>
     *   <li><b>必须记账</b> —— 它不改变权限, 却是唯一一个能把人挡在门外的非权限动作:
     *       重置之后那个人手上所有 token 立刻作废, 而能不能再进来取决于有没有人
     *       把新密码告诉他。滥用它的后果与封禁接近;</li>
     *   <li><b>不能重置自己</b> —— 自己走用户侧那条(要验旧密码)。这既是防呆, 也让
     *       「管理员能不能绕过旧密码校验改掉自己的密码」这个问题根本不存在, 少一条
     *       需要论证的路径。</li>
     * </ul>
     *
     * <p>密码强度校验不在这里做: {@code ResetPasswordRequest} 上的 {@code @Size} +
     * {@code @Pattern} 已经在校验层拦过一道, 且那两个注解引用的是 {@code PasswordPolicy}
     * 的常量 —— 从这里再抄一份判断, 就会出现两处阈值各自演化的裂缝。
     *
     * <p>走 {@code IsolatedInsert} 而不是 {@code @Transactional}: 「改密 + 记账」是两次写,
     * 必须同生共死(理由见类注释)。少了记账这件事不会有人发现, 而它正是这个动作最该
     * 留下的东西。
     */
    public void resetPassword(User actor, Long targetUserId, String newPassword) {
        isolatedInsert.attempt(() -> {
            User target = userRepository.findById(targetUserId)
                    .orElseThrow(() -> BusinessException.notFound("用户不存在"));

            if (actor != null && actor.getId() != null && actor.getId().equals(target.getId())) {
                throw BusinessException.badRequest("请使用个人中心的修改密码");
            }

            target.setPassword(passwordEncoder.encode(newPassword));
            // 与 UserService.changePassword 同一件事、同一理由: 写密码和写时刻分开就没有
            // 意义了 —— 只改密码不记时刻, 被盗账号上那张旧 token 会一直活到 7 天过期,
            // 而「强制重置」要的正是把它掐掉
            target.setPasswordChangedAt(LocalDateTime.now());
            userRepository.save(target);

            recordAction(actor, AdminActionLog.USER_PASSWORD_RESET, AdminActionLog.TARGET_USER,
                    target.getId(), "重置了用户 " + target.getUsername() + " 的密码");
            return null;
        });
    }

    // ========== 评论管理 ==========

    /**
     * 获取所有评论列表.
     *
     * <p>用 JOIN FETCH 一次把作者带回来: {@code Review.user} 是 LAZY 的, 而每一行
     * 都要读作者名和 id —— 不 fetch 就是「有几条评论就查几次用户」. 评论越多越慢,
     * 而这是管理端首页, 恰好是评论最多的那类库最常被打开.
     */
    public List<Map<String, Object>> getAllReviews() {
        List<Review> reviews = reviewRepository.findAllWithUser(Pageable.unpaged());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Review r : reviews) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.getId());
            map.put("subjectId", r.getSubjectId());
            map.put("username", r.getUser().getUsername());
            map.put("userId", r.getUser().getId());
            map.put("rating", r.getRating());
            map.put("content", r.getContent());
            // 赞数: 管理端拿它判断"这条是不是该被处理的热评". 列在 review 表上, 读实体
            // 就顺手带回来了, 不额外发查询(与评论列表那条走批量查询的理由不同 ——
            // 那里是每行都要问一次"我赞过没有", 这里只是读同一行的列).
            map.put("likeCount", r.getLikeCount());
            map.put("createdAt", r.getCreatedAt());
            result.add(map);
        }
        return result;
    }

    /**
     * 管理员删除任意评论.
     *
     * <p>为什么要先确认存在, 而不是直接 deleteById: Spring Data JPA 3.2 的 deleteById
     * 实现是 {@code findById(id).ifPresent(this::delete)} —— 目标不存在时它**什么都不做,
     * 也不抛异常**. 也就是说删一个不存在的 id 会一路走到「评论已删除」这个 200 上
     * (实测确认: 接口确实回 200). 管理员在列表上点删除、而那条已经被作者自己删掉时,
     * 他拿到的是一句成功的谎话, 分不清「删掉了」和「这条本来就没有」.
     *
     * <p>顺带一提, 更早的 Spring Data 版本这里抛 EmptyResultDataAccessException, 而项目
     * 没有为它登记处理器, 表现是 500「服务器错误」. 两种错法不同, 但都不是这里该有的
     * 答复 —— 「目标不存在」不是服务端故障.
     *
     * <p>统一成 404, 与用户自己删评论那条路一致(ReviewService.deleteReview 就是
     * findById 后抛 notFound): 同一种情况在同一个系统里只该有一种答复.
     * 另一条路(把删除做成幂等, 不存在也回 200)也说得通, 但那样这两个接口对同一件事
     * 会给出不同答复, 所以没选.
     *
     * <p><b>这一段以前写的是 existsById + deleteById</b>, 上面那句「残留的窗口」就是在说
     * 那两句话之间的空隙。加了账本之后它顺带被关掉了: 账里要写被删评论的作者和正文摘要,
     * 于是这里必须把那一行**读出来**, 变成 findById + delete, 而两句在同一个事务里 ——
     * 中间再没有可以让别人插进来的地方。
     */
    public void deleteAnyReview(User actor, Long reviewId) {
        isolatedInsert.attempt(() -> {
            Review review = reviewRepository.findById(reviewId)
                    .orElseThrow(() -> BusinessException.notFound("评论不存在"));

            // 三样都要在事务结束**之前**读出来: Review.user 是 LAZY 的, 而 IsolatedInsert
            // 的约定正是「实体出了这个事务就脱离持久化上下文」—— 出去之后再取作者名会炸
            String authorName = review.getUser().getUsername();
            Integer subjectId = review.getSubjectId();
            String snippet = TextSnippet.of(review.getContent());

            reviewRepository.delete(review);

            // detail 里带上作者名与正文摘要, 而不是只留一个 reviewId: 评论已经删了,
            // 事后想弄清「删的是哪条」就只能靠这一行字
            recordAction(actor, AdminActionLog.REVIEW_DELETE, AdminActionLog.TARGET_REVIEW, reviewId,
                    "删除用户 " + authorName + " 在作品 " + subjectId + " 下的评论"
                            + (snippet == null ? "（无正文）" : "：" + snippet));
            return null;
        });
    }

    // ========== 操作账本 ==========

    /**
     * 管理端操作日志: 按 action 精确筛 + 分页, 时间倒序.
     *
     * <p>骨架与 {@link #getUserPage} 逐条相同(夹取 page/limit → 先 count → long 偏移量
     * 溢出保护 → 越界返回空页 → 取页), 理由也一样, 这里不重复。两处唯一的不同是本页
     * **没有排序参数** —— 账本只有「最新的在最上面」这一种读法, 加排序开关属于为不存在
     * 的需求写代码。
     *
     * <p>未知的 {@code action} 当作**不筛**、不返回 400: 与 {@link #getUserPage} 对未知
     * role/status 的口径一致 —— 手改过的 URL 不该把页面变成一个错误屏。
     */
    public Map<String, Object> getActionPage(String action, int page, int limit) {
        String actionFilter = AdminActionLog.ACTIONS.contains(action) ? action : null;

        int safePage = Math.max(page, 1);
        int safeLimit = limit < 1 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);

        long matched = adminActionLogRepository.countPage(actionFilter);
        int total = (int) Math.min(matched, Integer.MAX_VALUE);

        long offset = (long) (safePage - 1) * safeLimit;
        if (offset >= total || offset > PageResults.MAX_SQL_OFFSET) {
            return PageResults.of(Collections.emptyList(), total, safePage);
        }

        Pageable pageable = PageRequest.of(safePage - 1, safeLimit);
        return PageResults.of(
                toActionRows(adminActionLogRepository.findPage(actionFilter, pageable)),
                total, safePage);
    }

    /**
     * {@code AdminActionLog} → 给管理端看的行.
     *
     * <p>{@code targetId} 原样给 id 而不是解析成名字: 账本的整个立意就是「不依赖目标还在
     * 不在」, 拿 id 回两张表去查名字等于把这个前提推翻。可读的那部分由 {@code actorName}
     * 与 {@code detail} 提供 —— detail 在**写的时候就**带上了目标的名字快照。
     */
    private static List<Map<String, Object>> toActionRows(List<AdminActionLog> logs) {
        List<Map<String, Object>> result = new ArrayList<>(logs.size());
        for (AdminActionLog l : logs) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", l.getId());
            map.put("action", l.getAction());
            map.put("actorName", l.getActorName());
            map.put("targetType", l.getTargetType());
            map.put("targetId", l.getTargetId());
            map.put("detail", l.getDetail());
            map.put("createdAt", l.getCreatedAt());
            result.add(map);
        }
        return result;
    }

    /**
     * 记一条账 —— 四个破坏性动作**唯一的出口**, 且必须在它们各自那个事务里被调用.
     *
     * <p>没有 actor 就抛 403, 而不是写一行空的操作者: 那一列是 NOT NULL, 而这里抛出去会
     * 把整个动作一起回滚 —— 于是「有动作、没账本」在结构上不可能出现。正常情况下走到这里
     * actor 一定非空(controller 前面那道 {@code checkAdmin} 已经拦过 null), 所以这条是
     * 兜底而不是常规路径。
     *
     * <p>写的是**快照**: {@code actorName} 取当前这一刻的用户名, 而不是留个 id 等读的时候
     * 再去 join —— 账本要活到那个账号改名或消失之后。
     *
     * <p>{@code actor.getId()} 判空是因为 {@code User.builder()} 造出来的对象可以没有 id
     * (测试里就有这种), 而 {@code actor_id} 同样是 NOT NULL。
     */
    private void recordAction(User actor, String action, String targetType, Long targetId, String detail) {
        if (actor == null || actor.getId() == null) {
            throw BusinessException.forbidden("无管理员权限");
        }
        adminActionLogRepository.save(AdminActionLog.builder()
                .actorId(actor.getId())
                .actorName(actor.getUsername())
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .detail(detail)
                .build());
    }

    // ========== 数据统计 ==========

    /** 系统仪表盘数据 */
    public Map<String, Object> getDashboard() {
        Map<String, Object> data = new HashMap<>();
        data.put("totalUsers", userRepository.count());
        data.put("adminUsers", userRepository.countByRole("ADMIN"));
        data.put("activeUsers", userRepository.countByStatus("ACTIVE"));
        data.put("disabledUsers", userRepository.countByStatus("DISABLED"));
        data.put("totalReviews", reviewRepository.count());
        data.put("totalTrackings", trackingRepository.count());
        return data;
    }

    /**
     * 全站热度榜: 追番人数最多的前 N 部作品.
     *
     * 用数据库层 GROUP BY 聚合, 而不是逐个番剧去数 —— 后者是典型的 N+1 查询,
     * 番剧一多就会把数据库打满.
     */
    public List<Map<String, Object>> getAnimeHeatRanking(int topN) {
        List<Object[]> rows = trackingRepository.findSubjectTrackingCounts(PageRequest.of(0, topN));
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<Integer> subjectIds = rows.stream()
                .map(r -> (Integer) r[0])
                .collect(Collectors.toList());

        Map<Integer, Anime> byId = animeRepository.findAllById(subjectIds).stream()
                .collect(Collectors.toMap(Anime::getId, a -> a, (a, b) -> a));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Integer subjectId = (Integer) row[0];
            long count = ((Number) row[1]).longValue();
            Anime anime = byId.get(subjectId);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("subjectId", subjectId);
            item.put("name", displayName(anime));
            item.put("trackingCount", count);
            item.put("rating", anime != null ? anime.getRating() : null);
            // 这个榜目前只被 AI 助手消费, 它要把榜单渲染成卡片, 没有封面就只剩一排文字
            item.put("cover", anime != null ? anime.getCoverUrl() : null);
            result.add(item);
        }
        return result;
    }

    /** 优先用中文名, 缺失时回退日文原名 */
    private String displayName(Anime anime) {
        if (anime == null) {
            return "未知作品";
        }
        if (anime.getTitleCn() != null && !anime.getTitleCn().isBlank()) {
            return anime.getTitleCn();
        }
        return anime.getTitle();
    }
}
