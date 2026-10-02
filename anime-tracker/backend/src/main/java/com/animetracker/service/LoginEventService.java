package com.animetracker.service;

import com.animetracker.config.LoginEventProperties;
import com.animetracker.entity.LoginEvent;
import com.animetracker.repository.LoginEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 登录事件的记录、聚合与过期清理。
 *
 * <p>三个职责放在一起，是因为它们共享同一个「窗口」概念（今天 / 近 7 天 / 近 14 天 /
 * 保留期），而这个概念一旦被拆到两处，两处迟早会各自演化出相差一天的定义。
 */
@Service
public class LoginEventService {

    private static final Logger log = LoggerFactory.getLogger(LoginEventService.class);

    /**
     * 曲线画多少天。
     *
     * <p>14 而不是 7：周活那个数已经把 7 天压缩成一个点，再画一条 7 天的曲线等于把同一个
     * 窗口说两遍。14 天能看出「上周比上上周」这种最小可用的趋势。
     */
    private static final int TREND_DAYS = 14;

    private final LoginEventRepository loginEventRepository;
    private final LoginEventProperties props;
    private final IsolatedInsert isolatedInsert;

    public LoginEventService(LoginEventRepository loginEventRepository,
                             LoginEventProperties props,
                             IsolatedInsert isolatedInsert) {
        this.loginEventRepository = loginEventRepository;
        this.props = props;
        this.isolatedInsert = isolatedInsert;
    }

    // ========== 写 ==========

    /**
     * 记一次登录尝试。<b>这个方法永远不会抛异常。</b>
     *
     * <p><b>为什么「不会抛」是一条要写进注释的约定。</b> 它是被 {@code UserService.login}
     * 在**判定已经做完之后**调用的旁路：写进去的是一条统计样本，而它旁边那行
     * {@code throw} 才是用户真正要的结果。让一个统计写入有机会把一次成功的登录变成
     * 500，是拿用户的登录换我们的一张图 —— 这笔账怎么算都不划算。所以这里把
     * {@code Exception} 整个兜住，只留一条 warn。
     *
     * <p>兜住的是 {@code Exception} 而不是某个具体类型：数据库连不上、唯一约束、字段
     * 超长、Hibernate 的懒加载异常 —— 这一层不需要知道会坏在哪里，只需要保证坏了
     * 也不往外传。
     *
     * <p><b>为什么要包在 {@link IsolatedInsert} 里。</b> 两个理由，第二个才是真理由：
     * <ul>
     *   <li>今天 {@code UserService.login} 没有外层事务，用它等价于「开一个新事务」；</li>
     *   <li>但上面那句「永远不会抛」要成立，光 catch 住是不够的 —— 如果这次插入跑在
     *       别人的事务里，一次失败会**把那个事务标记成 rollback-only**，调用方即使当场
     *       捕获，它所在的事务在提交时照样抛 {@code UnexpectedRollbackException}。
     *       放进独立事务之后，「记不上账不影响业务」才从「今天恰好没有外层事务」变成
     *       一条结构上的保证。</li>
     * </ul>
     * 这正是 {@code IsolatedInsert} 类注释里说的第一个用途，不是误用。
     *
     * @param userId  用户名不存在时传 null —— 那一类失败没有用户可挂，但它恰恰最该被记下来
     * @param ip      来源地址，取不到就传 null（不塞占位值）
     * @param success 这次尝试进来了没有
     */
    public void recordAttempt(Long userId, String ip, boolean success) {
        try {
            isolatedInsert.attempt(() -> loginEventRepository.save(LoginEvent.builder()
                    .userId(userId)
                    .ip(ip)
                    .success(success)
                    .build()));
        } catch (Exception e) {
            // 不带上堆栈: 这条日志的唯一用途是「知道它坏了」。真的去查的时候,
            // 坏在这一层的绝大多数原因是数据库不可用, 而那件事在别处有更响的日志。
            log.warn("登录事件写入失败(不影响本次登录结果): {}", e.toString());
        }
    }

    // ========== 读（看板的「谁在今天来过」那一块） ==========

