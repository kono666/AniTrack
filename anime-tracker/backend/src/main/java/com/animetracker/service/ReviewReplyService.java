package com.animetracker.service;

import com.animetracker.entity.ReplyLike;
import com.animetracker.entity.Review;
import com.animetracker.entity.ReviewReply;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.ReplyLikeRepository;
import com.animetracker.repository.ReviewReplyRepository;
import com.animetracker.repository.ReviewRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 短评下的回复: 发、改、删, 以及回复的赞.
 *
 * <p><b>类上不带 {@code @Transactional}</b>, 与 {@link ReviewService} /
 * {@link ReviewLikeService} / {@code TrackService} 是同一条规矩, 不是漏写: 需要独立
 * 事务的几段包在 {@link IsolatedInsert} 里, 而那些 catch 必须待在**没有环境事务**的
 * 这一层, 否则那份事务会被冲突标记成 rollback-only, 捕获了也提交不了(完整说明见
 * {@link IsolatedInsert})。
 *
 * <p><b>为什么新开一个类而不并进 {@link ReviewService}</b>: 两者的写路径形状不同 ——
 * 回复的增删是"两个写必须在同一个事务里"(与 {@link ReviewLikeService} 同形), 而评论
 * 是"插入撞唯一约束后重查再改写". 放在一个类里, 下一个人给评论加一条路径时很容易把
 * 回复的写法抄过去。
 */
@Service
public class ReviewReplyService {

    /**
     * 「谁回复了我」最多列几条.
     *
     * <p>这是一个没有分页控件的列表区块, 与 {@link ReviewLikeService#MAX_LIKERS_SHOWN}
     * 是同一种东西, 所以也封顶而不是分页。30 比 50 小, 因为这里每一项是一行带正文的
     * 记录(而名单里只是一串名字), 铺满一屏之外没有意义。
     */
    public static final int MAX_RECEIVED_SHOWN = 30;

    /** 一条短评下最多取多少条回复. 与评论列表一样是"不封顶地一次倒出"必须堵上的那个洞。 */
    public static final int MAX_REPLIES_PER_REVIEW = 200;

    private final ReviewRepository reviewRepository;
    private final ReviewReplyRepository reviewReplyRepository;
    private final ReplyLikeRepository replyLikeRepository;
    private final IsolatedInsert isolatedInsert;

    public ReviewReplyService(ReviewRepository reviewRepository,
                              ReviewReplyRepository reviewReplyRepository,
                              ReplyLikeRepository replyLikeRepository,
                              IsolatedInsert isolatedInsert) {
        this.reviewRepository = reviewRepository;
        this.reviewReplyRepository = reviewReplyRepository;
        this.replyLikeRepository = replyLikeRepository;
        this.isolatedInsert = isolatedInsert;
    }

    /** {@code ORDER BY} 里那个 {@code :epoch} 的值 —— 见仓储方法上的说明, 取什么都不影响结果 */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    // ==================== 读 ====================

    /**
     * 一条短评下的回复, 按时间正序(先发生的在前). 未登录也可看, 所以这里收的是 userId
     * 而不是 User.
     *
     * <p>{@code likedByMe} 用**一条批量查询**取回后在内存里合并, 不是每条问一次 ——
     * 20 条回复 20 次往返, 而这 20 个答案就在同一张表里. 匿名({@code ANONYMOUS_USER_ID})
     * 或这一页为空时那条查询**整条不发**: 后者是必须的, 空集合进 JPQL 的 {@code IN}
     * 没有合法写法(见 {@code AnimeQueries} 里那个哨兵参数)。
     */
    public List<Map<String, Object>> getReplies(Long userId, Long reviewId) {
        if (!reviewRepository.existsById(reviewId)) {
            throw BusinessException.notFound("评论不存在");
        }
        List<ReviewReply> replies = reviewReplyRepository.findReplies(
                reviewId, EPOCH, PageRequest.of(0, MAX_REPLIES_PER_REVIEW));
        Set<Long> likedIds = likedReplyIds(userId, replies);

        List<Map<String, Object>> result = new ArrayList<>();
        for (ReviewReply rr : replies) {
            result.add(replyMap(rr, userId, likedIds.contains(rr.getId())));
        }
        return result;
    }

