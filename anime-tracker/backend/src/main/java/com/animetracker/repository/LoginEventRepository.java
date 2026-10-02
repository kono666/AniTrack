package com.animetracker.repository;

import com.animetracker.entity.LoginEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 登录事件的写与读。
 *
 * <p><b>写只有一条路</b>（{@link com.animetracker.service.LoginEventService#record}），
 * 和 {@code AdminActionLogRepository} 一样：这张表的价值在于它是「发生过什么」的记录，
 * 所以除了保留期清理，没有任何按条件改写或删除的口子。
 *
 * <p><b>读全部是「扫一段时间窗」。</b> 看板的三个数（日活 / 周活 / 近 14 天曲线）与保留期
 * 清理的 DELETE 都是，一条 {@code (created_at)} 索引全覆盖（见 V16）。今天没有任何查询
 * 是从「某个用户」出发的，所以刻意没有 {@code (user_id, created_at)} —— 那是纯写放大。
 *
 * <p><b>为什么日活是 {@code COUNT(DISTINCT user_id)} 而不是行数。</b> 一个用户今天登录
 * 27 次仍然是一个人。行数是「登录次数」，是另一个指标，这里不要它 —— 两个数混在一个
 * 名字下，是那种事后谁也说不清哪个对的口径事故。
 */
public interface LoginEventRepository extends JpaRepository<LoginEvent, Long> {

    /**
     * 窗口 {@code [from, to)} 内**成功**登录过的去重人数（日活 / 周活的唯一实现）。
     *
     * <p>只数成功的那一半：失败了没进来的人不算「活跃」。失败次数另有
     * {@link #countFailuresBetween}，两件事分开数，不在这里做减法。
     *
     * <p>{@code userId} 可空，而 {@code COUNT(DISTINCT)} 天然不数 NULL —— 恰好就是
     * 「用户名不存在」那一类失败不该被算进人数里的意思（成功事件不会有 NULL userId，
     * 所以这里的 NULL 只可能来自失败事件，而它们先被 {@code success = true} 滤掉了）。
     *
     * <p><b>为什么右端也是参数，而不是只给一个下界。</b> 看板上「今日活跃」与那条曲线的
     * 最后一个点说的是同一件事，两个数并排放在一起 —— 只要有一个报了未来时刻的行、
     * 另一个没报，它们就会对不上，而看板上出现两个互相矛盾的数字时，没人解释得清。
     * 上下界都给之后，两处用的是<b>同一个窗口</b>，相等是结构上的，不靠「未来时刻的行
     * 不可能存在」这种话撑着。
     */
    @Query("SELECT COUNT(DISTINCT e.userId) FROM LoginEvent e"
            + " WHERE e.success = true AND e.createdAt >= :from AND e.createdAt < :to")
    long countActiveBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 窗口 {@code [from, to)} 内**失败**尝试的条数（不分用户）。
     *
     * <p>它按「次」而不是按「人」：爆破的特征是同一来源的重试次数，去重反而会把信号抹掉。
     */
    @Query("SELECT COUNT(e) FROM LoginEvent e"
            + " WHERE e.success = false AND e.createdAt >= :from AND e.createdAt < :to")
    long countFailuresBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 近 N 天的活跃曲线：一天一行 {@code [年, 月, 日, 去重人数]}，按时间升序。
     *
     * <p><b>为什么按 {@code year/month/day} 分组而不是 {@code CAST(... AS date)}。</b>
     * {@code year()/month()/day()} 是 HQL 标准函数，落到 SQL 是 {@code extract(...)}，
     * H2 与 PostgreSQL 都原生支持；而强转成 date 的写法要经过方言对 {@code LocalDate}
     * 的映射，是本项目用不上的额外一层可移植性风险（这个仓库有 H2/PG 双份迁移脚本，
     * 任何「在 H2 上碰巧能跑」的语句都得当成没验过）。
     *
     * <p>⚠️ <b>返回的是「有事件的那些天」，不是完整的一段日期。</b> 零登录的那一天在这条
     * 查询里根本不存在 —— 补齐成 14 个点是调用方（{@code LoginEventService}）的事，
     * 因为「窗口从哪天开始」是它的概念，SQL 不该知道。这样分开还有个好处：这里返回的
     * 行数就是「真有数据的天数」，{@code trackedSince} 那个「数据不足」的判据因此是免费的。
     */
    @Query("SELECT year(e.createdAt), month(e.createdAt), day(e.createdAt),"
            + " COUNT(DISTINCT e.userId) FROM LoginEvent e"
            + " WHERE e.success = true AND e.createdAt >= :from"
            + " GROUP BY year(e.createdAt), month(e.createdAt), day(e.createdAt)"
            + " ORDER BY year(e.createdAt), month(e.createdAt), day(e.createdAt)")
    List<Object[]> countDailyActiveSince(@Param("from") LocalDateTime from);

    /**
     * 库里最早的那条事件，用来回答「这个库从什么时候开始记的」。
     *
     * <p>看板拿它判断该不该画曲线：一条都没有（{@code Optional.empty()}）时，曲线画出来
     * 会是一条**假的平线** —— 看起来像「这几天没人来」，实际是「这个功能刚上线，还没有
     * 任何数据」。两种情形的运营含义完全相反，界面上必须分开。
     */
    Optional<LoginEvent> findFirstByOrderByCreatedAtAsc();

    /**
     * 删掉早于 {@code before} 的事件，返回删了几行。
     *
     * <p>这张表只增不减，一行一个登录事件，不清就是无限增长 —— 一个没有任何读路径会
     * 回溯到那么久的表，留着只是占地。保留期的取值与理由见
     * {@link com.animetracker.config.LoginEventProperties}。
     *
     * <p>走的是 {@code (created_at)} 索引，不是全表扫。
     */
    @Modifying
    @Query("DELETE FROM LoginEvent e WHERE e.createdAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
