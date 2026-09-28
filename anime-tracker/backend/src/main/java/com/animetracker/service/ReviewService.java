package com.animetracker.service;

import com.animetracker.dto.RequestDTO.ReviewRequest;
import com.animetracker.entity.Review;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.ReviewRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ReviewService {

    /** 默认每页条数, 与搜索/筛选/排行保持一致, 少一个要记的数字 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 上限. 与控制器上的 @Max 是同一个值, 改的时候别只改一处 */
    public static final int MAX_PAGE_SIZE = 50;

    private final ReviewRepository reviewRepository;
    private final IsolatedInsert isolatedInsert;

    public ReviewService(ReviewRepository reviewRepository, IsolatedInsert isolatedInsert) {
        this.reviewRepository = reviewRepository;
        this.isolatedInsert = isolatedInsert;
    }

    /**
     * 添加或更新评论.
     *
     * 与 {@link TrackService#saveTracking} 同一套写法, 理由也一样:
     * review 表上有 (user_id, subject_id) 唯一约束, 并发下的落败方重查一次改成更新,
     * 而不是把 500 抛给用户. 插入同样要套 {@link IsolatedInsert}, 否则 Agent 工具
     * 那条路上的外层事务会被冲突毒化, 补救全部作废.
     */
    public Review saveReview(User user, ReviewRequest req) {
        Optional<Review> existing = reviewRepository.findByUserAndSubjectId(user, req.getSubjectId());
        if (existing.isPresent()) {
            return applyAndSave(existing.get(), req);
        }
        try {
            return isolatedInsert.attempt(() -> applyAndSave(Review.builder()
                    .user(user)
                    .subjectId(req.getSubjectId())
                    .build(), req));
        } catch (DataIntegrityViolationException e) {
            Review winner = reviewRepository.findByUserAndSubjectId(user, req.getSubjectId())
                    .orElseThrow(() -> e);
            return applyAndSave(winner, req);
        }
    }

    /** 落字段并立刻 flush, 让约束冲突必定在 try 块内抛出. 理由同 TrackService. */
    private Review applyAndSave(Review review, ReviewRequest req) {
        review.setRating(req.getRating());
        review.setContent(req.getContent());
        return reviewRepository.saveAndFlush(review);
    }

    /** 删除评论 */
    public void deleteReview(User user, Long reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> BusinessException.notFound("评论不存在"));
        if (!review.getUser().getId().equals(user.getId())) {
            throw BusinessException.forbidden("无权删除他人评论");
        }
        reviewRepository.delete(review);
    }

    /**
     * 获取某番剧的评论(分页, 含作者名与头像).
     *
     * <p><b>为什么是 service 夹一道而不是只靠控制器上的注解</b>
     *
     * <p>控制器那层的 @Min/@Max 只挡得住网页请求. 这个 service 方法以后可能被
     * Agent 工具、定时任务或内部互调走到, 那些路径不经过参数绑定, 注解一次都不会跑.
     * 越界的页码在这里退化成第 1 页, 越界的条数退化成默认页大小 —— 都不抛异常,
     * 因为「调用方写错了页码」不该表现成 500.
     *
     * <p>limit 超过上限时**夹到上限**而不是报错, 理由同上: 这是只读列表, 少给几条
     * 远好过整个请求失败. 上界存在的意义是别让一个请求换走任意大的结果集.
     */
    public List<Map<String, Object>> getSubjectReviews(Long userId, Integer subjectId,
                                                       int page, int limit) {
        List<Review> reviews = reviewRepository.findPageBySubjectIdWithUser(
                subjectId, pageOf(page, limit));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Review r : reviews) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.getId());
            map.put("userId", r.getUser().getId());
            map.put("username", r.getUser().getUsername());
            map.put("avatar", r.getUser().getAvatar());
            map.put("rating", r.getRating());
            map.put("content", r.getContent());
            map.put("createdAt", r.getCreatedAt());
            map.put("isOwner", r.getUser().getId().equals(userId));
            result.add(map);
        }
        return result;
    }

    /**
     * 获取番剧评分统计 —— 均分、条数、十档分布全部由数据库聚合出来.
     *
     * <p><b>改前是什么写法、为什么是坑</b>
     *
     * <p>原来是 findBySubjectIdOrderByCreatedAtDesc(subjectId), 把该番的**每一条评论**
     * 都读成实体, 再在内存里 stream 求平均、size 求条数、循环数出十档分布. 这三个数字
     * 各是一句 COUNT/AVG 就能回答的事, 却要先把评论正文、时间、作者外键整行搬进 JVM ——
     * 而这是详情页的热路径, 每打开一次走一遍, 代价随该番的评论数线性变差.
     * 那句 ORDER BY created_at 更是白排的: 统计与顺序无关, 数据库却得先给结果排序.
     *
     * <p><b>为什么是一条 GROUP BY, 而不是 AVG + COUNT + GROUP BY 三条</b>
     *
     * <p>GROUP BY rating 的每一行是「分数 → 条数」. 把这些行按分数加权相加就是均值的分子,
     * 条数相加就是总数, 而这些行本身就是直方图. 一条查询同时回答三个问题, 结果最多十行,
     * 与评论条数无关; 拆成三条只是把同样的扫描做三遍.
     *
     * <p>响应结构与改前逐字一致(average / count / distribution 三个键, distribution 仍是
     * 下标 0~9 对应 1~10 分的 int[10]) —— 前端 AnimeDetail.vue 的柱子标签是 `i + 1`.
     */
    public Map<String, Object> getRatingStats(Integer subjectId) {
        long count = 0;
        long sum = 0;
        int[] distribution = new int[10];

        for (Object[] row : reviewRepository.countByRating(subjectId)) {
            int rating = ((Number) row[0]).intValue();
            long n = ((Number) row[1]).longValue();
            count += n;
            sum += (long) rating * n;
            // 十档只放得下 1~10 分. ReviewRequest.rating 上有 @Min(1)/@Max(10), 但注解
            // 只挡得住走接口的请求 —— 手工改库、历史遗留行、将来放宽校验都绕得过去,
            // 而 review 表上没有 CHECK 约束. 改前的 distribution[rating - 1] 碰上这种值
            // 就是数组越界, 整个统计接口 500、详情页跟着打不开.
            // 现在把它挡在直方图之外, 但仍然计进 count 与 average: 少一根画不出来的柱子,
            // 好过整页崩掉, 而且均值不该因为一根柱子放不下就跟着失真.
            if (rating >= 1 && rating <= 10) {
                distribution[rating - 1] += (int) n;
            }
        }

        double avg = count == 0 ? 0.0 : (double) sum / count;

        Map<String, Object> stats = new HashMap<>();
        stats.put("average", Math.round(avg * 10.0) / 10.0);
        stats.put("count", count);
        stats.put("distribution", distribution);
        return stats;
    }

    /** 获取用户自己的评论 */
    public Map<String, Object> getUserReview(User user, Integer subjectId) {
        Optional<Review> review = reviewRepository.findByUserAndSubjectId(user, subjectId);
        Map<String, Object> result = new HashMap<>();
        result.put("exists", review.isPresent());
        review.ifPresent(r -> {
            result.put("id", r.getId());
            result.put("rating", r.getRating());
            result.put("content", r.getContent());
            result.put("createdAt", r.getCreatedAt());
        });
        return result;
    }

    /**
     * 把外部传进来的分页参数夹成合法的 Pageable.
     *
     * <p>limit 小于 1 时退化成默认页大小而不是 1 条: 调用方传 0 通常是"没填",
     * 回一条评论对他没有任何用处, 而截断成 1 条又很难从响应里看出来.
     */
    private static Pageable pageOf(int page, int limit) {
        int safePage = Math.max(page, 1);
        int safeLimit = limit < 1 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        return PageRequest.of(safePage - 1, safeLimit);
    }
}
