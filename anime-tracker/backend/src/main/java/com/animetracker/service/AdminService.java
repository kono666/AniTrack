package com.animetracker.service;

import com.animetracker.entity.AdminActionLog;
import com.animetracker.entity.Anime;
import com.animetracker.entity.AnimeTracking;
import com.animetracker.entity.User;
import com.animetracker.entity.Review;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.AdminActionLogRepository;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.EpisodeWatchedRepository;
import com.animetracker.repository.UserRepository;
import com.animetracker.repository.ReviewReportRepository;
import com.animetracker.repository.ReviewRepository;
import com.animetracker.repository.TrackingRepository;
import com.animetracker.util.CoverImages;
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
import java.util.stream.Stream;

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

    /**
     * 最近登录. {@code last_login_at} 可空 —— 为空读作「注册后从未登录过」,
     * 排序里这些行恒定排在最后(见 {@code UserQueries.ORDER_LAST_LOGIN_DESC}).
     */
    private static final String SORT_LAST_LOGIN = "lastLoginAt";

    private static final String ORDER_ASC = "asc";
    private static final String ORDER_DESC = "desc";

    /**
     * 评论列表的排序键白名单.
     *
     * <p><b>默认键是 {@code id}, 不是 {@code createdAt}。</b> 两者在界面上给的是同一个序
     * (自增主键的顺序就是入库顺序), 但 {@code review.created_at} <b>可空</b> ——
     * 按它排就得在 JPQL 里写「先分组 NULL + COALESCE + id 兜底」那三段式才让 H2 与 PG
     * 给出同一个序, 而 {@code id} 是主键、永远非空, 那三段一段都不需要。
     * 代价是 URL 上写的是 {@code sort=id}: 它是对外契约的一部分, 前端的列头因此
     * 显示为「时间」而值是主键序(前端那一侧有一段注释说明这件事)。
     */
    private static final String SORT_ID = "id";
    private static final String SORT_LIKES = "likes";
    private static final String SORT_REPLIES = "replies";

    /** 评论排序键的白名单 */
    private static final Set<String> REVIEW_SORTS = Set.of(SORT_ID, SORT_LIKES, SORT_REPLIES);

    /**
     * 评分档位. 键 → {@code [min, max]} 闭区间.
     *
     * <p>三个档而不是 1~10 的十个值: 后台是一个下拉, 十个选项没人会去点, 而管理员
     * 真正会做的判断只有「差评有哪些」。区间的边界(1–4 / 5–7 / 8–10)与前端下拉的
     * 文案必须一致 —— 那三行是同一个约定的两处写法。
     *
     * <p>用 {@code Map.of} 而不是写三个 if: 键是 URL 直接带上来的字符串, 用 Map 查
     * 就天然是"白名单"语义, 未知值拿到 null 当作不筛(读路径不报 400, 理由见
     * {@link #getUserPage})。
     */
    private static final Map<String, RatingBand> RATING_BANDS = Map.of(
            "low", new RatingBand(1, 4),
            "mid", new RatingBand(5, 7),
            "high", new RatingBand(8, 10));

    /** 分页参数的默认与上限. 上限与 {@code AdminController} 上的 {@code @Max} 必须同值 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 用户详情页那三个小列表各取多少条. **无分页** —— 它们是「这个人干了什么」的样张,
     * 不是列表页, 所以没有 page 参数可以喂给它。
     *
     * <p>取值与列表页的默认每页同数: 这一页要让管理员一眼看出「这个号是活的还是死的」,
     * 20 条足够看出行为模式。真正想翻遍一个人的全部评论, 那要的是「按 userId 维度筛评论」,
     * 属于另一个需求(见提交说明里写的范围边界)。
     */
    private static final int DETAIL_LIST_LIMIT = 20;

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
    private final ReviewReportRepository reviewReportRepository;
    private final EpisodeWatchedRepository episodeWatchedRepository;
    private final IsolatedInsert isolatedInsert;
    private final PasswordEncoder passwordEncoder;

    public AdminService(UserRepository userRepository,
                        ReviewRepository reviewRepository,
                        TrackingRepository trackingRepository,
                        AnimeRepository animeRepository,
                        AdminActionLogRepository adminActionLogRepository,
                        ReviewReportRepository reviewReportRepository,
                        EpisodeWatchedRepository episodeWatchedRepository,
                        IsolatedInsert isolatedInsert,
                        PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.reviewRepository = reviewRepository;
        this.trackingRepository = trackingRepository;
        this.animeRepository = animeRepository;
        this.adminActionLogRepository = adminActionLogRepository;
        this.reviewReportRepository = reviewReportRepository;
        this.episodeWatchedRepository = episodeWatchedRepository;
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
     * @param sort    {@code createdAt}(默认) / {@code username} / {@code lastLoginAt}
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
        } else if (SORT_LAST_LOGIN.equals(sort)) {
            rows = ascending
                    ? userRepository.findUserPageByLastLoginAsc(
                            keywordPattern, roleKey, statusFilter.status(), statusFilter.locked(),
                            now, EPOCH, pageable)
                    : userRepository.findUserPageByLastLoginDesc(
                            keywordPattern, roleKey, statusFilter.status(), statusFilter.locked(),
                            now, EPOCH, pageable);
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
     * <p>没给(或给了不认识的值)时用**该列的自然首向**: 两列时间(注册、最近登录)都给
     * 最新在前, 用户名给 A→Z. 不能简化成"不是 asc 就是 desc": 前端把
     * {@code sort=username&order=asc} 当作默认组合、**不写进 URL**, 于是分享出去的链接
     * 就是光秃秃的 {@code ?sort=username} —— 那条规则会让它翻成倒序, 而点表头点出来的
     * 却是正序.
     *
     * <p>实现刻意是"只有 username 才算升序"这一句, 而不是一张 sort → 首向的表:
     * 加一列排序键时**默认就落进"最新在前"**, 而那正是所有时间列该有的自然首向;
     * 真出现一个自然首向是 A→Z 的新列, 那一句会显式地摆在这里让人看见. 反过来写成表,
     * 漏一行的表现是"点一次列头写出的 URL(不带 order)与表头 caret 指的方向不一致",
     * 而那只在分享链接/刷新时才出现.
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
     * {@link #getUserPage}), 而九个键里有两个是有讲究的, 见下面 {@code locked} 与
     * {@code lastLoginAt}.
     *
     * <p>键集本身是对外契约的一部分: {@code AdminUserListIntegrationTest} 有一条
     * {@code containsExactlyInAnyOrder} 钉着它, 加键必须连那条一起改 —— 它是「这个接口
     * 到底发出去什么」唯一的守卫.
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
        // 原样发出去, **不在服务端把 null 换成「从未登录」之类的文案**: 换掉之后就分不清
        // 「这个账号没登录过」和「服务端没发这个键」, 而前端要按前者渲染成「-」.
        map.put("lastLoginAt", u.getLastLoginAt());
        return map;
    }

    /**
     * 单个用户的详情: 账号事实 + 四个计数 + 三个小列表.
     *
     * <p><b>为什么要有这一页。</b> 列表页能回答「有哪些人」, 回答不了「这个人干了什么」。
     * 管理员面对一个举报或者一个可疑的号, 之前能做的只有翻列表 —— 而列表里没有追番、
     * 没有评论、没有「别人对他做过什么」。这一条把这三样凑齐, 于是走查第 8 条
     * 「用户画像点不进去」有了去处。
     *
     * <p><b>{@code locked} 与列表行用同一套判据({@code u.isLocked()})</b>, 不另写一个
     * 「lockedUntil 非空即锁定」: 那两处一旦分叉, 列表说「正常」而详情说「已锁定」,
     * 或者反过来, 而两边看着都像是对的。理由与 {@link #toAdminUserRow} 里那段相同。
     *
     * <p><b>语句条数是 9, 与数据无关。</b> 一次 findById + 四次 count + 三次取页 +
     * 一次 findAllById(番剧名/封面)。三次取页都固定取 {@code DETAIL_LIST_LIMIT} 条,
     * 所以追番 500 部的人和追番 3 部的人代价一样。
     *
     * <p>唯一一处随数据变的是最后那次 {@code findAllById}: <b>收到空集合时 Spring Data
     * 直接返回空表、一条 SQL 都不发</b>, 于是「既没追番又没评论」的账号是 8 条。
     * 语句计数用例按有数据的账号钉 9 —— <b>不要为了凑常数在代码里补一次空查询</b>,
     * 也不要在这里加「两个列表都空就提前返回」的早退: 那会把 9 变成 8, 然后有人把断言
     * 放宽成「≤ 9」, 那条用例从此不再守卫任何东西。
     *
     * <p><b>三个列表都是数组, 空的时候是空数组而不是缺键。</b> 缺键会让前端的
     * {@code list.length} 落到 undefined 上, 看着与空数组一样, 但「服务端没发这个键」
     * 这种真实的故障就再也看不出来了。
     *
     * <p><b>不复用 {@link #toAdminReviewRows}</b>, 尽管键长得很像。那个方法会
     * (一)按这一页的 id 白跑一次举报聚合(详情页不展示举报), (二)读 {@code r.getUser()}
     * 取作者名 —— 而这一页的作者就是主角本人。而这里的评论查询刻意没有
     * {@code JOIN FETCH r.user}(见 {@code ReviewQueries.PAGE_USER_CREATED_DESC}),
     * 那一句要么多触发一次懒加载、要么直接抛 {@code LazyInitializationException}。
     *
     * @throws com.animetracker.exception.BusinessException 404, 用户不存在
     */
    public Map<String, Object> getUserDetail(Long userId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));

        // 四个计数. 在架与被移除分开数, 不相减 —— 相减要求两个数来自同一瞬间(见
        // ReviewRepository.countByUserAndDeletedAtIsNotNull 的注释).
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("trackings", trackingRepository.countByUser(u));
        counts.put("reviewsAlive", reviewRepository.countByUserAndDeletedAtIsNull(u));
        counts.put("reviewsRemoved", reviewRepository.countByUserAndDeletedAtIsNotNull(u));
        counts.put("episodesWatched", episodeWatchedRepository.countByUser(u));

        Pageable first = PageRequest.of(0, DETAIL_LIST_LIMIT);
        List<AnimeTracking> trackings = trackingRepository.findByUserOrderByUpdatedAtDesc(u, first);
        List<Review> reviews = reviewRepository.findPageByUserOrderByCreatedAtDesc(u, EPOCH, first);
        // 别人对这个账号做过的动作. 走的是与账本列表页同一条语句、同一份行构造器, 只是
        // 把 target 那两个谓词填上 —— 于是「管理员在这里看到的」与「账本里记的」必然一致.
        List<AdminActionLog> actions =
                adminActionLogRepository.findPage(null, AdminActionLog.TARGET_USER, userId, first);

        // 番剧名/封面: 两个列表的 subjectId **合并成一次查询**. 分两次查也能用, 但那样
        // 「详情页发几条语句」就多出一个与数据无关的常数, 语句计数用例会变成在数实现细节.
        //
        // distinct() 不能省: 同一个用户追着又评论过的番会同时出现在两个列表里, 不去重就
        // 白读一遍那一行. 收集成 List 而不是 Set —— 与 TrackService.getUserTrackings 同形.
        List<Integer> subjectIds = Stream.concat(
                        trackings.stream().map(AnimeTracking::getSubjectId),
                        reviews.stream().map(Review::getSubjectId))
                .distinct()
                .collect(Collectors.toList());
        Map<Integer, Anime> byId = animeRepository.findAllById(subjectIds).stream()
                .collect(Collectors.toMap(Anime::getId, a -> a, (a, b) -> a));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", u.getId());
        data.put("username", u.getUsername());
        data.put("email", u.getEmail());
        data.put("avatar", u.getAvatar());
        data.put("role", u.getRole());
        data.put("status", u.getStatus());
        data.put("createdAt", u.getCreatedAt());
        data.put("lastLoginAt", u.getLastLoginAt());
        data.put("locked", u.isLocked());
        data.put("lockedUntil", u.isLocked() ? u.getLockedUntil() : null);
        data.put("counts", counts);
        data.put("trackings", toUserDetailTrackingRows(trackings, byId));
        data.put("reviews", toUserDetailReviewRows(reviews, byId));
        data.put("actions", toActionRows(actions));
        return data;
    }

    /**
     * 详情页的追番行.
     *
     * <p>比 {@code TrackService.getUserTrackings} 少了 {@code score}/{@code notes}:
     * 详情页是「他追了什么」的样张, 而私密的评语与打分不属于管理员要看的账号事实。
     * 与 {@link #toAdminReviewRows} 里「不一起给 deletedBy」是同一条取舍。
     */
    private static List<Map<String, Object>> toUserDetailTrackingRows(
            List<AnimeTracking> trackings, Map<Integer, Anime> byId) {
        List<Map<String, Object>> result = new ArrayList<>(trackings.size());
        for (AnimeTracking t : trackings) {
            Anime anime = byId.get(t.getSubjectId());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("subjectId", t.getSubjectId());
            map.put("animeTitle", anime == null ? null : animeTitleOf(anime));
            map.put("animeCover", anime == null ? null : CoverImages.proxied(anime.getCoverUrl()));
            map.put("status", t.getStatus());
            map.put("progress", t.getProgress());
            map.put("updatedAt", t.getUpdatedAt());
            result.add(map);
        }
        return result;
    }

    /**
     * 详情页的评论行. {@code deletedAt} 非空就是「已移除」, 由前端打徽章。
     *
     * <p>没有 {@code username}/{@code userId}: 作者就是这一页的主角。也没有点赞/回复/
     * 举报数 —— 那些是「该不该处理这条评论」的依据, 属于评论管理页, 而详情页要看的是
     * 「这个人写了些什么」。
     */
    private static List<Map<String, Object>> toUserDetailReviewRows(
            List<Review> reviews, Map<Integer, Anime> byId) {
        List<Map<String, Object>> result = new ArrayList<>(reviews.size());
        for (Review r : reviews) {
            Anime anime = byId.get(r.getSubjectId());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", r.getId());
            map.put("subjectId", r.getSubjectId());
            map.put("animeTitle", anime == null ? null : animeTitleOf(anime));
            map.put("rating", r.getRating());
            map.put("content", r.getContent());
            map.put("deletedAt", r.getDeletedAt());
            map.put("createdAt", r.getCreatedAt());
            result.add(map);
        }
        return result;
    }

    /**
     * 番剧名, <b>查不到就给 null</b>。
     *
     * <p>刻意**不用** {@link #displayName} 的「未知作品」兜底: 本地没缓存过那部番是
     * <b>真信息</b>(番剧是按需从外部 API 拉进 {@code anime} 表的), 编一个假名字比空着
     * 更糟 —— 几条不同 subjectId 的记录会挤在同一个假名字下面。前端拿到 null 时退化成
     * 「番剧 #656083」。这一条与 {@link #toAdminReviewRows} 的口径一致。
     */
    private static String animeTitleOf(Anime anime) {
        String cn = anime.getTitleCn();
        return (cn != null && !cn.isBlank()) ? cn : anime.getTitle();
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
     * 管理端评论列表: 关键词 / 评分档位筛选 + 排序 + 分页.
     *
     * <p>骨架与 {@link #getUserPage} 逐条相同(夹取 page/limit → 先 count → long 偏移量
     * 溢出保护 → 越界返回空页 → 取页), 那一段的注释已经把每条理由写过了, 这里只记
     * 三处**不一样**的:
     *
     * <p><b>一、没有"已删除"这个维度。</b> 这一轮评论仍然是**硬删**(软删属于这条线的
     * 第三个提交), 所以列表里出现的就是全部还在的评论, 不存在"要不要显示已删除"的
     * 开关。等软删落地时, 那个开关加在这里, 而不是加在 SQL 的 WHERE 里偷偷过滤掉。
     *
     * <p><b>二、排序键没有"每列各自的自然首向"。</b> {@code id}/{@code likes}/{@code replies}
     * 三列的自然首向**都是 desc**(最新在前、赞多的在前、回复多的在前), 于是
     * {@link #isAscending} 那条"按列查表"的规则在这里塌缩成一句
     * {@code ORDER_ASC.equals(order)}。前端"默认值不写进 URL"的规矩因此也只剩一个默认
     * 组合(排序键本身除外), 不会出现"分享出去的链接点出来是反的"那种错。
     *
     * <p><b>三、行里多两样东西: 番剧名与回复数。</b> 前者要一次批量查询(见
     * {@link #toAdminReviewRows}), 后者是 {@code review.reply_count} 这一列直接读出来的
     * —— 改前前端就在渲染 {@code r.replyCount}, 而后端从来没发过这个键, 于是那一项
     * **永不显示**。它不是新功能, 是一件"写了一半"的东西。
     *
     * <p><b>四、{@code reported} 是三个筛选里唯一一个布尔开关。</b> 它不是"再筛一个字段",
     * 是**换一个队列看**: 打开之后列表里剩下的正是「有人在等一个答复」的那些评论。
     * 判据(待处理 = {@code status='PENDING'} 且评论还在)只有一处定义, 在
     * {@link ReviewQueries#FILTER_REPORTED} 里。
     *
     * @param keyword  关键词, 命中评论正文或作者名; 空白等于不筛
     * @param rating   档位键 {@code low}/{@code mid}/{@code high}, 其他值等于不筛
     * @param reported {@code true} 表示只看有待处理举报的; 其他值(包括不给)不筛
     * @param sort     {@code id}(默认) / {@code likes} / {@code replies}, 其他值等于默认
     * @param order    {@code asc} / {@code desc}; 不认识或没给时一律 desc(见上面第二条)
     */
    public Map<String, Object> getReviewPage(String keyword, String rating, String reported,
                                             String sort, String order, int page, int limit) {
        String keywordPattern = keywordPatternOf(keyword);
        RatingBand band = ratingBandOf(rating);
        boolean reportedOnly = reportedOnlyOf(reported);

        int safePage = Math.max(page, 1);
        int safeLimit = limit < 1 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);

        // count 先算, 理由同 getUserPage: 越界页也要报真实 total, 否则前端的翻页控件
        // 会凭空少几页, 用户从最后一页往回点就回不去了
        long matched = reviewRepository.countAdminReviews(
                keywordPattern, band.min(), band.max(), reportedOnly);
        int total = (int) Math.min(matched, Integer.MAX_VALUE);

        long offset = (long) (safePage - 1) * safeLimit;
        if (offset >= total || offset > PageResults.MAX_SQL_OFFSET) {
            return PageResults.of(Collections.emptyList(), total, safePage);
        }

        Pageable pageable = PageRequest.of(safePage - 1, safeLimit);
        boolean ascending = ORDER_ASC.equals(order);
        // ⚠️ `sort == null` 那一半**不能省**, 也不能改成 `REVIEW_SORTS.contains(sort)` 一句:
        // REVIEW_SORTS 是 Set.of 建的不可变集合, 而不可变集合的 contains(null) 抛
        // NullPointerException(HashSet 返回 false)。少了它, 「不带 sort 参数的默认请求」
        // —— 也就是**绝大多数请求**, 以及两个 AI 工具传 null 的那条路 —— 全部 500。
        // ORDER_ASC.equals(order) 那行之所以没这个毛病, 是因为它是 String.equals, 天生吃 null。
        // 同一个坑在 AdminActionLog.ACTIONS 上不存在, 只因为那里用的是 LinkedHashSet,
        // 不能据此以为"本仓的 Set 都吃得下 null"。
        String sortKey = (sort != null && REVIEW_SORTS.contains(sort)) ? sort : SORT_ID;

        List<Review> rows;
        if (SORT_REPLIES.equals(sortKey)) {
            rows = ascending
                    ? reviewRepository.findAdminReviewPageByRepliesAsc(
                            keywordPattern, band.min(), band.max(), reportedOnly, pageable)
                    : reviewRepository.findAdminReviewPageByRepliesDesc(
                            keywordPattern, band.min(), band.max(), reportedOnly, pageable);
        } else if (SORT_LIKES.equals(sortKey)) {
            rows = ascending
                    ? reviewRepository.findAdminReviewPageByLikesAsc(
                            keywordPattern, band.min(), band.max(), reportedOnly, pageable)
                    : reviewRepository.findAdminReviewPageByLikesDesc(
                            keywordPattern, band.min(), band.max(), reportedOnly, pageable);
        } else {
            rows = ascending
                    ? reviewRepository.findAdminReviewPageByIdAsc(
                            keywordPattern, band.min(), band.max(), reportedOnly, pageable)
                    : reviewRepository.findAdminReviewPageByIdDesc(
                            keywordPattern, band.min(), band.max(), reportedOnly, pageable);
        }
        return PageResults.of(toAdminReviewRows(rows), total, safePage);
    }

    /**
     * {@code reported=true}(不区分大小写、忽略首尾空白)才算"只看有待处理举报的".
     *
     * <p>其余一切值 —— 包括 {@code null}、空串、{@code "false"}、{@code "yes"}、
     * {@code "1"} —— 一律当作**不筛**, 不返回 400。这条与上面 {@link #ratingBandOf}
     * 是同一条 doctrine(读路径的取值写错只是把结果集放宽一点), 也与前端"默认值不写进
     * URL"的规矩对齐: 那一侧把开关关掉时是把这个键**删掉**, 而不是写一个 {@code false}
     * 进来 —— 于是 {@code null} 才是常态, 它必须等于"不筛"。
     *
     * <p>那为什么不顺手也认 {@code "1"} / {@code "yes"}: 每多认一个写法, 「什么算 true」
     * 就多一处定义, 而这一处的收益是零 —— 调用方只有本仓的前端, 它传的就是字面量
     * {@code "true"}。
     */
    private static boolean reportedOnlyOf(String reported) {
        return reported != null && "true".equalsIgnoreCase(reported.trim());
    }

    /**
     * 评分档位解析出来的闭区间: {@code [min, max]}, 或者两个都为 null 表示不筛.
     *
     * <p>用一个小类型而不是两个各自可空的局部变量, 理由与 {@link StatusFilter} **逐字
     * 相同**: 这两者必须同时有值或同时为空, 用两个变量表达迟早会出现"只有 min 没有 max"
     * 这种半截条件 —— 而它在列表上表现为"筛出来的东西说不清是按什么筛的"。
     * 一个 {@code Integer min} 为 null 而 {@code max} 不为 null 的状态在这里**表达不出来**,
     * 这正是要的。
     *
     * <p>区间值是常量表里的那两个数, 不来自请求 —— 请求只带一个档位键, 越界的评分
     * 因此不是一个可能的输入。
     */
    private record RatingBand(Integer min, Integer max) {
        static final RatingBand NONE = new RatingBand(null, null);
    }

    /** 未知档位当成"不筛档位". 为什么读路径不报 400, 见 {@link #getUserPage} */
    private static RatingBand ratingBandOf(String rating) {
        if (rating == null) {
            return RatingBand.NONE;
        }
        RatingBand band = RATING_BANDS.get(rating.trim().toLowerCase(Locale.ROOT));
        return band == null ? RatingBand.NONE : band;
    }

    /**
     * {@code Review} → 给管理端看的行, 顺带批量补上番剧名.
     *
     * <p><b>番剧名是一次 {@code findAllById} 拿回来的整页, 不是逐条查。</b> 形状照
     * {@link #getAnimeHeatRanking}(同一个类里已经有的那种批量补全)。番剧名这一格
     * 改前**根本不存在** —— 列表上只有一串裸的 {@code subjectId}, 管理员看不出这是
     * 哪部番。
     *
     * <p><b>{@code animeTitle} 允许为 null。</b> {@code review.subject_id} 与 {@code anime}
     * 之间**没有外键**, 本地也不一定缓存过那部番(番剧数据是按需从外部 API 拉进
     * {@code anime} 表的)。查不到就给 null, 由前端退化成「番剧 #656083」——
     * 编一个假名字比空着更糟, 而"这个 id 对应的番剧还没进本地库"本身是**真信息**。
     *
     * <p>空页提前返回, 于是那两次批量查询不会为一个空列表白跑一遍 —— 但**它并不是
     * 越界页的那道防线**: {@link #getReviewPage} 里 {@code offset >= total} 的提前返回
     * 排在取页之前, 越界页根本走不到这里(那里只发一条 count). 这个守卫够得着的是
     * 另一种情况: count 那一刻 offset 还够、真去取页时那一页已被别人删空(并发下的
     * 窗口), 单线程测不到. 两处都留着, 一处省一次主键扫描, 一处是并发下的第二道.
     *
     * <p><b>举报那三样也是一次批量聚合, 而且照发不误。</b>
     * {@code findPendingSummaries} 按 {@code review_id IN (...)} 一次问完这一页
     * (走 {@code uk_review_report_review_reporter} 的最左前缀), 不是逐条 count.
     * 与番剧名不同的是: 它**不按内容分支** —— 这一页一条举报都没有时那条查询照样发.
     * 于是「管理端评论列表一页要发几条语句」是一个与数据无关的常数, 语句计数用例
     * 才能钉住它(照数据分支的话, 用例得先造出举报才看得见那一条).
     */
    private List<Map<String, Object>> toAdminReviewRows(List<Review> reviews) {
        if (reviews.isEmpty()) {
            return Collections.emptyList();
        }

        List<Integer> subjectIds = reviews.stream()
                .map(Review::getSubjectId)
                .distinct()
                .collect(Collectors.toList());
        Map<Integer, Anime> byId = animeRepository.findAllById(subjectIds).stream()
                .collect(Collectors.toMap(Anime::getId, a -> a, (a, b) -> a));

        List<Long> reviewIds = reviews.stream().map(Review::getId).collect(Collectors.toList());
        // 投影是 {reviewId, reason, createdAt}, 按 rr.id DESC 排 => 同一条评论的第一行
        // 就是最近那条举报. 计数在 Java 侧数一下即可(一页几十行, 不差这一次哈希).
        Map<Long, Object[]> latestReport = new HashMap<>();
        Map<Long, Integer> reportCounts = new HashMap<>();
        for (Object[] row : reviewReportRepository.findPendingSummaries(reviewIds)) {
            Long reviewId = (Long) row[0];
            latestReport.putIfAbsent(reviewId, row);
            reportCounts.merge(reviewId, 1, Integer::sum);
        }

        List<Map<String, Object>> result = new ArrayList<>(reviews.size());
        for (Review r : reviews) {
            Anime anime = byId.get(r.getSubjectId());
            Object[] report = latestReport.get(r.getId());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", r.getId());
            map.put("subjectId", r.getSubjectId());
            map.put("animeTitle", anime == null ? null : displayName(anime));
            map.put("username", r.getUser().getUsername());
            map.put("userId", r.getUser().getId());
            map.put("rating", r.getRating());
            map.put("content", r.getContent());
            // 赞数与回复数: 管理端拿它们判断"这条是不是该被处理的热评".
            // 两列都在 review 表上(V7/V8 建的非空冗余列), 读实体就顺手带回来了,
            // 不额外发查询(与用户侧评论列表那条不同 —— 那里每行都要问一次"我赞过没有").
            map.put("likeCount", r.getLikeCount());
            map.put("replyCount", r.getReplyCount());
            // 举报三样. **没有举报时是 0 / null / null, 不是缺键** —— 与 likeCount 那种
            // "老后端不发"的情况不同: 这三个是管理端自己的聚合, 不存在"对面版本旧"这回事,
            // 而缺键会让前端的 `row.reportCount > 0` 走 undefined 分支, 看着与 0 一样.
            // 计数只算 PENDING(被忽略掉的举报不该继续在列表上留一个待办标记).
            map.put("reportCount", reportCounts.getOrDefault(r.getId(), 0));
            map.put("latestReason", report == null ? null : report[1]);
            map.put("latestReportAt", report == null ? null : report[2]);
            // 被管理员移除的时间(V14): null = 在架上. 这一整列**只有管理端看得见** ——
            // 被移除的行照样出现在这一页里(它要能被恢复), 界面靠这个键决定显示"已移除"
            // 还是"删除"按钮.
            //
            // 给时间戳而不是一个 deleted 布尔: 这一页本来就是给管理员看的, 而"什么时候
            // 移除的"是他判断该不该恢复的信息之一(与给用户侧的 getUserReview 相反,
            // 那边刻意只给布尔).
            //
            // 不一起给 deletedBy: 显示"谁移除的"要么多一个 join, 要么每行一次懒加载,
            // 而这一页的语句条数是一条用例钉着的常数. 要读人名的地方是账本(操作日志页),
            // 它写的时候就存了名字快照. 完整理由写在 Review.deletedBy 的注释上.
            map.put("deletedAt", r.getDeletedAt());
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
     *
     * <p><b>V14 起删的是"可见性", 不是那一行</b> —— 置 {@code deletedAt/deletedBy} 而不是
     * {@code delete}, 于是这个动作变成可撤销的(见 {@link #restoreAnyReview})。它原本是这一
     * 组里唯一不可逆的那个(封禁能解、角色能改回来、锁能解、密码能重置, 而删掉的评论连正文
     * 都找不回来), 补上软删之后五个动作就都留了退路。库级那条 {@code ON DELETE CASCADE}
     * 仍然留着, 它守的是另外三条**真删**的路径(用户删自己的评论、删号), 见 V13 末尾的注记。
     *
     * <p>两处"怎么算不存在"要说清, 因为它们看起来像同一个判断:
     * <ul>
     *   <li>{@code findById} 取不到 → 404「评论不存在」(目标本来就没有);</li>
     *   <li>取到了但已经是"已移除" → 400「该评论已被移除」而不是再记第二笔账 ——
     *       重复点删除(或两个管理员同时点)不该产生两条 REVIEW_DELETE, 那会让账本读起来
     *       像"删了两次"。返回 404 也是错的: 那一行明明就在列表里给管理员看着。</li>
     * </ul>
     */
    public void deleteAnyReview(User actor, Long reviewId) {
        isolatedInsert.attempt(() -> {
            Review review = reviewRepository.findById(reviewId)
                    .orElseThrow(() -> BusinessException.notFound("评论不存在"));
            if (review.isRemoved()) {
                throw BusinessException.badRequest("该评论已被移除");
            }

            // 三样都要在事务结束**之前**读出来: Review.user 是 LAZY 的, 而 IsolatedInsert
            // 的约定正是「实体出了这个事务就脱离持久化上下文」—— 出去之后再取作者名会炸.
            // (V14 之后还要多一样:`deletedBy` 只存一个 id, 而账本里要有**名字** ——
            //  恢复那条路也同样要把这两样先读出来)
            String authorName = review.getUser().getUsername();
            Integer subjectId = review.getSubjectId();
            String snippet = TextSnippet.of(review.getContent());

            review.setDeletedAt(LocalDateTime.now());
            review.setDeletedBy(actor == null ? null : actor.getId());
            reviewRepository.save(review);

            // detail 里带上作者名与正文摘要, 而不是只留一个 reviewId: 评论已经从用户侧
            // 消失了, 事后想弄清「移除的是哪条」就只能靠这一行字
            //
            // 措辞是「移除」而不是「删除」: V14 起这句话描述的那个动作是可撤销的, 而
            // 撤销它就是 {@link #restoreAnyReview} 记的「恢复…」那一行 —— 一前一后读起来
            // 才是一件事的两半。(c94 写下的那批老记录仍是「删除…」, 账本不改写历史.)
            recordAction(actor, AdminActionLog.REVIEW_DELETE, AdminActionLog.TARGET_REVIEW, reviewId,
                    "移除用户 " + authorName + " 在作品 " + subjectId + " 下的评论"
                            + (snippet == null ? "（无正文）" : "：" + snippet));
            return null;
        });
    }

    /**
     * 撤销一次移除(V14) —— 把一条被管理员删掉的评论放回架上.
     *
     * <p><b>为什么恢复也要记一笔账。</b> 账本里那条 REVIEW_DELETE 不会因为撤销而消失
     * (账本记的是发生过的事实), 所以"这条评论后来怎么了"必须由一条新记录来回答 ——
     * 只有删除记录的话, 事后看到的就是「某年某月被删了」, 而它现在明明在列表上,
     * 读账的人会以为是账本坏了。两个动作各占一行, 时间顺序就是真实经过。
     *
     * <p>写的是 {@code REVIEW_RESTORE}, 与删除同属一本账(通用的 admin_action_log),
     * <b>没有第二本"评论专用账"</b> —— 管理端所有的处置都在操作日志页上, 按时间排开,
     * 一条评论的来龙去脉就是一前一后两行。
     *
     * <p>{@code deletedBy} 一并清空: 那两个字段的约定是**同时有值或同时为空**(见 V14),
     * 而"谁把它撤下来的"这个身份已经由这一行账记着了, 不必在 review 表上留半截状态。
     *
     * <p>状态判断与删除那条对称: 没被移除的 → 400「该评论未被移除」(同样是"状态不对",
     * 不是"不存在"); 已经是"在架上"了还回 200 的话, 账本里会多出一条什么都没做的
     * REVIEW_RESTORE。
     */
    public void restoreAnyReview(User actor, Long reviewId) {
        isolatedInsert.attempt(() -> {
            Review review = reviewRepository.findById(reviewId)
                    .orElseThrow(() -> BusinessException.notFound("评论不存在"));
            if (!review.isRemoved()) {
                throw BusinessException.badRequest("该评论未被移除");
            }

            String authorName = review.getUser().getUsername();
            Integer subjectId = review.getSubjectId();
            String snippet = TextSnippet.of(review.getContent());

            review.setDeletedAt(null);
            review.setDeletedBy(null);
            reviewRepository.save(review);

            recordAction(actor, AdminActionLog.REVIEW_RESTORE, AdminActionLog.TARGET_REVIEW, reviewId,
                    "恢复用户 " + authorName + " 在作品 " + subjectId + " 下的评论"
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

        // 后两个参数恒为 null: 「按 target 筛」是用户详情页的内部能力, **刻意不从
        // GET /api/admin/actions 暴露** —— 本轮没有前端在用, 而多一个公开口子就要多一份
        // 守卫。仓储层那两个谓词于是各自短路成真, 这一页的效果与它们不存在时逐字相同。
        // 真要按目标查账的那一天, 从这里把签名改回去即可, 仓储那边一个字不用动。
        long matched = adminActionLogRepository.countPage(actionFilter, null, null);
        int total = (int) Math.min(matched, Integer.MAX_VALUE);

        long offset = (long) (safePage - 1) * safeLimit;
        if (offset >= total || offset > PageResults.MAX_SQL_OFFSET) {
            return PageResults.of(Collections.emptyList(), total, safePage);
        }

        Pageable pageable = PageRequest.of(safePage - 1, safeLimit);
        return PageResults.of(
                toActionRows(adminActionLogRepository.findPage(actionFilter, null, null, pageable)),
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
        data.put("totalReviews", reviewRepository.countByDeletedAtIsNull());
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
            item.put("cover", anime != null ? CoverImages.proxied(anime.getCoverUrl()) : null);
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
