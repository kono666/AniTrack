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
    List<AnimeTracking> findByUserAndStatus(User user, String status);
    long countByUserAndStatus(User user, String status);
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
