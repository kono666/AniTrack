package com.animetracker.repository;

import com.animetracker.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    long countByRole(String role);

    /**
     * 「数一数系统里还有几个管理员」, 但**把数到的这几行锁住**, 直到调用方那个事务结束。
     *
     * <p>它和上面 {@link #countByRole} 唯一的不同就是这条锁, 而这条锁专门服务于
     * {@code AdminService.setUserRole} 里那条不变式: 「这次操作之后至少还剩一个管理员」。
     * 光数不加锁的话, 「数」与「写」之间开着一个窗口 —— 两个管理员同时降级对方, 各自都
     * 数到 2、各自都放行, 提交完系统里 0 个管理员, 而且两个请求都回成功。窗口很窄,
     * 手点几乎撞不上; 撞上就是整个管理端再也进不去, 且没有任何自助恢复的入口。
     *
     * <p><b>为什么是 SELECT ... FOR UPDATE 而不是把计数改成一条带条件的 UPDATE。</b>
     * 要护住的是「count 与 save 之间」这段区间, 不是 count 这一条语句 —— 只有把行锁持有
     * 到事务结束才能覆盖它。{@code PESSIMISTIC_WRITE} 在 H2 与 PostgreSQL 上都落成
     * {@code FOR UPDATE}, 两者行为一致。
     *
     * <p><b>拿到锁之后再数, 读到的是"排队到我这时"的最新已提交状态</b> (READ COMMITTED
     * 下被阻塞的语句在锁释放后会重新求值)。于是并发降级变成串行: 后到的那个会看见前一个
     * 已经把人降下去了, 数到 1, 正确拒绝。
     *
     * <p>⚠️ 返回的是实体列表而不是计数, 但调用方**只能取 size**。别拿这些实体去改:
     * 锁的作用域是事务, 事务一结束锁就没了, 那时再 save 等于没加锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.role = :role")
    List<User> findByRoleForUpdate(@Param("role") String role);
    long countByStatus(String status);

    // ==================== 管理端用户列表(筛选 + 排序 + 分页) ====================
    //
    // 四条取页 + 一条计数, 语句全在 UserQueries 里. 三点与 AnimeRepository 一致:
    //
    //  · **返回 List<User> 而不是 Page<User>**: Page 会顺着方法名再发一条 count,
    //    而这里要的 count 必须由同一份 WHERE 拼出来, 否则"这一页是谁"与"一共多少条"
    //    来自两套各自演化的条件. 所以取页与计数是两条显式的方法, service 配对调用.
    //  · **Pageable 一律不带 Sort**: 排序字面写在 JPQL 里, 再带一个 Sort 会被拼成
    //    第二段 ORDER BY.
    //  · **传 Pageable.unpaged() 就是"全部"**(AdminService.getUserList 走的那条),
    //    显式 ORDER BY 仍然生效 —— ReviewRepository.findAllWithUser 已经验过这一点.
    //
    // createdAt 那两条多一个 :epoch, 只为让 ORDER BY 里不出现 NULL(见
    // UserQueries.ORDER_CREATED_DESC); 计数那条不需要排序, 所以没有它.
    // lastLoginAt 那两条同理 —— 那一列**可空**, 而且存量用户全是空的(见
    // UserQueries.ORDER_LAST_LOGIN_DESC).

    @Query(UserQueries.PAGE_CREATED_DESC)
    List<User> findUserPageByCreatedDesc(@Param("keywordPattern") String keywordPattern,
                                         @Param("role") String role,
                                         @Param("status") String status,
                                         @Param("locked") Boolean locked,
                                         @Param("now") LocalDateTime now,
                                         @Param("epoch") LocalDateTime epoch,
                                         Pageable pageable);

    @Query(UserQueries.PAGE_CREATED_ASC)
    List<User> findUserPageByCreatedAsc(@Param("keywordPattern") String keywordPattern,
                                        @Param("role") String role,
                                        @Param("status") String status,
                                        @Param("locked") Boolean locked,
                                        @Param("now") LocalDateTime now,
                                        @Param("epoch") LocalDateTime epoch,
                                        Pageable pageable);

    /** 用户名两条不需要 :epoch —— username 是 NOT NULL, 排序里本来就没有 NULL */
    @Query(UserQueries.PAGE_USERNAME_ASC)
    List<User> findUserPageByUsernameAsc(@Param("keywordPattern") String keywordPattern,
                                         @Param("role") String role,
                                         @Param("status") String status,
                                         @Param("locked") Boolean locked,
                                         @Param("now") LocalDateTime now,
                                         Pageable pageable);

    @Query(UserQueries.PAGE_USERNAME_DESC)
    List<User> findUserPageByUsernameDesc(@Param("keywordPattern") String keywordPattern,
                                          @Param("role") String role,
                                          @Param("status") String status,
                                          @Param("locked") Boolean locked,
                                          @Param("now") LocalDateTime now,
                                          Pageable pageable);

    /**
     * 最近登录两条. 参数表与 {@link #findUserPageByCreatedDesc} **逐字相同**(含
     * {@code :epoch}) —— 两列都是可空时间戳, 排序形状也一样是三段式.
     */
    @Query(UserQueries.PAGE_LAST_LOGIN_DESC)
    List<User> findUserPageByLastLoginDesc(@Param("keywordPattern") String keywordPattern,
                                           @Param("role") String role,
                                           @Param("status") String status,
                                           @Param("locked") Boolean locked,
                                           @Param("now") LocalDateTime now,
                                           @Param("epoch") LocalDateTime epoch,
                                           Pageable pageable);

    @Query(UserQueries.PAGE_LAST_LOGIN_ASC)
    List<User> findUserPageByLastLoginAsc(@Param("keywordPattern") String keywordPattern,
                                          @Param("role") String role,
                                          @Param("status") String status,
                                          @Param("locked") Boolean locked,
                                          @Param("now") LocalDateTime now,
                                          @Param("epoch") LocalDateTime epoch,
                                          Pageable pageable);

    @Query(UserQueries.COUNT_USERS)
    long countUsers(@Param("keywordPattern") String keywordPattern,
                    @Param("role") String role,
                    @Param("status") String status,
                    @Param("locked") Boolean locked,
                    @Param("now") LocalDateTime now);

    /**
     * 失败计数 +1, 自增在**数据库里**做.
     *
     * <p>它替换掉的是「读出来 +1 再 save」那种写法, 那是典型的丢失更新:
     * 两个请求同时读到 3, 各自写回 4 —— 两次失败只记了一次. 后果不是「计数偏小」
     * 这么轻: 阈值是 5, 攻击者只要并发提交, 计数就涨得比尝试次数慢, 爆破窗口被
     * 拉长, 而日志上一切正常.
     *
     * <p>必须 COALESCE: {@code failed_attempts} 这一列可以**是 NULL**(老库加这列时
     * 没有默认值, 见 User 实体上的说明), 而 SQL 里 NULL + 1 还是 NULL ——
     * 少了这一层, 老账号的计数会永远停在空值上, 阈值形同不存在.
     */
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.failedAttempts = COALESCE(u.failedAttempts, 0) + 1 WHERE u.id = :id")
    void incrementFailedAttempts(@Param("id") Long id);

    /** 自增之后把计数读回来判断, 空值按 0 算(理由同上) */
    @Query("SELECT COALESCE(u.failedAttempts, 0) FROM User u WHERE u.id = :id")
    int readFailedAttempts(@Param("id") Long id);

    /**
     * 锁定到某个时刻, 同时把计数清零. 两件事写在同一条语句里, 因为它们是同一件事:
     * 把计数留在阈值上, 锁定期一满、用户再错一次就立刻又被锁上 —— 限时锁定会退化成
     * 永久锁定. 名字里带 Reset 就是为了让调用方看见后半句还在.
     */
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.failedAttempts = 0, u.lockedUntil = :until WHERE u.id = :id")
    void lockAndResetFailures(@Param("id") Long id, @Param("until") LocalDateTime until);
}
