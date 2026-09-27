package com.animetracker.repository;

import com.animetracker.entity.Review;
import com.animetracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    Optional<Review> findByUserAndSubjectId(User user, Integer subjectId);
    List<Review> findBySubjectIdOrderByCreatedAtDesc(Integer subjectId);
    List<Review> findByUserOrderByCreatedAtDesc(User user);
    List<Review> findAllByOrderByCreatedAtDesc();
    long countBySubjectId(Integer subjectId);
    long count();
}
