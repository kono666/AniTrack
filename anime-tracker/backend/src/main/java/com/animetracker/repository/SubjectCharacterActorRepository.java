package com.animetracker.repository;

import com.animetracker.entity.SubjectCharacterActor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 声优的读写. 形状与 {@link SubjectCharacterRepository} 一样 ——
 * 一次取整条目的、一次删整条目的, 都由 {@code subject_id} 那条索引承担.
 *
 * <p>读的是**整个条目**的声优而不是"某个角色的声优": 界面要一次画出全部角色卡,
 * 每个卡上的 CV 从这一个列表里按 {@code characterId} 分组贴上去。按角色逐条查会是
 * N+1(128 个角色 128 次查询), 而这一张表在一个条目里最多也就几百行。
 */
public interface SubjectCharacterActorRepository
        extends JpaRepository<SubjectCharacterActor, Long> {

    List<SubjectCharacterActor> findBySubjectIdOrderBySortOrderAsc(Integer subjectId);

    /** 见 {@link SubjectCharacterRepository#deleteBySubjectId} —— 同一条事务约束 */
    @Modifying
    @Transactional
    @Query("DELETE FROM SubjectCharacterActor a WHERE a.subjectId = :subjectId")
    int deleteBySubjectId(@Param("subjectId") Integer subjectId);
}
