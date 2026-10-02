package com.animetracker.service;

import com.animetracker.config.LoginProtectionProperties;
import com.animetracker.dto.RequestDTO.LoginRequest;
import com.animetracker.dto.RequestDTO.RegisterRequest;
import com.animetracker.entity.LoginEvent;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.LoginEventRepository;
import com.animetracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录事件在真库上的行为: 什么时候落一行、落成什么样、以及看板那几个数各自是什么口径。
 *
 * <p>与 {@code LoginEventServiceTest} 的分工: 那个类用 mock 管"写不抛"和"保留期配成 0
 * 不许清空"这两条控制流; 这个类管所有 mock 看不见的东西 —— 端到端落库、按天分组的 SQL、
 * 窗口边界、以及事务隔离。理由与 {@code WriteConflictIntegrationTest} 顶上写的一样:
 * 实体注解与迁移脚本对不对得上、JPQL 在真库上算出什么, 只有真库答得了。
 *
 * <p><b>类上刻意不加 {@code @Transactional}</b>: 加了之后每个用例自己就是一个大事务,
 * "记事件跑在独立事务里"这条根本没法验(外层回滚与否全被外层用例包住), 而且用例之间
 * 会互相看见对方没提交的行。生产上的登录请求本来就没有外层事务, 不挂事务才更接近真实。
 *
 * <p>库名单独起一个: 这里的 {@code @BeforeEach} 会清空 {@code login_event} 与本文建的用户,
 * 与别的用例共库的话那一下会掀掉它们的铺底数据。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-login-event;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false"
})
@ActiveProfiles("dev")
class LoginEventIntegrationTest {

    /** RFC 5737 的文档网段。刻意不用 127.0.0.1: 拿回环当地址会让"ip 有没有真的透传"永远成立 */
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String PASSWORD = "abcd1234";

    @Autowired
    private LoginEventService loginEventService;
    @Autowired
    private LoginEventRepository loginEventRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private LoginProtectionProperties loginProps;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void clear() {
        jdbc.execute("DELETE FROM login_event");
        jdbc.execute("DELETE FROM \"user\" WHERE username LIKE 'e2e_%'");
    }

    // ========== 端到端: 一次登录尝试 = 一行 ==========