    /**
     * 看板要的活跃度一组数：今天 / 近 7 天 / 近 14 天曲线 / 近 24 小时失败次数。
     *
     * <p><b>「今天」是服务端本地日。</b> 与 {@code last_login_at}、{@code created_at}
     * 同一口径（都是 {@code LocalDateTime.now()} 写进去的），所以整张看板的「一天」
     * 只有一个定义，不会出现「用户数按 UTC 算、日活按本地算」这种对不上的情况。
     *
     * <p>{@code trackedSince} 是「库里最早那条事件是哪天」，没有就回 null —— 前端拿它
     * 区分<b>两种完全相反的情形</b>：一条事件都没有是「这个功能刚上线，还没有数据」，
     * 而一个全是 0 的 14 天窗口是「这两周真的没人来」。两个都画成一条平线的话，
     * 读图的人一定会把前者读成后者。
     */
    public Map<String, Object> activity() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        // 窗口一律左闭右开 [from, to), 右端是明天零点. 这样「今日活跃」与曲线最后一个点
        // 必然相等 —— 两个并排显示的数字, 相等必须是结构上的, 不能靠"未来时刻的行不可能
        // 存在"撑着(理由见 LoginEventRepository.countActiveBetween).
        LocalDateTime tomorrowStart = todayStart.plusDays(1);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dau", loginEventRepository.countActiveBetween(todayStart, tomorrowStart));
        // 含今天一共 7 天: 「近 7 天」在 0 点这一刻应当等于「今天」, 而不是少一天
        out.put("wau",
                loginEventRepository.countActiveBetween(todayStart.minusDays(6), tomorrowStart));
        out.put("failedAttempts24h", loginEventRepository.countFailuresBetween(
                LocalDateTime.now().minusHours(24), tomorrowStart));
        out.put("trackedSince", loginEventRepository.findFirstByOrderByCreatedAtAsc()
                .map(e -> e.getCreatedAt().toLocalDate().toString())
                .orElse(null));
        out.put("trend", trend(todayStart));
        return out;
    }

    /**
     * 近 {@value #TREND_DAYS} 天的活跃曲线，**一天不缺**：库里没有事件的那天补 0。
     *
     * <p><b>为什么补齐不能交给前端。</b> 补齐需要一个「窗口从哪天开始」的定义，而那是
     * 服务端的概念（见 {@code LoginEventRepository.countDailyActiveSince} 的注释）。
     * 让前端自己推，就等于把「今天减 13 天」这条规则抄了第二份。
     *
     * <p>按天取值时一律走 {@link Number} 再转换：{@code extract(...)} 在 H2 上回的是
     * {@code Integer}、在 PostgreSQL 上有可能回 {@code Long} 或 {@code BigDecimal}，
     * 直接强转 {@code (Integer)} 在另一套库上就是 {@code ClassCastException}。
     * 这个仓库有两套迁移脚本，所以「只在 H2 上碰巧能跑」不算数。
     */
    private List<Map<String, Object>> trend(LocalDateTime todayStart) {
        LocalDate firstDay = todayStart.toLocalDate().minusDays(TREND_DAYS - 1L);

        Map<LocalDate, Long> byDay = new HashMap<>();
        for (Object[] row : loginEventRepository.countDailyActiveSince(firstDay.atStartOfDay())) {
            LocalDate day = LocalDate.of(((Number) row[0]).intValue(),
                    ((Number) row[1]).intValue(),
                    ((Number) row[2]).intValue());
            byDay.put(day, ((Number) row[3]).longValue());
        }

        List<Map<String, Object>> trend = new ArrayList<>(TREND_DAYS);
        for (int i = 0; i < TREND_DAYS; i++) {
            LocalDate day = firstDay.plusDays(i);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day.toString());
            point.put("users", byDay.getOrDefault(day, 0L));
            trend.add(point);
        }
        return trend;
    }

    // ========== 保留期清理 ==========

    /**
     * 每天删掉早于保留期的事件。这一条是 {@code login_event} 唯一的删除路径。
     *
     * <p>时刻挑 03:43，与 {@code DataRefreshService} 那条每日全量（04:17）错开半小时 ——
     * 两条定时任务都碰数据库，撞在一起只是让彼此变慢，没有任何好处。
     *
     * <p><b>保留期配成 0 或负数时直接跳过，而不是照删。</b> 那时的「早于 现在−保留期」
     * 会变成「早于现在」，一条 DELETE 就把整张表清空 —— 而写下那个 0 的人多半想的是
     * 「关掉清理」。把一个想关掉清理的配置读成「清空所有历史」，是这个类里唯一
     * 后果不可逆的分支，所以它值得单独一条判断和一条 warn。
     */
    @Scheduled(cron = "0 43 3 * * *")
    @Transactional
    public void purgeExpired() {
        Duration retention = props.getRetention();
        if (retention == null || retention.isZero() || retention.isNegative()) {
            log.warn("登录事件保留期配成了 {}, 跳过清理(这张表会一直涨下去)", retention);
            return;
        }

        LocalDateTime before = LocalDateTime.now().minus(retention);
        try {
            int deleted = loginEventRepository.deleteOlderThan(before);
            if (deleted > 0) {
                log.info("[登录事件清理] 删除 {} 条早于 {} 的记录", deleted, before);
            }
        } catch (Exception e) {
            // 删不掉不是急事: 多留一天而已, 明天再来. 但要让它在日志里看得见,
            // 因为「清理一直在失败」和「清理一直没跑」在数据上长得一模一样。
            log.warn("[登录事件清理] 失败, 留到明天再试: {}", e.toString());
        }
    }
}
