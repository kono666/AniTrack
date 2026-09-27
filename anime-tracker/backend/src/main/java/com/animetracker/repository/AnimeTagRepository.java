package com.animetracker.repository;

import com.animetracker.entity.AnimeTag;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnimeTagRepository extends JpaRepository<AnimeTag, Long> {

    List<AnimeTag> findByAnimeId(Integer animeId);

    @Query("SELECT at.animeId FROM AnimeTag at WHERE at.tag.id = ?1")
    List<Integer> findAnimeIdsByTagId(Long tagId);

    @Query("SELECT at.animeId, COUNT(at) FROM AnimeTag at WHERE at.animeId IN ?1 GROUP BY at.animeId")
    List<Object[]> countTagsForAnimeIds(List<Integer> animeIds);

    @Transactional
    @Modifying
    @Query("DELETE FROM AnimeTag at WHERE at.animeId = ?1")
    void deleteByAnimeId(Integer animeId);

    @Query("SELECT COUNT(at) > 0 FROM AnimeTag at")
    boolean hasAny();
}
