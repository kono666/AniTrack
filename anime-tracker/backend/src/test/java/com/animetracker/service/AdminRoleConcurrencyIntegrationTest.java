package com.animetracker.service;

import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 「系统里必须剩下至少一个管理员」这条不变式, 在**真库 + 真并发**下的证伪点。
 *
 * <p>{@code AdminServiceTest.refusesToDemoteTheLastAdmin} 验的是"数到 1 就拒绝", 那条
 * 用例对"数"和"写"之间有没有窗口一无所知 —— 它把 count 桩成 1, 于是无论实现怎么加锁,
 * 结果都一样。这里要问的是另一件事: <b>两个管理员同时降级对方时, 会不会两个都通过</b>。
 * 原来的实现会: 各自数到 2、各自放行, 提交完系统里 0 个管理员, 而两个请求都回成功。
 *
 * <p><b>为什么必须用闩锁, 不能"开两个线程一起冲"。</b> TOCTOU 的窗口是"count 查到"到
 * "save 提交"之间那几百微秒, 朴素并发用例绝大多数时候两个线程根本撞不上, 于是<b>在没
 * 加锁的实现上也会绿</b> —— 那种用例写了等于没写, 而且比没有更糟: 它让人以为这里验过了。
 * 这里的做法是把窗口**撑开成一个可以断言的事实**: 让第一个事务显式地把管理员行锁住、
 * 然后挂住不提交, 再看第二个调用是不是真的在等它。
 *
 * <p>于是断言分两半, 两半都不能少:
 * <ul>
 *   <li>"第二个还没返回" —— 这一半是**加锁这件事本身**的哨兵。把
 *       {@code findByRoleForUpdate} 换回 {@code countByRole}, 第二个调用会立刻算完并
 *       返回, 这一步当场红, 而后面那一半照样绿(结果碰巧是对的);</li>
 *   <li>"放行之后它做出正确判断" —— 这一半防的是另一种假绿: 一个只会死等、或者等完
 *       之后算错数的实现。少了它, "把这次调用整个卡死"也能让第一步通过。</li>
 * </ul>
 *
 * <p><b>类上刻意不加 @Transactional</b>, 理由同 {@code WriteConflictIntegrationTest}:
 * 加了之后每个测试方法自己就是一个大事务, 用例之间互相看得见对方没提交的行, 而且
 * "第二个调用能不能拿到锁"这件事会被外层事务整个搅乱。生产上的网页请求也没有外层事务,
 * 每个 repository 调用各自提交 —— 不挂事务才更接近真实路径。
 *
 * <p><b>为什么这个类自己一个库名。</b> 它要把库里所有账号的角色先统一打成 USER, 好让
 * "系统里有几个管理员"变成一个我能算出来的数; 跟别的用例共库的话, 这一下会掀掉它们的
 * 铺底数据。
 */
@SpringBootTest(properties = {
        // LOCK_TIMEOUT 调到 60 秒是为了让"被挡住"这件事能被看见:
        // H2 默认的锁等待只有一秒左右, 到点会抛锁超时而不是继续等 —— 那样第二个调用
        // 会以"异常结束"的样子变成 isDone() == true, 把一条正确的实现判红。
        "spring.datasource.url=jdbc:h2:mem:anitrack-admin-role-race;DB_CLOSE_DELAY=-1;MODE=MySQL;LOCK_TIMEOUT=60000",
        "anitrack.preload.enabled=false"
})
@ActiveProfiles("dev")
class AdminRoleConcurrencyIntegrationTest {

    /**
     * "第二个调用还在等"的观察窗口。
     *
     * <p>没有加锁时, 第二个调用要走的是 findById + 一条 count, 都是毫秒级 —— 1.5 秒
     * 足够它跑完并且留有大量余量(慢机器上也不会误判成"还在等")。加了锁时它会一直等到
     * 我们放行为止, 窗口多长都不影响结论。
     */
    private static final long WINDOW_MS = 1500;

    /** 闩锁和 get 的超时, 取一个远超任何真实耗时、又能让挂死的用例自己失败的值 */
    private static final long TIMEOUT_SECONDS = 30;

    @Autowired
    private AdminService adminService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @PersistenceContext
    private EntityManager em;

