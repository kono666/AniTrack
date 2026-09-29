package com.animetracker.repository;

import com.animetracker.entity.AnimeTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface AnimeTagRepository extends JpaRepository<AnimeTag, Long> {

    List<AnimeTag> findByAnimeId(Integer animeId);

    @Query("SELECT at.animeId FROM AnimeTag at WHERE at.tag.id = ?1")
    List<Integer> findAnimeIdsByTagId(Long tagId);

    @Query("SELECT at.animeId, COUNT(at) FROM AnimeTag at WHERE at.animeId IN ?1 GROUP BY at.animeId")
    List<Object[]> countTagsForAnimeIds(List<Integer> animeIds);

    /**
     * 批量删除要在事务里跑. 事务注解统一用 Spring 那份(org.springframework...):
     * 这里原来写的是 jakarta.transaction.Transactional —— Spring 其实也认它
     * (由 JtaTransactionAnnotationParser 解析, 映射 value/rollbackOn), 所以它
     * 并不是坏的; 但它表达不了 readOnly / timeout, 而两种注解在仓库层并存会让
     * 读的人分不清"是随便挑了一个"还是"作者知道区别". 全仓库统一一种.
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM AnimeTag at WHERE at.animeId = ?1")
    void deleteByAnimeId(Integer animeId);

    /**
     * 关联表里有没有行 —— 用来判断"标签迁移跑过没有", 三个调用点都在热路径上
     * (每次按标签查番剧都要问一次).
     *
     * <p>原来写的是 {@code SELECT COUNT(at) > 0 FROM AnimeTag at}: 要回答"有没有",
     * 却先把整张表数一遍. 这条判定的答案在第一行就定了, 所以改成 exists 派生查询 ——
     * Spring Data 对 exists 投影会加 {@code setMaxResults(1)}(实测生成的 SQL 带
     * {@code fetch first ? rows only}), 于是它读一行就返回, 与表里有多少行无关.
     *
     * <p>{@code anime_id} 上有 NOT NULL, 所以 {@code IsNotNull} 过滤掉的恰好是"空表"
     * 这一个情况, 语义就是原来的 {@code hasAny()}.
     */
    boolean existsByAnimeIdNotNull();
}