    @Test
    @DisplayName("登录成功落一行 success=true, 带 userId 和来源地址")
    void aSuccessfulLoginIsRecorded() {
        User user = register("e2e_ok");

        userService.login(loginReq("e2e_ok"), CLIENT_IP);

        assertThat(loginEventRepository.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getUserId()).isEqualTo(user.getId());
            assertThat(e.isSuccess()).isTrue();
            assertThat(e.getIp())
                    .as("地址是从 controller 一路传下来的, 中间任何一处漏掉都会变成 null")
                    .isEqualTo(CLIENT_IP);
            assertThat(e.getCreatedAt())
                    .as("没有时刻的事件不构成事件 —— 这一列由实体在持久化时填")
                    .isNotNull();
        });
    }

    /**
     * <b>用户名不存在的那一类失败也要落一行, 而且 userId 是 null。</b>
     *
     * <p>它是这张表唯一记"没有用户"的形状, 而它恰恰是扫账号最典型的痕迹 ——
     * 「同一个 IP 在一分钟里打了一堆不存在的用户名」。真让它匿名进去也无所谓:
     * 需要追查具体账号时, 另外几类失败都是带 userId 的。
     */
    @Test
    @DisplayName("用户名不存在的失败落一行, userId 为 null")
    void anUnknownUsernameIsRecordedWithoutAUser() {
        loginExpectingFailure(loginReq("e2e_ghost"));

        assertThat(loginEventRepository.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getUserId()).isNull();
            assertThat(e.isSuccess()).isFalse();
            assertThat(e.getIp()).isEqualTo(CLIENT_IP);
        });
    }

    @Test
    @DisplayName("密码错的失败落一行, 带 userId")
    void aWrongPasswordIsRecorded() {
        User user = register("e2e_wrong");

        loginExpectingFailure(loginReq("e2e_wrong", "wrong1234"));

        assertThat(loginEventRepository.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getUserId()).isEqualTo(user.getId());
            assertThat(e.isSuccess()).isFalse();
        });
    }

    @Test
    @DisplayName("被禁用的账号(密码是对的)也落一行 —— 那也是一次没进来的尝试")
    void aDisabledAccountIsRecorded() {
        User user = register("e2e_disabled");
        jdbc.update("UPDATE \"user\" SET status = 'DISABLED' WHERE id = ?", user.getId());

        loginExpectingFailure(loginReq("e2e_disabled"));

        assertThat(loginEventRepository.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getUserId()).isEqualTo(user.getId());
            assertThat(e.isSuccess()).isFalse();
        });
    }

    /**
     * <b>把账号打锁的那一次也必须落库。</b>
     *
     * <p>这条钉的是一个非常容易踩的顺序问题: 密码错误那一支里, 记事件必须写在
     * {@code recordFailure} <b>之前</b> —— 后者到达阈值时会抛出「已锁定」, 写在它后面的话
     * 那条语句永远不会执行, 于是「第 5 次尝试」恰好不会落库。而它正是最该被看见的那一次:
     * 前 4 次只是失败, 第 5 次是"这里有人在爆破, 我们已经把门锁了"。
     *
     * <p>断言的是"次数正好等于阈值"而不是">= 1": 少记一次和多记一次都是错,
     * 而后者(比如在 recordFailure 内外各记一次)光看最后一行是发现不了的。
     */
    @Test
    @DisplayName("把账号打锁的那第 N 次失败也落了库(记事件必须在 recordFailure 之前)")
    void theAttemptThatLocksTheAccountIsStillRecorded() {
        register("e2e_lock");

        int threshold = loginProps.getMaxFailures();
        for (int i = 0; i < threshold; i++) {
            loginExpectingFailure(loginReq("e2e_lock", "wrong1234"));
        }

        assertThat(loginEventRepository.findAll())
                .as("阈值是 %d, 就该正好有 %d 行 —— 少了说明打锁那一次漏记了", threshold, threshold)
                .hasSize(threshold)
                .allSatisfy(e -> assertThat(e.isSuccess()).isFalse());
    }

    /**
     * 一次请求 = 一行: 成功之后紧接着的那次失败不该被上一次的痕迹吞掉或重复算。
     *
     * <p>它守的是"每条出口恰好一条"这条不变式的另一半 —— 上面几条各只看了一种出口,
     * 这条把两种出口接在一起跑, 免得某个分支被重复记两次(重复在单出口的用例里看不出来)。
     */
    @Test
    @DisplayName("成功一次 + 失败一次 = 两行, 不多不少")
    void eachAttemptLeavesExactlyOneRow() {
        register("e2e_two");

        userService.login(loginReq("e2e_two"), CLIENT_IP);
        loginExpectingFailure(loginReq("e2e_two", "wrong1234"));

        assertThat(loginEventRepository.findAll(Sort.by(Sort.Direction.ASC, "id")))
                .as("findAll 不保证顺序, 这条断言的是「先成功后失败」, 所以按 id 定序")
                .extracting(LoginEvent::isSuccess)
                .containsExactly(true, false);
    }

    // ========== 事务: 它是旁路, 不是登录流程的一部分 ==========

    /**
     * <b>调用方回滚了, 事件这一行仍然在。</b>
     *
     * <p>这张表记的是"发生过什么", 而一次登录尝试发生过就是发生过了 —— 之后业务那边
     * 因为任何理由回滚, 都不该把这条事实一起抹掉。做到这一点靠的是
     * {@code IsolatedInsert}(REQUIRES_NEW): 插入跑在自己的事务里, 与调用方那个同生共死。
     *
     * <p>把它换成普通的 {@code save}(或者哪天有人觉得"包一层事务太绕"把它拆了),
     * 这条就会红 —— 而那正是"记不上账"与"登录被判失败"之间那道墙被拆掉的时刻。
     */
    @Test
    @DisplayName("记事件跑在自己的事务里: 调用方回滚了, 这一行仍然在")
    void theEventSurvivesTheCallersRollback() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        tx.execute(status -> {
            loginEventService.recordAttempt(42L, CLIENT_IP, false);
            status.setRollbackOnly();
            return null;
        });

        assertThat(loginEventRepository.count())
                .as("这一行跟着外层回滚掉的话, 说明它跑在别人的事务里")
                .isEqualTo(1);
    }

    // ========== 看板的口径 ==========

    @Test
    @DisplayName("日活数的是人不是次数; 失败不算活跃; 周活含今天")
    void dauCountsPeopleNotLogins() {
        LocalDate today = LocalDate.now();
        event(1L, today, true);
        event(1L, today, true);          // 同一个人今天来了两次
        event(2L, today, true);
        event(3L, today.minusDays(1), true);
        event(4L, today.minusDays(10), true);
        event(2L, today.minusDays(10), true);
        event(5L, today, false);         // 今天失败了一次 —— 没进来, 不算活跃

        Map<String, Object> activity = loginEventService.activity();

        assertThat(activity.get("dau"))
                .as("1 和 2 两个人, 与登录次数无关")
                .isEqualTo(2L);
        assertThat(activity.get("wau"))
                .as("今天(1,2) + 昨天(3), 10 天前那两个不在窗口里")
                .isEqualTo(3L);
    }

    /**
     * 活跃窗口是 <b>左闭右开 {@code [from, to)}</b>: 今天零点那一刻算今天, 明天零点那一刻不算。
     *
     * <p>两条边界各钉一侧, 缺哪一侧都有一个同类错法能溜过去 —— 而且都是<b>平时看不出来</b>的:
     * <ul>
     *   <li><b>少了右端</b>: 窗口退化成「今天零点之后的一切」。库里只有过去的数据时结果
     *       一字不差, 所以它不会被日常数据打红; 而一旦有时钟偏移/时区配错写进来一行未来的
     *       时刻, 「今日活跃」会把它算进去, 而曲线最后一个点（按天分组、窗口到今天就止）
     *       不会 —— 看板上两个并排的数字对不上, 没人解释得清哪个是对的。</li>
     *   <li><b>右端写成 {@code <=}</b>: 明天零点那一行被算进今天, 多算一个。</li>
     * </ul>
     *
     * <p>⚠️ 「明天」那一行是用例自己造的, 应用不会产生它 —— 这是刻意的。这条断言要问的是
     * 「右端是排他的吗」, 而这个问题在只有过去数据的库上<b>看不出答案</b>: 反向验证实测过,
     * 把日活那一处的右端放宽成 {@code +100 年}, 整个类加隔壁看板类共 17 条用例<b>一条都不红</b>。
     * 既然它是一条真性质, 就该有一条真守卫; 靠「未来时刻的行不可能存在」撑着不算。
     */
    @Test
    @DisplayName("活跃窗口左闭右开: 今天零点算今天, 明天零点不算")
    void theActiveWindowIsHalfOpen() {
        LocalDate today = LocalDate.now();
        insert(1L, today.atStartOfDay(), true);                  // 左端那一刻 —— 算
        insert(2L, today.plusDays(1).atStartOfDay(), true);      // 右端那一刻 —— 不算

        Map<String, Object> activity = loginEventService.activity();
        List<Map<String, Object>> trend = trendOf(activity);

        assertThat(activity.get("dau"))
                .as("少了右端这条会变成 2(明天那一刻被算进今天)")
                .isEqualTo(1L);
        assertThat(trend.get(13).get("users"))
                .as("曲线最后一个点与「今日活跃」必须是同一个窗口算出来的, 这一条也一样")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("近 24 小时失败次数按「次」算, 不按人")
    void failedAttemptsAreCountedPerAttempt() {
        LocalDateTime now = LocalDateTime.now();
        insert(1L, now.minusMinutes(30), false);
        insert(1L, now.minusMinutes(20), false);
        insert(1L, now.minusMinutes(10), false);
        insert(2L, now.minusHours(30), false);   // 出窗口了

        assertThat(loginEventService.activity().get("failedAttempts24h"))
                .as("爆破的特征是重试次数, 去重反而把信号抹掉")
                .isEqualTo(3L);
    }

    /**
     * 曲线是 <b>14 个点、一天不缺</b>, 且最后一个点与"今日活跃"是同一个数。
     *
     * <p>后一半是这条真正的价值: 两个并排显示的数字如果对不上, 看板上就没有人能解释
     * 哪个是对的。让它们相等靠的是"两处用同一个窗口"(见
     * {@code LoginEventRepository.countActiveBetween} 的注释), 而不是靠人记得同时改两处。
     */
    @Test
    @DisplayName("曲线固定 14 个点、没有事件的天补 0, 且最后一个点等于今日活跃")
    void theTrendIsFourteenDaysWithZeroesFilledIn() {
        LocalDate today = LocalDate.now();
        event(1L, today, true);
        event(2L, today, true);
        event(3L, today.minusDays(3), true);

        Map<String, Object> activity = loginEventService.activity();
        List<Map<String, Object>> trend = trendOf(activity);

        assertThat(trend).hasSize(14);
        assertThat(trend.get(0).get("date")).isEqualTo(today.minusDays(13).toString());
        assertThat(trend.get(13).get("date")).isEqualTo(today.toString());
        assertThat(usersOn(trend, today.minusDays(3)))
                .as("3 天前那个人落在倒数第 4 个点上")
                .isEqualTo(1L);
        assertThat(usersOn(trend, today.minusDays(9)))
                .as("库里没有事件的那一天必须是 0, 不能缺项 —— 缺项的曲线会被画成一条断线")
                .isEqualTo(0L);
        assertThat(trend.get(13).get("users"))
                .as("同一个窗口, 就必须是同一个数")
                .isEqualTo(activity.get("dau"));
    }

    /**
     * <b>空表要能被认出来是"还没有数据", 而不是"这几天没人来"。</b>
     *
     * <p>两种情形的运营含义正好相反: 一个再也没人用的站点, 和一个刚上线的功能, 画出来
     * 都是同一条贴地的平线。{@code trackedSince} 为 null 是前者与后者的分界 ——
     * 一条事件都没有时它就是 null, 前端据此显示"数据不足"而不是画线。
     */
    @Test
    @DisplayName("空表: trackedSince 是 null(前端据此显示「数据不足」), 曲线全 0")
    void anEmptyTableIsReportedAsSuch() {
        Map<String, Object> activity = loginEventService.activity();

        assertThat(activity.get("trackedSince"))
                .as("null 说的是「这个功能还没有任何数据」, 而全 0 说的是「没人来」")
                .isNull();
        assertThat(activity.get("dau")).isEqualTo(0L);
        assertThat(trendOf(activity)).hasSize(14)
                .allSatisfy(point -> assertThat(point.get("users")).isEqualTo(0L));
    }

    @Test
    @DisplayName("有数据之后 trackedSince 报的是最早那天")
    void trackedSinceIsTheEarliestEvent() {
        LocalDate today = LocalDate.now();
        event(1L, today.minusDays(4), true);
        event(2L, today, true);

        assertThat(loginEventService.activity().get("trackedSince"))
                .isEqualTo(today.minusDays(4).toString());
    }

    // ========== 保留期 ==========

    /**
     * 保留期清理真的按时间删 —— 单元用例只验了"传给仓储的界对不对", 删没删掉是 SQL 的事。
     */
    @Test
    @DisplayName("purgeExpired 真的删掉过期那批, 保留期内的一个不动")
    void purgeExpiredReallyDeletesOldRows() {
        LocalDate today = LocalDate.now();
        event(1L, today.minusDays(200), true);
        event(2L, today.minusDays(181), true);
        event(3L, today.minusDays(179), true);
        event(4L, today, true);

        loginEventService.purgeExpired();

        assertThat(loginEventRepository.findAll())
                .extracting(LoginEvent::getUserId)
                .as("界是 180 天: 200 天前那条必须走, 179 天前那条必须留")
                .containsExactlyInAnyOrder(3L, 4L);
    }

    // ========== 工具 ==========

    /**
     * 跑一次注定进不去的登录, 把那条例外吃掉。
     *
     * <p>这里要的是它<b>落下的那一行</b>, 不是异常本身; 但也不能装作没抛 —— 所以顺带断言
     * 它确实是一次失败。不加这句的话, 哪天那个分支改成"放行", 用例会因为"事件是 success=false"
     * 而继续绿, 只是理由完全变了。
     */
    private void loginExpectingFailure(LoginRequest req) {
        assertThatThrownBy(() -> userService.login(req, CLIENT_IP))
                .isInstanceOf(BusinessException.class);
    }

    private User register(String username) {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(username);
        req.setEmail(username + "@example.com");
        req.setPassword(PASSWORD);
        userService.register(req);
        return userRepository.findByUsername(username).orElseThrow();
    }

    private static LoginRequest loginReq(String username) {
        return loginReq(username, PASSWORD);
    }

    private static LoginRequest loginReq(String username, String password) {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }

    /** 今天 {@code day} 那天的中午一条事件. 用中午而不是零点, 免得正好压在日界上 */
    private void event(Long userId, LocalDate day, boolean success) {
        insert(userId, day.atTime(12, 0), success);
    }

    private void insert(Long userId, LocalDateTime at, boolean success) {
        loginEventRepository.save(LoginEvent.builder()
                .userId(userId).createdAt(at).ip(CLIENT_IP).success(success).build());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> trendOf(Map<String, Object> activity) {
        return (List<Map<String, Object>>) activity.get("trend");
    }

    /**
     * 曲线上某一天的人数, 按日期找而不是按下标算。
     *
     * <p>写 {@code trend.get(10)} 的话, 下标与日期之间的关系要读者自己在脑子里算一遍
     * (算错一次就是一条绿得莫名其妙的用例, 或者一条红得莫名其妙的); 按日期找还把
     * "那一天根本不在窗口里"变成一条说得出话的失败。
     */
    private static Object usersOn(List<Map<String, Object>> trend, LocalDate day) {
        return trend.stream()
                .filter(point -> day.toString().equals(point.get("date")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("曲线的 14 个点里没有 " + day + " 这一天"))
                .get("users");
    }
}