    /**
     * 每个用例开始前把**所有**账号打成 USER。
     *
     * <p>dev 档的迁移脚本里铺了一个 admin 账号, 而这两个用例断言的都是"系统里有几个
     * 管理员" —— 不定死这个底数, 断言就得跟着铺底数据走, 以后谁往种子里再加一个管理员,
     * 这里就会以一种完全看不出原因的方式红掉。
     */
    @BeforeEach
    void normaliseRoles() {
        jdbc.update("UPDATE \"user\" SET role = 'USER'");
    }

    private User userWithRole(String role) {
        return userRepository.save(User.builder()
                .username("race_" + UUID.randomUUID().toString().substring(0, 8))
                .password("x")
                .email(UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .role(role)
                .status("ACTIVE")
                .build());
    }

    private String roleInDb(Long id) {
        return jdbc.queryForObject("SELECT role FROM \"user\" WHERE id = ?", String.class, id);
    }

    /**
     * 收尾: 等第二个调用落地, 再关线程池。
     *
     * <p>不等就走的话, 它可能还占着一条数据库连接 —— 而下一条用例正要用连接去拿锁,
     * HikariCP 的池子一旦被占满, 失败会以"下一条用例超时"的样子出现。
     */
    private static void drain(ExecutorService pool, Future<?> future) {
        if (future != null) {
            try {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 这个调用本来就可能是"抛异常结束"的, 这里只关心它有没有收干净
            }
        }
        pool.shutdownNow();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 抓住一个「锁住某一行之后就挂住不提交」的事务, 把窗口撑开。放行靠返回的 latch。
     *
     * <p>用 {@code EntityManager} 直接锁单行而不是调 {@code findByRoleForUpdate}: 后者
     * 会把**所有**管理员行一起锁上, 于是"锁住指定这一行"就做不到了 —— 而下面那条主用例
     * 恰恰需要锁一个**不会被写**的行, 理由见那里的注释。
     */
    private HeldTransaction holdRowLocked(Long userId) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "lock-holder"));
        Future<?> holder = pool.submit(() -> new TransactionTemplate(txManager).execute(status -> {
            em.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE);
            locked.countDown();
            awaitQuietly(release);
            // 回滚而不是提交: 这个事务的作用只是"占着锁", 不该顺手改动任何数据,
            // 否则第二半断言里"另一个事务提交后重读到的状态"就会掺进它的影响
            status.setRollbackOnly();
            return null;
        }));
        assertThat(locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("锁持有者没能拿到那一行的锁").isTrue();
        return new HeldTransaction(pool, release, holder);
    }

    /**
     * 那个"占着锁"的事务的把手。
     *
     * <p><b>必须是 AutoCloseable 而且在 finally 里关。</b> 第一版只在成功路径上放行,
     * 于是<b>用例一旦在断言上失败, 锁就再也没人放</b> —— 持锁线程被 shutdownNow 粗暴打断,
     * 它在后台慢慢回滚, 而下一条用例已经开始跑、并撞上这把还没释放的锁, 一路卡到超时。
     * 结果是一条真正的失败后面跟着一条 30 秒的假失败: 真正的原因被埋在后面那条里,
     * 看报告的人会以为是第二条用例的错。
     */
    private static final class HeldTransaction implements AutoCloseable {
        private final ExecutorService pool;
        private final CountDownLatch releaseLatch;
        private final Future<?> holder;
        private boolean released;

        HeldTransaction(ExecutorService pool, CountDownLatch releaseLatch, Future<?> holder) {
            this.pool = pool;
            this.releaseLatch = releaseLatch;
            this.holder = holder;
        }

        /** 放行持锁事务, 幂等 */
        synchronized void release() {
            if (released) {
                return;
            }
            released = true;
            releaseLatch.countDown();
        }

        @Override
        public synchronized void close() {
            release();
            try {
                // 等它真的结束: 不等的话它会和后面的断言抢连接, 失败信息会指向错误的地方
                holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 这里只负责收拾, 不负责报错 —— 该报的错已经在用例主体里报过了
            }
            pool.shutdownNow();
        }
    }

    // ========== 两个管理员互相降级 ==========

    /**
     * 这是本类的主哨兵。把 {@code findByRoleForUpdate} 换回 {@code countByRole} 之后,
     * "第二个还没返回"那一步会立刻红 —— 因为不加锁的话第二个调用根本不等, 它会数到 3、
     * 放行、提交, 全程毫秒级。
     *
     * <p><b>⚠️ 被锁住的那一行必须是「旁观者」, 不能是 target。</b> 这是第一版踩的坑,
     * 而且踩得很隐蔽: 那一版锁的正是 target 那一行, 于是"第二个调用卡住了"这件事<b>没有
     * 加锁查询也照样成立</b> —— 它会卡在后面那条 UPDATE 上(去改一行被别的事务锁住的行,
     * 数据库当然要拦)。用例于是绿得毫无道理: 它想钉的是"数管理员的时候有没有拿锁",
     * 而它实际观察到的可能是"写的时候撞上了行锁"。反向验证当场把它打出来了 —— 把加锁
     * 查询换回 countByRole, 这条仍然绿, 隔壁那条才红。
     *
     * <p>让旁观者那一行被锁, 就把这两件事分开了: 数管理员时扫到被锁的行 → 等;
     * 而真正要写的那一行没人锁。于是"卡住"只有在**数**这一步拿了锁的前提下才会发生。
     */
    @Test
    @DisplayName("另一个管理员的事务正持着管理员行时: 这一次的「数管理员」会等它, 而不是并排挤过去")
    void theAdminCountWaitsForTheOtherTransaction() throws Exception {
        User actor = userWithRole("ADMIN");
        User target = userWithRole("ADMIN");
        User bystander = userWithRole("ADMIN");
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "second-demotion"));
        Future<?> second = null;
        HeldTransaction first = holdRowLocked(bystander.getId());
        try {
            second = pool.submit(
                    () -> adminService.setUserRole(actor, target.getId(), "USER"));

            Thread.sleep(WINDOW_MS);
            assertThat(second.isDone())
                    .as("第二个降级没有等第一个事务 —— 「数管理员」与「写下去」之间还开着窗口")
                    .isFalse();

            first.release();
            second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            // 顺序要紧: 先放锁再收线程池。断言失败时这一句就是唯一把锁放掉的地方
            first.close();
            drain(pool, second);
        }

        assertThat(roleInDb(target.getId()))
                .as("锁释放后它该正常走下去: 系统里还有别的管理员, 降级是允许的")
                .isEqualTo("USER");
        assertThat(roleInDb(actor.getId())).isEqualTo("ADMIN");
        assertThat(roleInDb(bystander.getId())).isEqualTo("ADMIN");
    }

    // ========== 只剩下一个管理员时 ==========

    /**
     * 另一半: 等待结束之后做出的**判断**必须是对的。
     *
     * <p>只有上面那条"被挡住"的话, 一个死等到底的实现也能过。这条把结果钉住:
     * 系统里只剩 solo 一个管理员, 锁释放后第二个调用重读, 应当数到 1 并拒绝。
     *
     * <p>actor 特意是个普通用户。看着不自洽, 但规则本来就不该依赖调用者手里的 token
     * 是否新鲜(JWT 里的 role 是签发时的快照, 库里可能早就变了), 也不该依赖"改自己"
     * 那一栏是否还拦得住 —— 这一点与 {@code AdminServiceTest.refusesToDemoteTheLastAdmin}
     * 是同一个立场, 只是那条把 count 桩成 1, 这条让真库自己数。
     */
    @Test
    @DisplayName("只剩一个管理员时: 等到锁之后数到 1, 正确拒绝, 而且不落库")
    void theLastAdminIsStillRefusedAfterWaitingForTheLock() throws Exception {
        User solo = userWithRole("ADMIN");
        User helper = userWithRole("USER");
        // 这条锁的正好是 target 那一行, 与上一条相反 —— 这里没问题, 因为它拒绝在
        // 写之前就发生了, 不存在"卡在 UPDATE 上"这条替代路径可以冒充
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "last-admin-demotion"));
        Future<?> second = null;
        HeldTransaction first = holdRowLocked(solo.getId());
        try {
            second = pool.submit(() -> adminService.setUserRole(helper, solo.getId(), "USER"));

            Thread.sleep(WINDOW_MS);
            assertThat(second.isDone())
                    .as("第二个降级没有等第一个事务 —— 加锁查询没接上")
                    .isFalse();

            first.release();

            // 业务异常是从线程里抛出来的, get() 会把它包进 ExecutionException ——
            // 断言要拆到 cause 上, 否则"抛了任何异常"都能让这条绿
            try {
                second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                fail("只剩一个管理员时降级必须被拒绝");
            } catch (ExecutionException e) {
                assertThat(e.getCause())
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("最后一个管理员");
            }
        } finally {
            first.close();
            drain(pool, second);
        }

        assertThat(roleInDb(solo.getId())).as("拒绝之后这一行必须原封不动").isEqualTo("ADMIN");
    }
}
