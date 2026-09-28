package com.animetracker.repository;

import com.animetracker.entity.Review;
import com.animetracker.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    Optional<Review> findByUserAndSubjectId(User user, Integer subjectId);
    List<Review> findBySubjectIdOrderByCreatedAtDesc(Integer subjectId);
    List<Review> findByUserOrderByCreatedAtDesc(User user);
    long countBySubjectId(Integer subjectId);
    long count();

    /**
     * 每个分数各有几条 —— 评分统计用, 返回的每行是 {@code [分数, 条数]}, 最多十行.
     *
     * <p>为什么是聚合而不是把该番的评论取回来在内存里数: 那个写法要走
     * {@link #findBySubjectIdOrderByCreatedAtDesc}, 它把**每一行短评都读成一个实体**
     * (评论正文、时间、作者外键全都读进来), 只为了算三个数字; 而且那句 ORDER BY
     * 是白排的 —— 统计结果与顺序无关. 详情页每打开一次就走一遍这条路,
     * 评论多的番就是白白读几百行.
     *
     * <p>换成 GROUP BY 之后, 数据库只回十行以内的计数, 读进来的实体是 0 个
     * (投影不是实体), 与评论条数无关. 用例数着这两个数(见 QueryCountIntegrationTest).
     */
    @Query("SELECT r.rating, COUNT(r) FROM Review r WHERE r.subjectId = ?1 GROUP BY r.rating")
    List<Object[]> countByRating(Integer subjectId);

    /**
     * 某部番的评论 + 作者, 一次取回; 取多少条由 Pageable 决定.
     *
     * <p>为什么要 JOIN FETCH: {@code Review.user} 是 LAZY 的, 而列表里每一条都要读
     * 作者的名字和头像. 不 fetch 就是标准的 N+1 —— 20 条评论 = 20 次「按 id 查用户」,
     * 而这 20 次查询的结果就在上一句已经读出来的那几行里.
     *
     * <p>这里用 JOIN FETCH 而不是 @EntityGraph, 只因为查询本来就要写 ORDER BY 和分页,
     * 顺手写在一起, 少一个要跟着改的注解. 两者等价.
     *
     * <p>对 @ManyToOne 做 fetch join 再分页是安全的: 一行评论仍然只对应一行用户,
     * 结果集不会被放大, 所以 limit 是真的下推到 SQL 的. (若是集合关联,
     * Hibernate 就只能把整个结果集读进内存再切页, 并打印 firstResult/maxResults 警告.)
     */
    @Query("SELECT r FROM Review r JOIN FETCH r.user WHERE r.subjectId = ?1 "
            + "ORDER BY r.createdAt DESC")
    List<Review> findPageBySubjectIdWithUser(Integer subjectId, Pageable pageable);

    /**
     * 全部评论 + 作者(管理端).
     *
     * <p>同样是为了避免逐条加载作者. 管理端的分页留到 4.6 —— 这里的量级远小于
     * 用户侧, 而先把 N+1 去掉是这次的目标.
     */
    @Query("SELECT r FROM Review r JOIN FETCH r.user ORDER BY r.createdAt DESC")
    List<Review> findAllWithUser(Pageable pageable);
}
