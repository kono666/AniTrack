package com.animetracker.service;

import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
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
 * 改角色这个动作的护栏.
 *
 * <p>这一组规则的价值全在「写错了不会报错」上: 白名单漏了, 库里会多出一个
 * 名字像管理员、权限却不是的账号, 没有任何一处会报错; 少拦一次「改自己」或
 * 「降级最后一个管理员」, 系统会在某一次点击之后突然**再也没有人能进管理端**,
 * 而那次点击看起来和平时任何一次成功操作一模一样.
 *
 * <p>所以每个用例都同时断言两件事: 抛出的是 400(而不是 500, 也不是静默通过),
 * 以及**什么都没写进库** —— 只在抛异常之前先 save 了一下, 异常照样抛,
 * 但数据已经坏了.
 */
class AdminServiceTest {

    private UserRepository userRepository;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        adminService = new AdminService(userRepository, mock(ReviewRepository.class),
                mock(TrackingRepository.class), mock(AnimeRepository.class));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static User user(Long id, String username, String role) {
        return User.builder().id(id).username(username).role(role).status("ACTIVE").build();
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
}
