package com.animetracker.service;

import com.animetracker.entity.AdminActionLog;
import com.animetracker.entity.Anime;
import com.animetracker.entity.AnimeTracking;
import com.animetracker.entity.Review;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.AdminActionLogRepository;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.EpisodeWatchedRepository;
import com.animetracker.repository.ReviewRepository;
import com.animetracker.repository.ReviewReportRepository;
import com.animetracker.repository.TrackingRepository;
import com.animetracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端那些**写**动作的护栏: 改角色 / 封禁 / 解锁 / 删评论(含 V14 的软删与恢复),
 * 以及它们留下的账.
 *
 * <p>这些规则的价值全在「写错了不会报错」上: 白名单漏了, 库里会多出一个
 * 名字像管理员、权限却不是的账号, 没有任何一处会报错; 少拦一次「改自己」或
 * 「降级最后一个管理员」, 系统会在某一次点击之后突然**再也没有人能进管理端**,
 * 而那次点击看起来和平时任何一次成功操作一模一样.
 *
 * <p>所以「角色白名单」那一组里每个用例都同时断言两件事: 抛出的是 400(而不是 500,
 * 也不是静默通过), 以及**什么都没写进库** —— 只在抛异常之前先 save 了一下, 异常照样抛,
 * 但数据已经坏了.
 *
 * <p>「账本」那一组的失效方式与上面那组不同, 更安静: 漏记一条账, 动作照样生效、
 * 接口照样 200、界面上一模一样, 只是事后再也查不到是谁按的那一下.
 */
class AdminServiceTest {

    /**
     * 假编码器吐出来的「密文」.
     *
     * <p>它是**常量**, 不是 {@code encode(明文)} —— 于是断言里出现它就意味着
     * 「这一列确实是被 {@code encode} 的返回值写进去的」, 而不是明文原样落库。
     * 这类断言只有在这个值跟任何明文都不一样时才成立。
     */
    private static final String ENCODED_PASSWORD = "$2a$10$encoded-by-mock";

    private UserRepository userRepository;
    private ReviewRepository reviewRepository;
    private AdminActionLogRepository adminActionLogRepository;
    // 下面三个只有 getUserDetail 那一组用得上, 但一样提成字段: 留在构造调用里内联
    // mock 的话, 新用例就没有办法给它们打桩了.
    private TrackingRepository trackingRepository;
    private AnimeRepository animeRepository;
    private EpisodeWatchedRepository episodeWatchedRepository;
    private TrackingIsolatedInsert isolatedInsert;
    private PasswordEncoder passwordEncoder;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        reviewRepository = mock(ReviewRepository.class);
        adminActionLogRepository = mock(AdminActionLogRepository.class);
        trackingRepository = mock(TrackingRepository.class);
        animeRepository = mock(AnimeRepository.class);
        episodeWatchedRepository = mock(EpisodeWatchedRepository.class);
        // 用真的那个, 不用 mock: IsolatedInsert.attempt 的语义就是「把回调跑一遍」,
        // 换成 mock 之后四个动作会一起变成空操作, 而那一组用例的断言全都还在,
        // 于是它们会以「什么都没发生」的形式静默失真. 子类只是多记一个「回调开着呢」.
        isolatedInsert = new TrackingIsolatedInsert();
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn(ENCODED_PASSWORD);
        adminService = new AdminService(userRepository, reviewRepository,
                trackingRepository, animeRepository,
                adminActionLogRepository, mock(ReviewReportRepository.class),
                episodeWatchedRepository, isolatedInsert, passwordEncoder);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * 能回答「记账那一刻, 我们是不是还在 {@code attempt} 里面」的那个 {@link IsolatedInsert}.
     *
     * <p>单元测试里没有真正的事务管理器(注解在这条路径上本来也不生效), 所以「同一个事务」
     * 这件事在这一层唯一观察得到的形状就是「回调跑到哪儿了」。真正的回滚由
     * {@code AdminActionLogRollbackTest} 在真库上验 —— 那个才是这条约定最终的证伪点。
     */
    private static final class TrackingIsolatedInsert extends IsolatedInsert {
        private boolean inside;

        @Override
        public <T> T attempt(Supplier<T> insert) {
            inside = true;
            try {
                return insert.get();
            } finally {
                inside = false;
            }
        }

        boolean isInside() {
            return inside;
        }
    }

    private static User user(Long id, String username, String role) {
        return User.builder().id(id).username(username).role(role).status("ACTIVE").build();
    }

    /** 取出这次唯一写下的那条账. 多于一条会在这里直接报出来, 而不是悄悄取第一条 */
    private AdminActionLog onlyAuditRow() {
        ArgumentCaptor<AdminActionLog> captor = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(adminActionLogRepository).save(captor.capture());
        return captor.getValue();
    }

    // ========== 角色值白名单 ==========