    /**
     * 「谁回复了我」: 我写的短评下面、别人发的回复.
     *
     * <p>这一版**不做已读、不做红点**(用户明确选的最简版): 没有"上次看到哪"这种状态,
     * 也就没有要存的东西 —— 整条路径是只读的。
     *
     * <p>每一项都带上 {@code subjectId} 与评论正文的摘要: 列表里要回答"这是哪条番剧下
     * 我写的哪条评论", 光有回复正文的话用户不知道说的是什么。
     */
    public Map<String, Object> getReceivedReplies(User user) {
        List<ReviewReply> replies = reviewReplyRepository.findReceivedReplies(
                user.getId(), EPOCH, PageRequest.of(0, MAX_RECEIVED_SHOWN));

        List<Map<String, Object>> list = new ArrayList<>();
        for (ReviewReply rr : replies) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", rr.getId());
            row.put("reviewId", rr.getReview().getId());
            row.put("subjectId", rr.getReview().getSubjectId());
            // 评论正文只给**摘要**: 这一行要回答的是"你回的是哪条", 不是把那条评论原文
            // 再贴一遍 —— 评论正文最长 5000 字, 30 行原样带出去就是一个几十 KB 的响应.
            row.put("reviewContent", snippet(rr.getReview().getContent()));
            row.put("userId", rr.getUser().getId());
            row.put("username", rr.getUser().getUsername());
            row.put("avatar", rr.getUser().getAvatar());
            row.put("content", rr.getContent());
            row.put("createdAt", rr.getCreatedAt());
            list.add(row);
        }

