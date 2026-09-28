package com.animetracker.repository;

import com.animetracker.entity.EpisodeWatched;
import com.animetracker.entity.User;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EpisodeWatchedRepository extends JpaRepository<EpisodeWatched, Long> {
    List<EpisodeWatched> findByUserAndAnimeId(User user, Integer animeId);
    boolean existsByUserAndAnimeIdAndEpisodeNum(User user, Integer animeId, Integer episodeNum);

    /**
     * 派生删除方法会发一条 bulk delete, 必须在事务里跑.
     *
     * 以前这个事务由 StatsService.toggleEpisode 上的 @Transactional 提供.
     * 那个注解后来被摘掉了(理由见该方法), 所以事务边界落到这里 —— 与
     * AnimeTagRepository.deleteByAnimeId 的写法一致.
     */
    @Transactional
    void deleteByUserAndAnimeIdAndEpisodeNum(User user, Integer animeId, Integer episodeNum);

    long countByUser(User user);
}
