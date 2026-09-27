package com.animetracker.repository;

import com.animetracker.entity.EpisodeWatched;
import com.animetracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EpisodeWatchedRepository extends JpaRepository<EpisodeWatched, Long> {
    List<EpisodeWatched> findByUserAndAnimeId(User user, Integer animeId);
    boolean existsByUserAndAnimeIdAndEpisodeNum(User user, Integer animeId, Integer episodeNum);
    void deleteByUserAndAnimeIdAndEpisodeNum(User user, Integer animeId, Integer episodeNum);
    long countByUser(User user);
}
