package com.animetracker.anime.repository;

import com.animetracker.anime.domain.Anime;
import com.animetracker.anime.domain.Episode;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EpisodeRepository extends JpaRepository<Episode, Long> {
    List<Episode> findByAnimeOrderByEpisodeNumAsc(Anime anime);
}
