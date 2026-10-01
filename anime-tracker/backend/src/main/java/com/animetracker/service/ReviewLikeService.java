package com.animetracker.service;

import com.animetracker.entity.Review;
import com.animetracker.entity.ReviewLike;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.ReviewLikeRepository;
import com.animetracker.repository.ReviewRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 短评的点赞.
 *
 * <p><b>为什么不并进 {@link ReviewService}</b>: 那是评论的读写, 这里是互动, 两者的
 * 事务要求不一样 —— 点赞要在同一个事务里改两张表(见下面 {@link #like}), 而评论的
 * 写路径是 {@code IsolatedInsert} + 冲突重查那一套。放在一个类里, 下一个人给评论加
 * 一条路径时很容易顺手把点赞的写法抄过去。
 *
 * <p><b>类上不带 {@code @Transactional}</b>, 与 {@link ReviewService} /
 * {@link TrackService} / {@code AdminService} 是同一条规矩, 不是漏写: 需要独立事务的
 * 那两段包在 {@link IsolatedInsert} 里, 冲突的 catch 必须待在**没有环境事务**的这一层,
 * 否则那份事务会被冲突标记成 rollback-only, 捕获了也提交不了(完整说明见
 * {@link IsolatedInsert})。
 */
@Service
public class ReviewLikeService {

    /**
     * 「谁赞了」最多列出几个名字.
     *
     * <p>封顶而不是分页: 这一块是列表项里内联展开的一行字, 不是一个可以翻的列表;
     * 而赞数本身由 {@code likeCount} 给(它才是显示"共 N 人赞过"的值)。
     * 50 这个数与 {@code ReviewService.MAX_PAGE_SIZE} 同量级, 不再引一个新数字。
     */
    public static final int MAX_LIKERS_SHOWN = 50;

    private final ReviewRepository reviewRepository;
    private final ReviewLikeRepository reviewLikeRepository;
    private final IsolatedInsert isolatedInsert;
    private final NotificationService notificationService;

    public ReviewLikeService(ReviewRepository reviewRepository,
                             ReviewLikeRepository reviewLikeRepository,
                             IsolatedInsert isolatedInsert,
                             NotificationService notificationService) {
        this.reviewRepository = reviewRepository;
        this.reviewLikeRepository = reviewLikeRepository;
        this.isolatedInsert = isolatedInsert;
        this.notificationService = notificationService;
    }

    /**
     * 点赞. <b>幂等</b>: 已经赞过再点一次, 返回 200 且 {@code liked=true}, 计数不变。
     *
     * <p>幂等不是"顺手加的礼貌", 是这条路必须有的性质: 前端一次点击可能因为网络重发
     * 变成两次请求, 而"赞"这个动作重复执行一次与执行一次的结果必须相同 —— 否则用户会
     * 看到自己的赞莫名消失(这正是本项目刻意不做单个 toggle 端点的理由)。
     *
     * <p><b>两个写必须在同一个事务里</b>(因此都在同一个 {@link IsolatedInsert} 里):
     * 点赞行与 {@code review.like_count} 是同一件事的两半。分开两次调用就是两个事务,
     * 于是"行写进去了、计数没涨"和"计数涨了、行没写进去"都能发生 —— 两种都不会报错,
     * 只会让计数越漂越远。放在一起则由数据库保证要么都成、要么都不成。
     *
     * <p>冲突(唯一约束)是**正常路径**, 不是错误: 并发点两次时后到的那次必然撞约束。
     * 靠 catch 而不是"先查再插" —— 先查再插在并发下照样会撞, 只是把冲突挪到了提交时。
     * 而 catch 只能在这一层做, 见类注释。
     */
    public Map<String, Object> like(User user, Long reviewId) {
        // 读出来(带作者)而不是 existsById: 通知要送到**评论作者**手上, 而那是这条实体
        // 上的一个字段。代价是这一条 SELECT 从"只判在不在"变成"取回整行", 换掉的是下面
        // 那句 getReferenceById —— 一次查询换一次查询, 净支出为零。
        // (为什么用 findByIdWithUser 而不是 findById: 见那个方法上的注释。)
        Review review = reviewRepository.findByIdWithUser(reviewId)
                .orElseThrow(() -> BusinessException.notFound("评论不存在"));
        try {
            isolatedInsert.attempt(() -> {
                reviewLikeRepository.saveAndFlush(ReviewLike.builder()
                        .review(review)
                        .user(user)
                        .build());
                reviewRepository.incrementLikeCount(reviewId);
                // 在 attempt 之内、saveAndFlush 之后: 于是只有**真的插入成功**这一次才
                // 会写通知, 而"已经赞过"撞唯一约束的那条幂等路径会直接跳到下面的 catch,
                // 不会重复刷通知(见 NotificationService.onReviewLike)。
                notificationService.onReviewLike(user, review.getUser().getId(), reviewId);
                return null;
            });
        } catch (DataIntegrityViolationException e) {
            // 两种冲突都会落到这里, 得分清楚:
            //   · 唯一约束 uk_review_like_review_user —— 已经赞过, 这是幂等, 什么都不做;
            //   · 外键 fk_review_like_review —— 这条评论刚好在这两步之间被别人删了,
            //     那是真的没有了, 该报 404 而不是假装赞成功。
            // 只在冲突这条罕见路径上多花一条查询, 正常路径一次都不花。
            if (!reviewRepository.existsById(reviewId)) {
                throw BusinessException.notFound("评论不存在");
            }
        }
        return likeResult(reviewId, true);
    }

    /**
     * 取消点赞. 同样幂等: 没赞过再取消一次, 返回 200 且 {@code liked=false}。
     *
     * <p>{@code decrementLikeCount} 只在**真的删掉了一行**时才调用 —— 用派生删除或者
     * 不判断返回值都做不到这一点(前者还会先 SELECT 再 DELETE, 见
     * {@link ReviewLikeRepository#deleteByReviewIdAndUserId})。判断的意义是防偏差:
     * 没删掉却减一, 计数就永久少一个, 而点赞的人越多、重复取消越频繁, 偏得越远。
     */
    public Map<String, Object> unlike(User user, Long reviewId) {
        if (!reviewRepository.existsById(reviewId)) {
            throw BusinessException.notFound("评论不存在");
        }
        isolatedInsert.attempt(() -> {
            int deleted = reviewLikeRepository.deleteByReviewIdAndUserId(reviewId, user.getId());
            if (deleted == 1) {
                reviewRepository.decrementLikeCount(reviewId);
            }
            return null;
        });
        return likeResult(reviewId, false);
    }

    /**
     * 谁赞了这条短评. 未登录也可看(接口是公开的), 所以这里不接 User。
     *
     * <p>{@code total} 直接取 {@code review.likeCount}(冗余计数), 不另发一条 COUNT:
     * 它必须与列表里显示的那个数字**同源**, 否则同一个界面上的"共 12 人赞过"和展开后
     * 的 11 个名字会打架。代价是它与 {@code review_like} 的行数可能不一致 ——
     * 那属于计数漂移, 是另一条线的问题(对账用例守着), 不该在这里用第二个口径掩盖它。
     */
    public Map<String, Object> getLikers(Long reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> BusinessException.notFound("评论不存在"));

        List<Map<String, Object>> list = new ArrayList<>();
        for (ReviewLike rl : reviewLikeRepository.findLikers(
                reviewId, PageRequest.of(0, MAX_LIKERS_SHOWN))) {
            Map<String, Object> row = new HashMap<>();
            row.put("userId", rl.getUser().getId());
            row.put("username", rl.getUser().getUsername());
            list.add(row);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", review.getLikeCount());
        result.put("list", list);
        return result;
    }

    /**
     * 点完赞之后回给前端的当前状态: {@code {liked, likeCount}}。
     *
     * <p>把新计数一起回给前端, 是为了让按钮上的数字不依赖"本地 +1"那种猜测 ——
     * 幂等路径(已经赞过)与真实点赞在服务端的计数完全不同, 客户端自己算会算错。
     * 这也正是刻意不做单个 toggle 端点的原因: 客户端表达的是**目标状态**,
     * 服务端回的是**实际状态**, 两边对不上的时候用户看得出来。
     *
     * <p>{@code readLikeCount} 理论上可能回 null(评论在这两句之间被删掉), 那种情况
     * 回 {@code 0} 而不是把 null 塞进响应: 前端的计数显示是 {@code count} 直接渲染,
     * null 会显示成空白, 而"评论刚被删掉"本来就该走 404 / 刷新, 不该表现成渲染异常。
     */
    private Map<String, Object> likeResult(Long reviewId, boolean liked) {
        Long count = reviewRepository.readLikeCount(reviewId);
        Map<String, Object> result = new HashMap<>();
        result.put("liked", liked);
        result.put("likeCount", count == null ? 0L : count);
        return result;
    }
}
