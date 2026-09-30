package com.animetracker.repository;

import com.animetracker.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
