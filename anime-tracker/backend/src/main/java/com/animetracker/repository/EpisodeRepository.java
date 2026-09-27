package com.animetracker.repository;

import com.animetracker.entity.Anime;
import com.animetracker.entity.Episode;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EpisodeRepository extends JpaRepository<Episode, Long> {
    List<Episode> findByAnimeOrderByEpisodeNumAsc(Anime anime);
}
