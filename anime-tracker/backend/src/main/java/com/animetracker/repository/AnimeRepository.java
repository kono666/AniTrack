package com.animetracker.repository;

import com.animetracker.entity.Anime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface AnimeRepository extends JpaRepository<Anime, Integer> {
    List<Anime> findByOrderByRankAsc();
    List<Anime> findByOrderByRatingDesc();
    List<Anime> findByOrderByDateDesc();

    @Query("SELECT a FROM Anime a WHERE a.titleCn LIKE CONCAT('%', :keyword, '%') OR a.title LIKE CONCAT('%', :keyword, '%')")
    List<Anime> searchByKeyword(String keyword);

    List<Anime> findBySeason(String season);
}
