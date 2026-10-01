package com.animetracker.service;

import com.animetracker.entity.AdminActionLog;
import com.animetracker.entity.Review;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.AdminActionLogRepository;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.ReviewRepository;
import com.animetracker.repository.TrackingRepository;
import com.animetracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端那些**写**动作的护栏: 改角色 / 封禁 / 解锁 / 删评论, 以及它们留下的账.
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

    private UserRepository userRepository;
    private ReviewRepository reviewRepository;
    private AdminActionLogRepository adminActionLogRepository;
    private TrackingIsolatedInsert isolatedInsert;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        reviewRepository = mock(ReviewRepository.class);
        adminActionLogRepository = mock(AdminActionLogRepository.class);
        // 用真的那个, 不用 mock: IsolatedInsert.attempt 的语义就是「把回调跑一遍」,
        // 换成 mock 之后四个动作会一起变成空操作, 而那一组用例的断言全都还在,
        // 于是它们会以「什么都没发生」的形式静默失真. 子类只是多记一个「回调开着呢」.
        isolatedInsert = new TrackingIsolatedInsert();
        adminService = new AdminService(userRepository, reviewRepository,
                mock(TrackingRepository.class), mock(AnimeRepository.class),
                adminActionLogRepository, isolatedInsert);
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
     */
    @Test
    @DisplayName("删评论: 记下作者与正文摘要, 摘要封顶 60 字")
    void deletingAReviewSnapshotsTheAuthorAndASnippet() {
        Review review = Review.builder()
                .id(9L).user(user(3L, "carol", "USER")).subjectId(96000201)
                .content("甲".repeat(200)).build();
        when(reviewRepository.findById(9L)).thenReturn(Optional.of(review));

        adminService.deleteAnyReview(user(1L, "admin", "ADMIN"), 9L);

        verify(reviewRepository).delete(review);
        AdminActionLog log = onlyAuditRow();
        assertThat(log.getAction()).isEqualTo(AdminActionLog.REVIEW_DELETE);
        assertThat(log.getTargetType()).isEqualTo(AdminActionLog.TARGET_REVIEW);
        assertThat(log.getTargetId()).isEqualTo(9L);
        assertThat(log.getDetail())
                .isEqualTo("删除用户 carol 在作品 96000201 下的评论：" + "甲".repeat(60) + "…");
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

    // ========== 操作日志: 参数夹取与筛选 ==========

    /** 默认桩: 计数给个非零值, 否则取页那一段会因为"越界"而根本不执行 */
    private void stubActionPage(long matched) {
        when(adminActionLogRepository.countPage(any())).thenReturn(matched);
        when(adminActionLogRepository.findPage(any(), any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("认不出来的 action 当作「不筛」, 而不是把那个字符串发给数据库")
    void unknownActionBecomesNoFilter() {
        stubActionPage(1);

        adminService.getActionPage("BOGUS", 1, 20);

        // 原样下发的话 WHERE action = 'BOGUS' 匹配零行 —— 界面上是"没有操作记录",
        // 而接口 200, 没有任何东西报错. 与用户列表对未知 role 的口径一致.
        verify(adminActionLogRepository).countPage(isNull());
    }

    @Test
    @DisplayName("认识的 action 原样下发, 大小写不归一化")
    void knownActionIsPassedThrough() {
        stubActionPage(1);

        adminService.getActionPage(AdminActionLog.USER_BAN, 1, 20);

        // 这五个值是**我们自己写进库的字面量**, 不是用户输入 —— 归一化只会让
        // "user_ban 也认"这种宽容反过来掩盖一次拼错
        verify(adminActionLogRepository).countPage(AdminActionLog.USER_BAN);
    }

    @Test
    @DisplayName("页码小于 1 当作第 1 页; 每页条数 0 回到默认、超过上限夹到 100")
    void actionPageParamsAreClamped() {
        stubActionPage(500);

        adminService.getActionPage(null, 0, 0);
        ArgumentCaptor<Pageable> first = ArgumentCaptor.forClass(Pageable.class);
        verify(adminActionLogRepository).findPage(isNull(), first.capture());
        assertThat(first.getValue().getPageNumber()).isZero();
        assertThat(first.getValue().getPageSize()).isEqualTo(20);

        adminService.getActionPage(null, 1, 100000);
        ArgumentCaptor<Pageable> second = ArgumentCaptor.forClass(Pageable.class);
        verify(adminActionLogRepository, times(2)).findPage(isNull(), second.capture());
        assertThat(second.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("越界页: 报真实的 total, 一行都不取")
    void outOfRangeActionPageStillReportsTheRealTotal() {
        stubActionPage(30);

        Map<String, Object> result = adminService.getActionPage(null, 5, 20);

        assertThat(result.get("total")).isEqualTo(30);
        assertThat((List<?>) result.get("list")).isEmpty();
        verify(adminActionLogRepository, never()).findPage(any(), any());
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
        when(adminActionLogRepository.countPage(any())).thenReturn(1L);
        when(adminActionLogRepository.findPage(any(), any())).thenReturn(List.of(
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
}
