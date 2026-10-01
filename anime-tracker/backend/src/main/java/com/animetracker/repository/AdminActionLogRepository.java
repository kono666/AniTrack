package com.animetracker.repository;

import com.animetracker.entity.AdminActionLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 操作账本的读与写。
 *
 * <p><b>写入只有一处</b>（{@code AdminService.record}），所以这里没有「按 action 删」
 * 「按时间清理」之类的口子 —— 账本能被改动的那一天，它就不再是账本了。要清历史的话，
 * 那是运维拿 SQL 去做的事，不该有接口。
 *
 * <p>读只有一页：时间倒序、可按 action 精确筛、可按 target 精确筛。{@code :x IS NULL OR ...}
 * 这个写法与 {@code UserQueries} 同一套路 —— 传 null 就是「不筛」，由一条语句服务两种情形，
 * 免得两个入口各有一份物理定义、迟早对不上。
 *
 * <p><b>{@code targetType}/{@code targetId} 是给用户详情页用的，不是一个新的公开筛选。</b>
 * {@code GET /api/admin/actions} 没有这两个参数，{@code AdminService.getActionPage} 也刻意
 * 恒传 null（见那边的注释）—— 于是列表页走的还是「只有 action 能筛」那条路，两个新谓词
 * 各自短路成真，SQL 的效果与加它们之前逐字相同。
 */
public interface AdminActionLogRepository extends JpaRepository<AdminActionLog, Long> {

    /**
     * 与 {@link #findPage} **同一份 WHERE** 的计数。两处的条件必须一起改。
     *
     * <p>{@code targetId} 用包装类型 {@code Long} 而不是 {@code long}：{@code :targetId IS NULL}
     * 对基本类型永远为假，那样「不筛 target」那一支会变成「筛 target = 0」—— 账本列表页
     * 会安静地变成空页。
     */
    @Query("SELECT COUNT(l) FROM AdminActionLog l"
            + " WHERE (:action IS NULL OR l.action = :action)"
            + " AND (:targetType IS NULL OR l.targetType = :targetType)"
            + " AND (:targetId IS NULL OR l.targetId = :targetId)")
    long countPage(@Param("action") String action,
                   @Param("targetType") String targetType,
                   @Param("targetId") Long targetId);

    /**
     * 取一页。
     *
     * <p>排序是 {@code created_at DESC, id DESC}，第二键不是装饰：{@code created_at} 是
     * 应用侧写进去的，同一毫秒内落两行完全可能，而没有稳定序时翻页会重复或丢行。
     * {@code id} 由 IDENTITY 单调递增，拿它兜底就是「插入顺序」。
     */
    @Query("SELECT l FROM AdminActionLog l"
            + " WHERE (:action IS NULL OR l.action = :action)"
            + " AND (:targetType IS NULL OR l.targetType = :targetType)"
            + " AND (:targetId IS NULL OR l.targetId = :targetId)"
            + " ORDER BY l.createdAt DESC, l.id DESC")
    List<AdminActionLog> findPage(@Param("action") String action,
                                  @Param("targetType") String targetType,
                                  @Param("targetId") Long targetId,
                                  Pageable pageable);
}
