package com.animetracker.repository;

import com.animetracker.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {
    Optional<Tag> findByName(String name);

    /**
     * 一次取回若干个标签名对应的行.
     *
     * <p>存在的理由就是替掉 {@code findAll()} + 内存过滤: 那种写法每次按标签查番剧
     * 都要把整张 tag 表读出来, 而这里 {@code name} 上有唯一约束 (uk_tag_name),
     * 一条 IN 查询就能走索引定位 —— 与 tag 表有多少行无关.
     */
    List<Tag> findByNameIn(Collection<String> names);

    @Query("SELECT t.name, COUNT(at.id) FROM Tag t JOIN AnimeTag at ON t.id = at.tag.id GROUP BY t.name ORDER BY COUNT(at.id) DESC")
    List<Object[]> findAllWithCount();

    @Query("SELECT COUNT(at) FROM AnimeTag at WHERE at.tag.id = ?1")
    long countAnimeByTagId(Long tagId);
}
