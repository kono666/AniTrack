package com.animetracker.repository;

import com.animetracker.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {
    Optional<Tag> findByName(String name);

    @Query("SELECT t.name, COUNT(at.id) FROM Tag t JOIN AnimeTag at ON t.id = at.tag.id GROUP BY t.name ORDER BY COUNT(at.id) DESC")
    List<Object[]> findAllWithCount();

    @Query("SELECT COUNT(at) FROM AnimeTag at WHERE at.tag.id = ?1")
    long countAnimeByTagId(Long tagId);
}