        Map<String, Object> result = new HashMap<>();
        // 没有 total: 这一块不分页, 而"一共多少条"要另发一条 COUNT —— 用户明确选的是
        // 最简版, 封顶 30 条就够用, 多出来的那个数字没有任何地方会显示.
        result.put("list", list);
        return result;
    }

    // ==================== 写 ====================

    /**
     * 发一条回复. 插入回复行与给 {@code review.reply_count} 加一在**同一个事务**里 ——
     * 分开就是两个事务, 于是"回复写进去了、数没涨"和反过来的半截状态都能发生,
     * 两种都不报错, 只让计数越漂越远(与 {@link ReviewLikeService#like} 同一条理由)。
     */
    public Map<String, Object> addReply(User user, Long reviewId, String content) {
        if (!reviewRepository.existsById(reviewId)) {
            throw BusinessException.notFound("评论不存在");
        }
        Long replyId = isolatedInsert.attempt(() -> {
            ReviewReply saved = reviewReplyRepository.saveAndFlush(ReviewReply.builder()
                    // 只写外键, 不把 Review 读出来: 存在性上面已经确认过
                    .review(reviewRepository.getReferenceById(reviewId))
                    .user(user)
                    .content(content)
                    .build());
            reviewRepository.incrementReplyCount(reviewId);
            return saved.getId();
        });
        // 读回来再回给前端, 而不是拿 isolatedInsert 里那个实体: 那个事务结束后它已经
        // 脱离持久化上下文了(见 IsolatedInsert 的说明), 碰它的懒加载关联会炸.
        return replyMap(load(replyId), user.getId(), false);
    }

    /**
     * 改一条回复的正文. 只有作者能改。
     *
     * <p>{@code updatedAt} 由实体的 {@code @PreUpdate} 写(不是这里手写), 前端据此显示
     * 「已编辑」—— 判据是 {@code updatedAt > createdAt}。两个时间戳在插入时是同一次
     * {@code now()}, 所以刚发的回复不会被标成已编辑。
     */
    public Map<String, Object> editReply(User user, Long replyId, String content) {
        ReviewReply reply = load(replyId);
        if (!reply.getUser().getId().equals(user.getId())) {
            throw BusinessException.forbidden("只能编辑自己的回复");
        }
        // 不放在 isolatedInsert 里: 这一条不撞唯一约束, 没有需要捕获的冲突, 也就没有
        // 需要隔离的东西 —— 用它是为了让 catch 安全, 而这里没有 catch.
        reply.setContent(content);
        ReviewReply saved = reviewReplyRepository.saveAndFlush(reply);
        // likedByMe 要真的问一次, 不能顺手写 false: 前端拿这条响应整体替换那一行,
        // 而「我自己赞过自己的回复」是完全合法的状态 —— 写死 false 会把心形点灭.
        return replyMap(saved, user.getId(),
                replyLikeRepository.existsByReplyIdAndUserId(replyId, user.getId()));
    }

    /**
     * 删一条回复. 权限是**三选一**: 回复作者 ∪ 评论作者 ∪ 管理员。
     *
     * <p>「评论作者也能删自己楼里的回复」是拍板的决定, 理由是那一栏毕竟是他的地盘 ——
     * 没有这个权限, 一条辱骂回复只能等管理员来处理。
     *
     * <p>删回复行与给 {@code review.reply_count} 减一在同一个事务里; 这条回复的
     * {@code reply_like} 由库级的 {@code ON DELETE CASCADE}(V8)**自己**跟着走,
     * 不手写 —— 手写一遍就多一处会漏的地方, 而漏掉的表现是残留一堆指向已删回复的赞行。
     */
    public void deleteReply(User user, Long replyId) {
        ReviewReply reply = load(replyId);
        if (!canDelete(user, reply)) {
            throw BusinessException.forbidden("无权删除该回复");
        }
        Long reviewId = reply.getReview().getId();
        isolatedInsert.attempt(() -> {
            // deleteById 而不是 delete(实体): 后者要先 SELECT 一次再把托管实体删掉,
            // 这里那句 SELECT 已经在 load() 里发过了.
            reviewReplyRepository.deleteById(replyId);
            reviewRepository.decrementReplyCount(reviewId);
            return null;
        });
    }

    /** 删除权限判据 —— 单独一个方法, 因为它是三支, 塞进 deleteReply 里读不清 */
    private boolean canDelete(User user, ReviewReply reply) {
        return reply.getUser().getId().equals(user.getId())                 // 回复作者
                || reply.getReview().getUser().getId().equals(user.getId()) // 评论作者
                || "ADMIN".equals(user.getRole());                          // 管理员
    }

    // ==================== 回复的赞 ====================

    /** 赞一条回复. 幂等, 形状与理由与 {@link ReviewLikeService#like} 逐条相同。 */
    public Map<String, Object> likeReply(User user, Long replyId) {
        if (!reviewReplyRepository.existsById(replyId)) {
            throw BusinessException.notFound("回复不存在");
        }
        try {
            isolatedInsert.attempt(() -> {
                replyLikeRepository.saveAndFlush(ReplyLike.builder()
                        .reply(reviewReplyRepository.getReferenceById(replyId))
                        .user(user)
                        .build());
                reviewReplyRepository.incrementLikeCount(replyId);
                return null;
            });
        } catch (DataIntegrityViolationException e) {
            // 同 ReviewLikeService: 唯一约束 = 已经赞过(幂等), 外键 = 这条回复刚被删了(404)
            if (!reviewReplyRepository.existsById(replyId)) {
                throw BusinessException.notFound("回复不存在");
            }
        }
        return likeResult(replyId, true);
    }

    /** 取消回复的赞. 幂等; 只在**真的删掉了一行**时才减计数。 */
    public Map<String, Object> unlikeReply(User user, Long replyId) {
        if (!reviewReplyRepository.existsById(replyId)) {
            throw BusinessException.notFound("回复不存在");
        }
        isolatedInsert.attempt(() -> {
            int deleted = replyLikeRepository.deleteByReplyIdAndUserId(replyId, user.getId());
            if (deleted == 1) {
                reviewReplyRepository.decrementLikeCount(replyId);
            }
            return null;
        });
        return likeResult(replyId, false);
    }

    /** 谁赞了这条回复. 未登录也可看(接口公开), 所以不接 User。 */
    public Map<String, Object> getReplyLikers(Long replyId) {
        ReviewReply reply = reviewReplyRepository.findById(replyId)
                .orElseThrow(() -> BusinessException.notFound("回复不存在"));

        List<Map<String, Object>> list = new ArrayList<>();
        for (ReplyLike rl : replyLikeRepository.findLikers(
                replyId, PageRequest.of(0, ReviewLikeService.MAX_LIKERS_SHOWN))) {
            Map<String, Object> row = new HashMap<>();
            row.put("userId", rl.getUser().getId());
            row.put("username", rl.getUser().getUsername());
            list.add(row);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", reply.getLikeCount());
        result.put("list", list);
        return result;
    }

    // ==================== 私有 ====================

    /**
     * 「谁回复了我」里那条评论的摘要, 最长 {@value #SNIPPET_LENGTH} 个字符.
     *
     * <p>{@code null} 与空白都回成 {@code null} 而不是空串: 评论是可以没有正文的
     * (只打分不写字), 前端据此显示「（无文字）」, 空串会让那个判断变成"有内容但看不见".
     */
    private static String snippet(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        return content.length() <= SNIPPET_LENGTH
                ? content
                : content.substring(0, SNIPPET_LENGTH) + "…";
    }

    /** 摘要长度. 一屏列表里读的是"这是哪条", 一行放得下就够了. */
    private static final int SNIPPET_LENGTH = 60;

    /** 按 id 取一条带作者的回复, 没有就 404 */
    private ReviewReply load(Long replyId) {
        return reviewReplyRepository.findByIdWithUser(replyId)
                .orElseThrow(() -> BusinessException.notFound("回复不存在"));
    }

    private Set<Long> likedReplyIds(Long userId, List<ReviewReply> replies) {
        if (userId == null || userId == ReviewService.ANONYMOUS_USER_ID || replies.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = new ArrayList<>(replies.size());
        for (ReviewReply rr : replies) {
            ids.add(rr.getId());
        }
        return new HashSet<>(replyLikeRepository.findLikedReplyIds(userId, ids));
    }

    /** 回复的对外形状. 三处调用(列表/发布/编辑)共用, 免得字段在某一处少一个。 */
    private Map<String, Object> replyMap(ReviewReply rr, Long userId, boolean likedByMe) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", rr.getId());
        row.put("userId", rr.getUser().getId());
        row.put("username", rr.getUser().getUsername());
        row.put("avatar", rr.getUser().getAvatar());
        row.put("content", rr.getContent());
        row.put("createdAt", rr.getCreatedAt());
        row.put("updatedAt", rr.getUpdatedAt());
        // 「已编辑」由前端比较两个时间戳得出, 服务端不塞一个布尔 —— 那个布尔是派生值,
        // 两处算同一个东西时总有一处会忘(比如编辑后忘了把它翻成 true).
        row.put("isOwner", rr.getUser().getId().equals(userId));
        row.put("likeCount", rr.getLikeCount());
        row.put("likedByMe", likedByMe);
        return row;
    }

    /** 赞完之后回 {@code {liked, likeCount}} —— 新计数由服务端给, 不让前端自己猜 */
    private Map<String, Object> likeResult(Long replyId, boolean liked) {
        Long count = reviewReplyRepository.readLikeCount(replyId);
        Map<String, Object> result = new HashMap<>();
        result.put("liked", liked);
        result.put("likeCount", count == null ? 0L : count);
        return result;
    }

}
