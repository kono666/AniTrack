package com.animetracker.repository;

import com.animetracker.entity.SubjectStaff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 制作人员的读写. 形状同 {@link SubjectCharacterRepository} */
public interface SubjectStaffRepository extends JpaRepository<SubjectStaff, Long> {

    List<SubjectStaff> findBySubjectIdOrderBySortOrderAsc(Integer subjectId);

    @Modifying
    @Transactional
    @Query("DELETE FROM SubjectStaff s WHERE s.subjectId = :subjectId")
    int deleteBySubjectId(@Param("subjectId") Integer subjectId);
}