    @Test
    @DisplayName("不认识的角色值直接拒绝 —— 存进去就是一个权限语义对不上的账号")
    void rejectsUnknownRole() {
        User actor = user(1L, "admin", "ADMIN");
        User target = user(2L, "bob", "USER");
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));

        for (String bogus : new String[]{"SUPER", "admin", "Admin", "ADMIN ", "", "root"}) {
            assertThatThrownBy(() -> adminService.setUserRole(actor, 2L, bogus))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
        }

        // 一个都不该落库: 大小写不同、多个空格都是不同的字符串, 鉴权侧认的是字面量 "ADMIN"
        verify(userRepository, never()).save(any(User.class));
        assertThat(target.getRole()).isEqualTo("USER");
    }

    /**
     * 提示语里那两个值的顺序要稳定.
     *
     * <p>这条看着像在测文案, 实际测的是「这个集合是不是 Set.of」: Set.of 的迭代
     * 顺序每次启动都不一样, 同一段代码会在两次运行里提示出两种说法. 顺序不可
     * 预期本身不会让谁出错, 但报错截图和代码对不上、也没法把「消息里有什么」
     * 钉成断言 —— 所以这里把顺序也一起钉住.
     */
    @Test
    @DisplayName("提示语里列出合法取值, 顺序固定为 USER / ADMIN")
    void roleHintMentionsTheAllowedValuesInAFixedOrder() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "bob", "USER")));

        assertThatThrownBy(() -> adminService.setUserRole(user(1L, "admin", "ADMIN"), 2L, "SUPER"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("角色只能是 USER / ADMIN 之一");
    }

    @Test
    @DisplayName("null 角色值也拒绝, 而不是把 null 写进 NOT NULL 的列换个 500 回来")
    void rejectsNullRole() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "bob", "USER")));

        assertThatThrownBy(() -> adminService.setUserRole(user(1L, "admin", "ADMIN"), 2L, null))
                .isInstanceOf(BusinessException.class);
        verify(userRepository, never()).save(any(User.class));
    }

    // ========== 不能把自己锁在门外 ==========

    @Test
    @DisplayName("不能修改自己的角色 —— 改完就没有任何入口能改回来了")
    void refusesToChangeOwnRole() {
        User self = user(1L, "admin", "ADMIN");
        when(userRepository.findById(1L)).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> adminService.setUserRole(self, 1L, "USER"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("自己");

        verify(userRepository, never()).save(any(User.class));
        assertThat(self.getRole()).isEqualTo("ADMIN");
    }

    // ========== 系统里必须剩下管理员 ==========

    /**
     * 这里直接在验「不变式」本身: 操作之后系统里必须还剩至少一个管理员.
     *
     * <p>它的 count 桩成 1 而操作者又自称 ADMIN, 看着不自洽 —— 这是故意的:
     * 规则不该依赖操作者手里的 token 是否新鲜(JWT 里的 role 是签发时的快照,
     * 库里可能早就变了), 也不该依赖「改自己」那一栏是否还拦得住.
     * 独立地钉住这条不变式, 将来谁把上面那条自保规则的顺序挪了、删了,
     * 这一条仍然会响.
     */
    @Test
    @DisplayName("最后一个管理员不能被降级 —— 降完整个系统再没人进得了管理端")
    void refusesToDemoteTheLastAdmin() {
        User actor = user(1L, "admin", "ADMIN");
        User other = user(2L, "boss", "ADMIN");
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));
        when(userRepository.countByRole("ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> adminService.setUserRole(actor, 2L, "USER"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最后一个管理员");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("还有别的管理员时, 降级是允许的")
    void allowsDemotionWhileAnotherAdminRemains() {
        User actor = user(1L, "admin", "ADMIN");
        User other = user(2L, "boss", "ADMIN");
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));
        when(userRepository.countByRole("ADMIN")).thenReturn(2L);

        adminService.setUserRole(actor, 2L, "USER");

        assertThat(other.getRole()).isEqualTo("USER");
        verify(userRepository).save(other);
    }

    @Test
    @DisplayName("往上升不受管理员数量影响: 一个管理员也没有时, 提升依然要能做")
    void promotionIsNeverBlockedByAdminCount() {
        User actor = user(1L, "admin", "ADMIN");
        User target = user(2L, "bob", "USER");
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));

        adminService.setUserRole(actor, 2L, "ADMIN");

        assertThat(target.getRole()).isEqualTo("ADMIN");
        verify(userRepository).save(target);
    }

    @Test
    @DisplayName("改一个不存在的用户: 404, 不是 500")
    void unknownTargetIsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.setUserRole(user(1L, "admin", "ADMIN"), 99L, "USER"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
    }

    @Test
    @DisplayName("把已经是 USER 的人再设成 USER: 幂等通过, 不报错")
    void settingTheSameRoleIsAllowed() {
        User actor = user(1L, "admin", "ADMIN");
        User target = user(2L, "bob", "USER");
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));

        adminService.setUserRole(actor, 2L, "USER");

        assertThat(target.getRole()).isEqualTo("USER");
    }

    // ========== 用户列表: 参数白名单与夹取 ==========
    //
    // 这一组不起库, 只问"发给仓储的**条件**是什么". 之所以要单独钉一遍: 读路径对
    // 垃圾值走**默认**而不是 400, 所以"认不出来"和"认成了另一个值"在 HTTP 层
    // 完全同形 —— 接口两份都回 200, 只是结果集不一样. 只有在这一层才分得出来.

    /** 默认桩: 计数给个非零值, 否则取页那一段会因为"越界"而根本不执行 */
    private void stubUserPage(long matched) {
        when(userRepository.countUsers(any(), any(), any(), any(), any())).thenReturn(matched);
        when(userRepository.findUserPageByCreatedDesc(
                any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(userRepository.findUserPageByCreatedAsc(
                any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(userRepository.findUserPageByUsernameAsc(
                any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(userRepository.findUserPageByUsernameDesc(
                any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(userRepository.findUserPageByLastLoginDesc(
                any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(userRepository.findUserPageByLastLoginAsc(
                any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("认不出来的 role / status 当作「不筛」, 而不是把那个字符串发给数据库")
    void unknownRoleAndStatusBecomeNoFilter() {
        stubUserPage(1);

        adminService.getUserPage(null, "SUPER", "BOGUS", null, null, 1, 20);

        // 原样下发的话 `WHERE u.role = 'SUPER'` 会匹配到零行 —— 界面上是"这个站
        // 没有用户", 而接口 200, 没有任何东西报错
        verify(userRepository).countUsers(isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("status=LOCKED 落在锁定条件上, 且**不同时**筛 status")
    void lockedIsAPseudoValueOnTheOtherColumn() {
        stubUserPage(1);

        adminService.getUserPage(null, null, "LOCKED", null, null, 1, 20);

        // 叠加的话就成了"已锁定的活跃账号"; 列表空着的时候没人分得清是"没有锁定的
        // 账号"还是"条件写拧了" —— 这是拍板时接受的那个取舍的落点
        verify(userRepository).countUsers(isNull(), isNull(), isNull(), eq(Boolean.TRUE), any());
    }

    @Test
    @DisplayName("空白关键词等于不筛 —— 空串会拼出 '%%'(匹配全部), 不是 '没有关键词'")
    void blankKeywordMeansNoFilter() {
        stubUserPage(1);

        for (String blank : new String[]{null, "", "   ", "\t"}) {
            adminService.getUserPage(blank, null, null, null, null, 1, 20);
        }

        verify(userRepository, times(4)).countUsers(isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("关键词前后的空格不进模式串")
    void keywordIsTrimmedBeforeBecomingAPattern() {
        stubUserPage(1);

        adminService.getUserPage("  bob  ", null, null, null, null, 1, 20);

        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        verify(userRepository).countUsers(pattern.capture(), isNull(), isNull(), isNull(), any());
        assertThat(pattern.getValue()).isEqualTo("%bob%");
    }

    @Test
    @DisplayName("页码小于 1 当作第 1 页; 每页条数小于 1 回到默认而不是 1 条")
    void pageAndLimitAreClamped() {
        stubUserPage(500);

        adminService.getUserPage(null, null, null, null, null, 0, 0);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findUserPageByCreatedDesc(
                any(), any(), any(), any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        // 1 条/页既没有用, 又和"分页坏了"长得一模一样
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("每页条数超过上限时夹到 100")
    void limitIsCappedAtTheMaximum() {
        stubUserPage(500);

        adminService.getUserPage(null, null, null, null, null, 1, 100000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findUserPageByCreatedDesc(
                any(), any(), any(), any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("排序: 默认注册时间倒序; username 不带 order 时是正序, 带 desc 才倒序")
    void sortAndOrderPickTheRightQuery() {
        stubUserPage(500);

        adminService.getUserPage(null, null, null, null, null, 1, 20);
        verify(userRepository).findUserPageByCreatedDesc(
                any(), any(), any(), any(), any(), any(), any());

        // 这一条是前后端那条约定的落点: 前端把 `sort=username&order=asc` 当作默认组合、
        // **不写进 URL**, 所以分享出去的链接就是光秃秃的 `?sort=username`.
        // 若这里按"不是 asc 就是 desc"处理, 那条链接会翻成倒序, 而界面上写着正序
        adminService.getUserPage(null, null, null, "username", null, 1, 20);
        verify(userRepository).findUserPageByUsernameAsc(
                any(), any(), any(), any(), any(), any());

        adminService.getUserPage(null, null, null, "username", "desc", 1, 20);
        verify(userRepository).findUserPageByUsernameDesc(
                any(), any(), any(), any(), any(), any());
    }

    /**
     * 「最近登录」那一列: 不带 order 时必须落进**倒序**(最近来过的在前).
     *
     * <p>这一条是「{@code isAscending} 不用改」的哨兵. 它的实现是
     * {@code SORT_USERNAME.equals(sort)} —— 于是除 username 外一律默认倒序, 新加的时间列
     * 天然落对. 若哪天有人「顺手」把它改成显式枚举、又把这一列的自然首向写成 asc,
     * 排出来的是「最久没登录的在最前」, 而表头的 caret 指着向下 —— 表头和数据说的不是
     * 一回事, 且只在分享链接/刷新时才出现.
     */
    @Test
    @DisplayName("最近登录: 不带 order 时是倒序(最近来过的在前), desc / asc 各走各的")
    void lastLoginSortDefaultsToNewestFirst() {
        stubUserPage(500);

        adminService.getUserPage(null, null, null, "lastLoginAt", null, 1, 20);
        verify(userRepository).findUserPageByLastLoginDesc(
                any(), any(), any(), any(), any(), any(), any());

        adminService.getUserPage(null, null, null, "lastLoginAt", "asc", 1, 20);
        verify(userRepository).findUserPageByLastLoginAsc(
                any(), any(), any(), any(), any(), any(), any());

        adminService.getUserPage(null, null, null, "lastLoginAt", "desc", 1, 20);
        verify(userRepository, times(2)).findUserPageByLastLoginDesc(
                any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * 两列时间可空, 所以取页那两条多一个 {@code :epoch} —— 这里顺带钉住它**真的传了值**
     * 而不是 null: 传 null 的话 JPQL 里 {@code COALESCE(u.lastLoginAt, :epoch)} 整句恒为
     * NULL, 「ORDER BY 里没有 NULL」那句话就不成立了(H2 与 PG 会把空值排在相反两端).
     */
    @Test
    @DisplayName("最近登录排序把 :epoch 传下去, 不让 ORDER BY 里出现 NULL")
    void lastLoginSortPassesEpoch() {
        stubUserPage(500);

        adminService.getUserPage(null, null, null, "lastLoginAt", null, 1, 20);

        ArgumentCaptor<LocalDateTime> epoch = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).findUserPageByLastLoginDesc(
                any(), any(), any(), any(), any(), epoch.capture(), any());
        assertThat(epoch.getValue()).isNotNull();
    }

    @Test
    @DisplayName("认不出来的 sort 落回注册时间倒序, 不抛异常")
    void unknownSortFallsBackToTheDefault() {
        stubUserPage(500);

        adminService.getUserPage(null, null, null, "bogus", "asc", 1, 20);

        verify(userRepository).findUserPageByCreatedAsc(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("越界页: 报真实的 total, 一行都不取")
    void outOfRangePageStillReportsTheRealTotal() {
        stubUserPage(30);

        Map<String, Object> result = adminService.getUserPage(null, null, null, null, null, 5, 20);

        // total 报 0 是错的口径: 前端按 ceil(total/limit) 算出来的翻页控件会凭空
        // 少几页, 用户从最后一页往回点就回不去了
        assertThat(result.get("total")).isEqualTo(30);
        assertThat((List<?>) result.get("list")).isEmpty();
        verify(userRepository, never()).findUserPageByCreatedDesc(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("getUserList 走的是同一条查询的无筛选 + 不分页版本")
    void getUserListAsksForEverything() {
        stubUserPage(3);

        adminService.getUserList();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findUserPageByCreatedDesc(
                isNull(), isNull(), isNull(), isNull(), any(LocalDateTime.class),
                any(LocalDateTime.class), pageable.capture());
        // 助手侧的 list_users 与周报的构成统计要的是**全量**: 指到分页那条上,
        // 周报的 byRole / byStatus 会静默变成"前 20 个人的构成"
        assertThat(pageable.getValue().isUnpaged()).isTrue();
    }

    // ========== 账本: 四个动作各留一条 ==========
    //
    // 这一组钉的是「有动作、没账本」在结构上不可能出现. 它不会以异常的形式表现出来:
    // 漏记一条账, 接口照样 200、动作照样生效、界面上一模一样, 只是事后再也查不到是
    // 谁按的那一下 —— 而账本一旦漏记就再也补不回来.

    @Test
    @DisplayName("禁用用户: 动作生效, 并记下一条 USER_BAN")
    void banningWritesOneAuditRow() {
        User actor = user(1L, "admin", "ADMIN");
        User target = user(2L, "bob", "USER");
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));

        adminService.toggleUserStatus(actor, 2L);

        assertThat(target.getStatus()).isEqualTo("DISABLED");
        AdminActionLog log = onlyAuditRow();
        assertThat(log.getActorId()).isEqualTo(1L);
        assertThat(log.getActorName()).isEqualTo("admin");
        assertThat(log.getAction()).isEqualTo(AdminActionLog.USER_BAN);
        assertThat(log.getTargetType()).isEqualTo(AdminActionLog.TARGET_USER);
        assertThat(log.getTargetId()).isEqualTo(2L);
        assertThat(log.getDetail()).isEqualTo("禁用用户 bob");
    }

    /**
     * 封与解是**两个** action 而不是一个带参数的: 「这个人被封过」与「这个人被解封过」
     * 是两条不同的历史, 合成一个之后按 action 筛只能筛出"被封或被解过"这一团.
     */
    @Test
    @DisplayName("启用一个已禁用的用户: 记的是 USER_UNBAN")
    void unbanningWritesUnban() {
        User target = user(2L, "bob", "USER");
        target.setStatus("DISABLED");
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));

        adminService.toggleUserStatus(user(1L, "admin", "ADMIN"), 2L);

        assertThat(target.getStatus()).isEqualTo("ACTIVE");
        AdminActionLog log = onlyAuditRow();
        assertThat(log.getAction()).isEqualTo(AdminActionLog.USER_UNBAN);
        assertThat(log.getDetail()).isEqualTo("启用用户 bob");
    }

    /**
     * 解锁要记下**解锁之前**的失败次数.
     *
     * <p>写的是 0(解锁之后的值)的话, 这条账就成了「管理员解锁了一个没有任何异常的用户」——
     * 而「这个人为什么老是被锁」正是事后唯一想从这条记录里看出来的一件事.
     */
    @Test
    @DisplayName("解锁: 记下解锁之前的失败次数, 而不是清完之后的 0")
    void unlockingRecordsTheFailureCountFromBefore() {
        User target = user(2L, "bob", "USER");
        target.setFailedAttempts(5);
        target.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));

        adminService.unlockUser(user(1L, "admin", "ADMIN"), 2L);

        assertThat(target.isLocked()).isFalse();
        assertThat(target.failedAttemptsOrZero()).isZero();
        AdminActionLog log = onlyAuditRow();
        assertThat(log.getAction()).isEqualTo(AdminActionLog.USER_UNLOCK);
        assertThat(log.getDetail()).contains("5 次");
    }

    /**
     * 改角色的账里**两端都要在**.
     *
     * <p>只记「改成了 ADMIN」的话, 事后分不清这是把谁提上来的、还是把谁降下去之后又提回来
     * —— 而提权恰恰是这四个动作里最该被查的一个.
     */
    @Test
    @DisplayName("改角色: 记下从哪个角色改到哪个角色")
    void roleChangeRecordsBothEnds() {
        User target = user(2L, "bob", "USER");
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));

        adminService.setUserRole(user(1L, "admin", "ADMIN"), 2L, "ADMIN");

        AdminActionLog log = onlyAuditRow();
        assertThat(log.getAction()).isEqualTo(AdminActionLog.USER_ROLE);
        assertThat(log.getTargetType()).isEqualTo(AdminActionLog.TARGET_USER);
        assertThat(log.getDetail()).isEqualTo("把用户 bob 的角色从 USER 改为 ADMIN");
    }

    /**
     * 删评论的账里要带**作者名与正文摘要**: 评论已经删了, 事后想弄清"删的是哪条"
     * 就只能靠这一行字, 而 reviewId 本身什么也说明不了.
     *
     * <p>顺带钉住摘要那 60 字的口径 —— 正文最长 5000 字, 原样记进 detail 就是几十 KB
     * 一行; 而这一点在两处实现(账本、收到的回复)之间漂掉时, 没有任何东西会报错.
     *
     * <p>V14 起还要钉住**它没有真的删行**: {@code reviewRepository.delete} 一次都不该被
     * 调用, 改的是那两个字段. 这条断言看着像实现细节, 其实是整个软删的地基 —— 真删掉之后
     * 恢复接口会因为找不到行而回 404, 而"删干净了"这件事本身完全静默, 界面照样显示成功.
     */
    @Test
    @DisplayName("删评论: 打的是软删标记, 记下作者与正文摘要, 摘要封顶 60 字")
    void deletingAReviewSnapshotsTheAuthorAndASnippet() {
        Review review = Review.builder()
                .id(9L).user(user(3L, "carol", "USER")).subjectId(96000201)
                .content("甲".repeat(200)).build();
        when(reviewRepository.findById(9L)).thenReturn(Optional.of(review));

        adminService.deleteAnyReview(user(1L, "admin", "ADMIN"), 9L);

        assertThat(review.isRemoved()).as("标记打上了").isTrue();
        assertThat(review.getDeletedBy()).isEqualTo(1L);
        verify(reviewRepository, never()).delete(any(Review.class));
        verify(reviewRepository).save(review);
        AdminActionLog log = onlyAuditRow();
        assertThat(log.getAction()).isEqualTo(AdminActionLog.REVIEW_DELETE);
        assertThat(log.getTargetType()).isEqualTo(AdminActionLog.TARGET_REVIEW);
        assertThat(log.getTargetId()).isEqualTo(9L);
        assertThat(log.getDetail())
                .isEqualTo("移除用户 carol 在作品 96000201 下的评论：" + "甲".repeat(60) + "…");
    }

    /** 只打分不写字的评论是合法的, 那种情况下 detail 里不能出现一个空的冒号 */
    @Test
    @DisplayName("删一条没有正文的评论: detail 里是「（无正文）」而不是一个空冒号")
    void deletingAReviewWithoutContentSaysSo() {
        Review review = Review.builder()
                .id(9L).user(user(3L, "carol", "USER")).subjectId(96000201).content(null).build();
        when(reviewRepository.findById(9L)).thenReturn(Optional.of(review));

        adminService.deleteAnyReview(user(1L, "admin", "ADMIN"), 9L);

        assertThat(onlyAuditRow().getDetail()).endsWith("（无正文）");
    }

    /**
     * 恢复: 两个字段一起清空, 并且**另记一笔** REVIEW_RESTORE.
     *
     * <p>为什么不是"把那条 REVIEW_DELETE 删掉": 账本记的是发生过的事实, 撤销是第二件事实.
     * 一条评论的来龙去脉读起来就该是两行 —— 先删后恢复, 各有时间、各有操作人(可能不是
     * 同一个管理员). 只留删除记录的话, 事后读账的人看到的是「某年某月被删了」, 而那条
     * 评论现在明明在列表上, 他会以为账本坏了.
     *
     * <p>{@code deletedBy} 也要清掉: 那两个字段的约定是同时有值或同时为空(见 V14),
     * 而"谁把它撤下来的"已经由这一行账记着了.
     */
    @Test
    @DisplayName("恢复评论: 清掉两个标记, 并另记一笔 REVIEW_RESTORE(不是抹掉原来那条)")
    void restoringClearsTheMarkerAndRecordsItsOwnAction() {
        Review review = Review.builder()
                .id(9L).user(user(3L, "carol", "USER")).subjectId(96000201)
                .content("说错话了").deletedAt(LocalDateTime.now()).deletedBy(1L).build();
        when(reviewRepository.findById(9L)).thenReturn(Optional.of(review));

        adminService.restoreAnyReview(user(1L, "admin", "ADMIN"), 9L);

        assertThat(review.isRemoved()).as("放回架上了").isFalse();
        assertThat(review.getDeletedBy()).isNull();
        verify(reviewRepository).save(review);

        AdminActionLog log = onlyAuditRow();
        assertThat(log.getAction()).isEqualTo(AdminActionLog.REVIEW_RESTORE);
        assertThat(log.getTargetType()).isEqualTo(AdminActionLog.TARGET_REVIEW);
        assertThat(log.getTargetId()).isEqualTo(9L);
        assertThat(log.getDetail()).isEqualTo("恢复用户 carol 在作品 96000201 下的评论：说错话了");
    }

    /**
     * 动作失败时**一条账都不该留下**.
     *
     * <p>四种失败路径各来一次. 校验全部在 {@code save} 之前、也就是在记账之前, 所以它们
     * 天然不产生账 —— 但"天然"是会被人挪走的: 只要有人把某句校验挪到记账后面,
     * 账本里就会出现一条「封了一个不存在的用户」.
     */
    @Test
    @DisplayName("动作失败(404/400)时不产生任何账本行")
    void failedActionsLeaveNoAuditRow() {
        User actor = user(1L, "admin", "ADMIN");

        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "boss", "ADMIN")));
        assertThatThrownBy(() -> adminService.toggleUserStatus(actor, 2L))
                .isInstanceOf(BusinessException.class);

        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> adminService.unlockUser(actor, 99L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adminService.setUserRole(actor, 99L, "USER"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adminService.setUserRole(actor, 99L, "SUPER"))
                .isInstanceOf(BusinessException.class);

        when(reviewRepository.findById(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> adminService.deleteAnyReview(actor, 7L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adminService.restoreAnyReview(actor, 7L))
                .isInstanceOf(BusinessException.class);

        // V14 的两条"状态不对"(400): 已移除的再删一次、没移除的却要恢复.
        // 它们尤其不能记账 —— 重复点一下删除就在操作日志页上留下两条 REVIEW_DELETE,
        // 而那一页读起来就是"删了两次", 没有任何东西会提示这是同一个动作点了两下.
        when(reviewRepository.findById(8L)).thenReturn(Optional.of(Review.builder()
                .id(8L).user(user(3L, "carol", "USER")).subjectId(96000201)
                .deletedAt(LocalDateTime.now()).build()));
        assertThatThrownBy(() -> adminService.deleteAnyReview(actor, 8L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该评论已被移除");

        when(reviewRepository.findById(9L)).thenReturn(Optional.of(Review.builder()
                .id(9L).user(user(3L, "carol", "USER")).subjectId(96000201).build()));
        assertThatThrownBy(() -> adminService.restoreAnyReview(actor, 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该评论未被移除");

        verify(adminActionLogRepository, never()).save(any(AdminActionLog.class));
    }

    /**
     * {@code actorName} 是**写入那一刻**的快照, 不是指回 {@code user} 表的一根指针.
     *
     * <p>账本要活到那个账号改名或消失之后 —— 只有 id 的话, 一次改名就把历史记录
     * 变成了几个认不出来的数字.
     */
    @Test
    @DisplayName("actorName 是快照: 操作者之后改名, 旧记录仍是旧名")
    void actorNameIsASnapshot() {
        User actor = user(1L, "admin", "ADMIN");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "bob", "USER")));

        adminService.toggleUserStatus(actor, 2L);
        actor.setUsername("renamed");

        assertThat(onlyAuditRow().getActorName()).isEqualTo("admin");
    }

    /**
     * 记账必须发生在 {@code attempt} 的**回调之内**.
     *
     * <p>挪到外面两次写就分属两个事务, 于是"人封了、账没记"和反过来都能发生, 而两种
     * 都不报错. 这一层能观察到的是"回调跑到哪儿了"; 真库上的回滚由
     * {@code AdminActionLogRollbackTest} 验.
     */
    @Test
    @DisplayName("记账发生在事务边界之内 —— 挪出去就成了「人封了、账没记」")
    void auditRowIsWrittenInsideTheTransaction() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "bob", "USER")));
        AtomicBoolean insideWhenLogged = new AtomicBoolean();
        when(adminActionLogRepository.save(any(AdminActionLog.class))).thenAnswer(inv -> {
            insideWhenLogged.set(isolatedInsert.isInside());
            return inv.getArgument(0);
        });

        adminService.toggleUserStatus(user(1L, "admin", "ADMIN"), 2L);

        assertThat(insideWhenLogged).isTrue();
    }

    /** 账写不进去时, 整个动作跟着失败 —— 不允许出现一次「成功但无痕」的封禁 */
    @Test
    @DisplayName("记账失败时整个动作失败, 不静默通过")
    void whenTheLedgerWriteFailsTheActionFailsToo() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "bob", "USER")));
        when(adminActionLogRepository.save(any(AdminActionLog.class)))
                .thenThrow(new IllegalStateException("账本写不进去"));

        assertThatThrownBy(() -> adminService.toggleUserStatus(user(1L, "admin", "ADMIN"), 2L))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 操作者没有 id 时抛 403, 而不是写一行 {@code actor_id} 为空的账(那一列是 NOT NULL).
     *
     * <p>异常从 {@code attempt} 里抛出去会把整个动作一起回滚, 所以这里连"用户已经被改过"
     * 那半截也不会留下 —— 这正是记不住账时宁可什么都不做的意思.
     */
    @Test
    @DisplayName("没有 id 的操作者: 403, 而不是写一行没有操作者的账")
    void anActorWithoutIdIsRejected() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "bob", "USER")));

        assertThatThrownBy(() -> adminService.toggleUserStatus(
                User.builder().username("ghost").role("ADMIN").build(), 2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));

        verify(adminActionLogRepository, never()).save(any(AdminActionLog.class));
    }

    // ========== 重置密码 ==========
    //
    // 第五个破坏性动作. 它不改变权限, 却是唯一一个能把人挡在门外的非权限动作:
    // 重置之后那个人手上所有 token 立刻作废, 而能不能再进来取决于有没有人把新密码
    // 告诉他 —— 滥用它的后果与封禁最接近, 所以它和另外四个一样要留下一条账.

    @Test
    @DisplayName("重置密码: 写入新密文、记下时刻, 并记一条 USER_PASSWORD_RESET")
    void resettingAPasswordRecordsTheAction() {
        User target = user(2L, "bob", "USER");
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));

        adminService.resetPassword(user(1L, "admin", "ADMIN"), 2L, "brand-new-1");

        assertThat(target.getPassword())
                .as("存的必须是编码器的返回值, 不是明文")
                .isEqualTo(ENCODED_PASSWORD);
        assertThat(target.getPasswordChangedAt())
                .as("不记时刻的话, 这次重置等于没发生 —— 被盗账号上那张旧 token 会活到过期")
                .isNotNull();
        verify(passwordEncoder).encode("brand-new-1");

        AdminActionLog log = onlyAuditRow();
        assertThat(log.getAction()).isEqualTo(AdminActionLog.USER_PASSWORD_RESET);
        assertThat(log.getTargetType()).isEqualTo(AdminActionLog.TARGET_USER);
        assertThat(log.getTargetId()).isEqualTo(2L);
        assertThat(log.getDetail()).isEqualTo("重置了用户 bob 的密码");
    }

    /** 账里**不能**出现新密码本身: 账本是长期留存、多人可读的东西, 明文密码不该进去 */
    @Test
    @DisplayName("账本里不写新密码本身, 只写「重置了谁的密码」")
    void theAuditRowNeverContainsTheNewPassword() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "bob", "USER")));

        adminService.resetPassword(user(1L, "admin", "ADMIN"), 2L, "brand-new-1");

        assertThat(onlyAuditRow().getDetail()).doesNotContain("brand-new-1");
    }

    /**
     * 不能重置自己 —— 自己那条路要验旧密码, 是另一件事.
     *
     * <p>这条既是防呆, 也让「管理员能不能绕过旧密码校验改掉自己的密码」这个问题在结构上
     * 不存在。没有它的话, 一个只是「登录着」的管理员就能凭一张 token 换掉自己的密码 ——
     * 而那正是 {@code UserService.changePassword} 花力气防的那件事。
     */
    @Test
    @DisplayName("重置自己: 400 并指回个人中心, 且什么都没写")
    void refusesToResetOwnPassword() {
        User me = user(1L, "admin", "ADMIN");
        me.setPassword(ENCODED_PASSWORD);
        when(userRepository.findById(1L)).thenReturn(Optional.of(me));

        assertThatThrownBy(() -> adminService.resetPassword(me, 1L, "brand-new-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400))
                .hasMessageContaining("个人中心");

        verify(userRepository, never()).save(any(User.class));
        verify(adminActionLogRepository, never()).save(any(AdminActionLog.class));
        assertThat(me.getPassword()).isEqualTo(ENCODED_PASSWORD);
    }

    /** 目标不存在 → 404, 且不产生账 —— 账本里不该出现「重置了一个不存在的用户」 */
    @Test
    @DisplayName("重置一个不存在的用户: 404, 不记账")
    void resettingAnUnknownUserIsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.resetPassword(user(1L, "admin", "ADMIN"), 99L, "x1y2z3a4"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));

        verify(userRepository, never()).save(any(User.class));
        verify(adminActionLogRepository, never()).save(any(AdminActionLog.class));
    }

    /** 与另外四个动作同一条约定: 改密与记账在同一个事务里 */
    @Test
    @DisplayName("重置密码的记账也发生在事务边界之内")
    void resetAuditRowIsWrittenInsideTheTransaction() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "bob", "USER")));
        AtomicBoolean insideWhenLogged = new AtomicBoolean();
        when(adminActionLogRepository.save(any(AdminActionLog.class))).thenAnswer(inv -> {
            insideWhenLogged.set(isolatedInsert.isInside());
            return inv.getArgument(0);
        });

        adminService.resetPassword(user(1L, "admin", "ADMIN"), 2L, "brand-new-1");

        assertThat(insideWhenLogged).isTrue();
    }

    // ========== 操作日志: 参数夹取与筛选 ==========

    /**
     * 默认桩: 计数给个非零值, 否则取页那一段会因为"越界"而根本不执行.
     *
     * <p>三个 {@code any()} 对的是仓储那条语句的三个筛选参数(action / targetType /
     * targetId). 后两个从 {@code getActionPage} 过来时**恒为 null**(target 筛是详情页的
     * 内部能力, 不从 {@code /api/admin/actions} 暴露) —— 打桩用 {@code any()} 而不是
     * {@code isNull()} 只是不想让"桩"也跟着复述一遍那个实现细节; 「列表页传的就是 null」
     * 这件事由下面 {@code actionPageIgnoresTheTargetFilter} 单独钉.
     */
    private void stubActionPage(long matched) {
        when(adminActionLogRepository.countPage(any(), any(), any())).thenReturn(matched);
        when(adminActionLogRepository.findPage(any(), any(), any(), any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("认不出来的 action 当作「不筛」, 而不是把那个字符串发给数据库")
    void unknownActionBecomesNoFilter() {
        stubActionPage(1);

        adminService.getActionPage("BOGUS", 1, 20);

        // 原样下发的话 WHERE action = 'BOGUS' 匹配零行 —— 界面上是"没有操作记录",
        // 而接口 200, 没有任何东西报错. 与用户列表对未知 role 的口径一致.
        verify(adminActionLogRepository).countPage(isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("认识的 action 原样下发, 大小写不归一化")
    void knownActionIsPassedThrough() {
        stubActionPage(1);

        adminService.getActionPage(AdminActionLog.USER_BAN, 1, 20);

        // 这五个值是**我们自己写进库的字面量**, 不是用户输入 —— 归一化只会让
        // "user_ban 也认"这种宽容反过来掩盖一次拼错
        verify(adminActionLogRepository).countPage(eq(AdminActionLog.USER_BAN), isNull(), isNull());
    }

    @Test
    @DisplayName("页码小于 1 当作第 1 页; 每页条数 0 回到默认、超过上限夹到 100")
    void actionPageParamsAreClamped() {
        stubActionPage(500);

        adminService.getActionPage(null, 0, 0);
        ArgumentCaptor<Pageable> first = ArgumentCaptor.forClass(Pageable.class);
        verify(adminActionLogRepository).findPage(isNull(), isNull(), isNull(), first.capture());
        assertThat(first.getValue().getPageNumber()).isZero();
        assertThat(first.getValue().getPageSize()).isEqualTo(20);

        adminService.getActionPage(null, 1, 100000);
        ArgumentCaptor<Pageable> second = ArgumentCaptor.forClass(Pageable.class);
        verify(adminActionLogRepository, times(2))
                .findPage(isNull(), isNull(), isNull(), second.capture());
        assertThat(second.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("越界页: 报真实的 total, 一行都不取")
    void outOfRangeActionPageStillReportsTheRealTotal() {
        stubActionPage(30);

        Map<String, Object> result = adminService.getActionPage(null, 5, 20);

        assertThat(result.get("total")).isEqualTo(30);
        assertThat((List<?>) result.get("list")).isEmpty();
        verify(adminActionLogRepository, never()).findPage(any(), any(), any(), any());
    }

    /**
     * 行里给的是账本自己的字段, 一个键都不回表去查.
     *
     * <p>把 {@code targetId} 解析成用户名的写法看着更友好, 但它把账本"不依赖目标还在不在"
     * 这个前提推翻了 —— 而可读的那部分本就在 {@code actorName} 与 {@code detail} 里
     * (detail 在写的时候就带上了目标的名字快照).
     */
    @Test
    @DisplayName("日志行的七个键来自账本本身, 不回表查目标名字")
    void actionRowsCarryTheLedgerFields() {
        when(adminActionLogRepository.countPage(any(), any(), any())).thenReturn(1L);
        when(adminActionLogRepository.findPage(any(), any(), any(), any())).thenReturn(List.of(
                AdminActionLog.builder().id(7L).actorId(1L).actorName("admin")
                        .action(AdminActionLog.USER_BAN).targetType(AdminActionLog.TARGET_USER)
                        .targetId(2L).detail("禁用用户 bob")
                        .createdAt(LocalDateTime.of(2030, 1, 1, 0, 0)).build()));

        Map<String, Object> result = adminService.getActionPage(null, 1, 20);

        assertThat(result.get("total")).isEqualTo(1);
        assertThat(result.get("page")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0))
                .containsEntry("id", 7L)
                .containsEntry("action", AdminActionLog.USER_BAN)
                .containsEntry("actorName", "admin")
                .containsEntry("targetType", AdminActionLog.TARGET_USER)
                .containsEntry("targetId", 2L)
                .containsEntry("detail", "禁用用户 bob");
    }

    /**
     * 账本多了「按 target 筛」这两个谓词之后, <b>列表页的行为必须一个字都不变</b>。
     *
     * <p>这条守的是那个 {@code null, null}: 谓词是
     * {@code :targetType IS NULL OR l.targetType = :targetType}, 而传 null 让它恒真。
     * 将来谁把谓词从 {@code IS NULL OR} 写成直接的 {@code AND l.targetType = :targetType},
     * 账本列表页会**静默变成空页** —— 接口 200、没有异常、上面那些用例也全绿
     * (它们打的是 {@code any()} 桩, 不关心参数)。只有这一条会红。
     */
    @Test
    @DisplayName("账本列表页不做按 target 筛: 两个谓词都被传成 null")
    void actionPageIgnoresTheTargetFilter() {
        stubActionPage(1);

        adminService.getActionPage(null, 1, 20);

        verify(adminActionLogRepository).countPage(isNull(), isNull(), isNull());
        verify(adminActionLogRepository).findPage(isNull(), isNull(), isNull(), any(Pageable.class));
    }

    // ========== 用户详情 ==========

    @Test
    @DisplayName("用户不存在: 404, 一条读语句都不发")
    void userDetailOfMissingUserIsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.getUserDetail(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户不存在");

        verify(trackingRepository, never()).countByUser(any());
        verify(reviewRepository, never()).countByUserAndDeletedAtIsNull(any());
    }

    @Test
    @DisplayName("四个计数各自走自己的聚合, 不在 Java 里数")
    void userDetailCountsComeFromAggregates() {
        User target = user(7L, "bob", "USER");
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));
        when(trackingRepository.countByUser(target)).thenReturn(12L);
        when(reviewRepository.countByUserAndDeletedAtIsNull(target)).thenReturn(4L);
        when(reviewRepository.countByUserAndDeletedAtIsNotNull(target)).thenReturn(1L);
        when(episodeWatchedRepository.countByUser(target)).thenReturn(37L);

        Map<String, Object> detail = adminService.getUserDetail(7L);

        assertThat(detail.get("counts")).isEqualTo(Map.of(
                "trackings", 12L, "reviewsAlive", 4L,
                "reviewsRemoved", 1L, "episodesWatched", 37L));
        // 在架与被移除各数各的: 相减会在两次查询之间留下窗口, 而这两个数字在界面上
        // 是并排显示的, 对不上就会被当成 bug 报回来.
        verify(reviewRepository).countByUserAndDeletedAtIsNull(target);
        verify(reviewRepository).countByUserAndDeletedAtIsNotNull(target);
    }

    @Test
    @DisplayName("锁定的判据与列表行同源: 过期的 lockedUntil 不算锁定")
    void userDetailUsesTheSameLockedVerdictAsTheListRow() {
        User target = User.builder().id(7L).username("bob").role("USER").status("ACTIVE")
                .lockedUntil(LocalDateTime.of(2000, 1, 1, 0, 0)).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));

        Map<String, Object> detail = adminService.getUserDetail(7L);

        assertThat(detail).containsEntry("locked", false).containsEntry("lockedUntil", null);
    }

    /**
     * 「有没有 N+1」的结构性证明: 番剧名/封面**只查一次**, 且入参是两个列表 subjectId 的
     * <b>并集</b>。
     *
     * <p>比数语句更稳 —— 数语句只能证明"这一轮是 9 条", 而这一条证明的是"将来追番和评论
     * 撞上同一部番时不会去查两遍"。分两次查的写法在今天的测试数据上语句数是一样的。
     */
    @Test
    @DisplayName("番剧信息只批量查一次, 入参是追番与评论 subjectId 的并集")
    void userDetailFetchesAnimeMetadataOnceForTheUnion() {
        User target = user(7L, "bob", "USER");
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));
        when(trackingRepository.findByUserOrderByUpdatedAtDesc(eq(target), any(Pageable.class)))
                .thenReturn(List.of(
                        AnimeTracking.builder().subjectId(100).status("WATCHING").progress(5).build(),
                        AnimeTracking.builder().subjectId(200).status("PLAN").progress(0).build()));
        when(reviewRepository.findPageByUserOrderByCreatedAtDesc(eq(target), any(), any(Pageable.class)))
                .thenReturn(List.of(
                        Review.builder().id(9L).subjectId(100).rating(8).build(),
                        Review.builder().id(10L).subjectId(300).rating(6).build()));
        when(animeRepository.findAllById(any())).thenReturn(List.of(
                Anime.builder().id(100).titleCn("进击的巨人").coverUrl("c100").build(),
                Anime.builder().id(200).title("ONE PIECE").build()));

        Map<String, Object> detail = adminService.getUserDetail(7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Integer>> captor = ArgumentCaptor.forClass(List.class);
        // 只被调用一次 —— 追番与评论各查一次的话这里就是 times(2)
        verify(animeRepository, times(1)).findAllById(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(100, 200, 300);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trackings = (List<Map<String, Object>>) detail.get("trackings");
        assertThat(trackings).hasSize(2);
        assertThat(trackings.get(0)).containsEntry("animeTitle", "进击的巨人")
                .containsEntry("animeCover", "c100");
        // 本地没缓存过的那部(200)回退成日文原名; 而 300 根本没在 animeRepository 里 —— 见下一条
        assertThat(trackings.get(1)).containsEntry("animeTitle", "ONE PIECE")
                .containsEntry("animeCover", null);
    }

    /**
     * 本地没缓存过的番剧给 {@code null}, <b>不用「未知作品」兜底</b>。
     *
     * <p>编一个假名字比空着更糟: 几条不同 subjectId 的记录会挤在同一个假名字下面, 而
     * 「这部番还没进本地库」本身是真信息。前端拿到 null 时退化成「番剧 #656083」。
     */
    @Test
    @DisplayName("本地没缓存过的番剧: animeTitle 为 null, 不是「未知作品」")
    void userDetailLeavesUnknownAnimeTitleNull() {
        User target = user(7L, "bob", "USER");
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));
        when(reviewRepository.findPageByUserOrderByCreatedAtDesc(eq(target), any(), any(Pageable.class)))
                .thenReturn(List.of(Review.builder().id(9L).subjectId(656083).rating(8).build()));
        when(animeRepository.findAllById(any())).thenReturn(List.of());

        Map<String, Object> detail = adminService.getUserDetail(7L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviews = (List<Map<String, Object>>) detail.get("reviews");
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0)).containsEntry("animeTitle", null)
                .containsEntry("subjectId", 656083);
    }

    /**
     * 三个列表都是**数组**, 空的时候是空数组而不是缺键。
     *
     * <p>缺键会让前端的 {@code list.length} 落到 undefined 上, 看着与空数组一样 ——
     * 但「服务端没发这个键」这种真实故障就再也看不出来了。
     */
    @Test
    @DisplayName("三个列表为空时是空数组, 不是缺键; 也从不提前返回")
    void userDetailAlwaysCarriesThreeLists() {
        User target = user(7L, "bob", "USER");
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));

        Map<String, Object> detail = adminService.getUserDetail(7L);

        assertThat(detail).containsKeys("trackings", "reviews", "actions");
        assertThat(detail.get("trackings")).isEqualTo(List.of());
        assertThat(detail.get("reviews")).isEqualTo(List.of());
        assertThat(detail.get("actions")).isEqualTo(List.of());
        // 空的账号也走完同样多的查询: findById 收到空集合会直接返回空表、不发 SQL,
        // 所以这里是 8 条而不是 9 条 —— 但代码里**不允许**为它补一次空查询或者加早退,
        // 那会把常数 9 变成一个随数据变的值, 语句计数用例从此不守卫任何东西.
        verify(animeRepository).findAllById(any());
    }

    @Test
    @DisplayName("账本列表按 target 筛到这一个用户, 且复用列表页那一份 WHERE")
    void userDetailScopesTheLedgerToThisUser() {
        User target = user(7L, "bob", "USER");
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));

        adminService.getUserDetail(7L);

        verify(adminActionLogRepository)
                .findPage(isNull(), eq(AdminActionLog.TARGET_USER), eq(7L), any(Pageable.class));
        // 详情页不走 getActionPage: 那会白算一次 count 再把信封丢掉
        verify(adminActionLogRepository, never()).countPage(any(), any(), any());
    }
}
