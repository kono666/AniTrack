package com.animetracker.repository;

import com.animetracker.entity.SubjectRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 关联条目的读写. 形状同 {@link SubjectCharacterRepository} */
public interface SubjectRelationRepository extends JpaRepository<SubjectRelation, Long> {

    List<SubjectRelation> findBySubjectIdOrderBySortOrderAsc(Integer subjectId);

    @Modifying
    @Transactional
    @Query("DELETE FROM SubjectRelation r WHERE r.subjectId = :subjectId")
    int deleteBySubjectId(@Param("subjectId") Integer subjectId);
}
