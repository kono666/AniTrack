package com.animetracker.repository;

import com.animetracker.entity.AnimeTracking;
import com.animetracker.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface TrackingRepository extends JpaRepository<AnimeTracking, Long> {
    Optional<AnimeTracking> findByUserAndSubjectId(User user, Integer subjectId);
    List<AnimeTracking> findByUserOrderByUpdatedAtDesc(User user);

    /**
     * 同一排序口径, 但只要前若干条.
     *
     * <p>首页「最近活动」只需要 10 条, 而它原来走的是上面那个不带 Pageable 的版本:
     * 把该用户的<b>全部</b>追番读进内存, 再 {@code limit(10)} 丢掉其余部分.
     * 追番 500 部的人打开首页就是白白读 500 行.
     *
     * <p>刻意保留同名重载而不是另起一个名字: 两个方法的关系就是「要不要全量」,
     * 名字一样, 调用点一眼能看出排序口径没变、变的只是取多少.
     */
    List<AnimeTracking> findByUserOrderByUpdatedAtDesc(User user, Pageable pageable);
    List<AnimeTracking> findByUserAndStatus(User user, String status);
    long countByUserAndStatus(User user, String status);

    /**
     * 该用户追了多少部 —— 后台用户详情页四个计数之一.
     *
     * <p>为什么不复用 {@code findByUserOrderByUpdatedAtDesc(user).size()}: 那是把每一行
     * 都读成实体再数个数, 追番几百部的人就是白读几百行 —— 与
     * {@code findSubjectTrackingCounts} 上面那句注释是同一条理由. 而详情页那三次
     * 取页都已经被 {@code DETAIL_LIST_LIMIT} 封顶, 这里的计数是唯一一个「想要全部」的数字,
     * 更不能靠把全部读进来得到.
     */
    long countByUser(User user);
    long countBySubjectIdAndStatus(Integer subjectId, String status);
    long countBySubjectId(Integer subjectId);

    /**
     * 全站热度榜: 按追番人数分组统计.
     * 返回 [subjectId, 追番人数], 已按人数倒序, 用 Pageable 控制取前几名.
     *
     * 运营分析用. 注意这里是数据库层聚合, 不要在 Java 里循环单查 —— 那是 N+1.
     */
    @Query("SELECT t.subjectId, COUNT(t) FROM AnimeTracking t "
            + "GROUP BY t.subjectId ORDER BY COUNT(t) DESC")
    List<Object[]> findSubjectTrackingCounts(Pageable pageable);
}
