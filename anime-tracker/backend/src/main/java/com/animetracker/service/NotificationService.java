package com.animetracker.service;

import com.animetracker.entity.Notification;
import com.animetracker.entity.Review;
import com.animetracker.entity.ReviewReply;
import com.animetracker.entity.User;
import com.animetracker.repository.NotificationRepository;
import com.animetracker.repository.ReviewReplyRepository;
import com.animetracker.repository.ReviewRepository;
import com.animetracker.repository.UserRepository;
import com.animetracker.util.PageResults;
import com.animetracker.util.TextSnippet;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内通知: 三个写入点 + 列表/未读/标记已读三条读法。
 *
 * <p><b>类上、以及 {@code onReply} / {@code onReviewLike} / {@code onReplyLike} 上都不带
 * {@code @Transactional}, 这是这个类最要紧的一条约束, 不是漏写。</b> 那三个方法必须跑在
 * **调用方**的事务里 —— 调用点都在 {@code ReviewReplyService} / {@code ReviewLikeService}
 * 的 {@link IsolatedInsert#attempt} 内部, 与「插入行 + 增减计数器」同一个事务。
 * 给它们加一个 {@code REQUIRES_NEW} 就是把通知拆成第三个事务, 于是"赞记上了、通知没写"
 * 会静默发生(点赞那条路还额外连着"到底有没有真插入"这个判断, 见 {@link #onReviewLike}),
 * 而这类半截状态没有任何报错。{@code REQUIRED} 看着无害, 但它让这一层自己也能成为
 * 事务的起点 —— 调用方哪天在事务外调用, 就悄悄多出一个事务, 而"通知必须跟着它描述的
 * 那件事一起提交"这个约束就没有东西守着了。
 *
 * <p>读侧同理不该有事务: {@code markAllRead} 那条批量 UPDATE 的事务由仓储方法上的
 * {@code @Transactional} 提供, 不需要这一层再包。
 *
 * <p><b>为什么收件人由调用方算好传进来, 而不是在这里从 review 反查。</b> 三个写入点
 * 手上都已经有那个对象(评论或回复), 作者就在它的 {@code user} 关联上; 在这里再查一次
 * 等于每次互动多一条 SELECT, 只为把一件调用方已经知道的事问第二遍。
 */
@Service
public class NotificationService {

    /** 列表默认一页几条 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 上限。50 与 {@code ReviewController} 的评论列表同量级 —— 两者都是"用户自己
     * 看的一屏列表", 而管理端那张全站表用的是 100。
     */
    public static final int MAX_PAGE_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewReplyRepository reviewReplyRepository;

    public NotificationService(NotificationRepository notificationRepository,
                               UserRepository userRepository,
                               ReviewRepository reviewRepository,
                               ReviewReplyRepository reviewReplyRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.reviewRepository = reviewRepository;
        this.reviewReplyRepository = reviewReplyRepository;
    }

    // ==================== 写(必须在调用方的事务里) ====================

    /**
     * 有人回复了某条短评。调用点: {@code ReviewReplyService.addReply} 的
     * {@code attempt} 内, 插入回复行之后。
     *
     * <p>只在**真的插入了**那一次调用它: 那条路径没有"已存在"的分支(回复可以重复发),
     * 所以它天然满足, 不需要额外判断。
     *
     * @param actor         发回复的人
     * @param recipientId   被回复的那条短评的作者
     * @param reviewId      被回复的短评
     * @param replyId       刚插入的那条回复
     */
    public void onReply(User actor, Long recipientId, Long reviewId, Long replyId) {
        record(Notification.REPLY, actor, recipientId, reviewId, replyId);
    }

    /**
     * 有人赞了某条短评。调用点: {@code ReviewLikeService.like} 的 {@code attempt} 内,
     * {@code saveAndFlush} 之后。
     *
     * <p><b>位置就是这条的全部:</b> 点赞是幂等的, 「已经赞过」会撞唯一约束并走 catch
     * 分支 —— 写在这里意味着只有真的插入成功那一次才会通知, 重复点一百下也只有一条。
     * 挪到 {@code attempt} 外(或者挪到 catch 之后)都会让重复点赞刷出一串通知。
     */
    public void onReviewLike(User actor, Long recipientId, Long reviewId) {
        record(Notification.REVIEW_LIKE, actor, recipientId, reviewId, null);
    }

    /**
     * 有人赞了某条回复。调用点: {@code ReviewReplyService.likeReply} 的 {@code attempt}
     * 内, {@code saveAndFlush} 之后。幂等那半的理由同 {@link #onReviewLike}。
     *
     * @param recipientId 被赞的那条**回复**的作者(不是评论的作者 —— 赞的是他的话)
     */
    public void onReplyLike(User actor, Long recipientId, Long reviewId, Long replyId) {
        record(Notification.REPLY_LIKE, actor, recipientId, reviewId, replyId);
    }

    /**
     * 三种通知共同的写入。三处只有 {@code type} 与"给不给 replyId"不同, 其余(自赞不通知、
     * 只写外键、时间戳由实体写)逐字相同 —— 所以它们只能有一份实现。
     *
     * <p><b>自己对自己做的事不通知</b>, 三条路都适用: 给自己的评论点赞、回复自己的评论、
     * 赞自己的回复都是合法的操作, 而给自己发一条"你赞了你自己"的通知只是噪音 ——
     * 更糟的是那个红点永远点不掉(它只会被下一次查看清掉, 而下次自赞又亮起来)。
     *
     * <p>只写外键、不把 User/Review/Reply 读出来: 三个 id 调用方都已经确认过存在
     * (那条评论/回复是刚刚被读过或刚插入的), {@code getReferenceById} 拿到的是代理,
     * 不产生查询 —— 与 {@code ReviewLikeService.like} 里那句同一个写法。
     */
    private void record(String type, User actor, Long recipientId, Long reviewId, Long replyId) {
        if (recipientId == null || recipientId.equals(actor.getId())) {
            return;
        }
        notificationRepository.save(Notification.builder()
                .recipient(userRepository.getReferenceById(recipientId))
                .actor(actor)
                .type(type)
                .review(reviewRepository.getReferenceById(reviewId))
                .reply(replyId == null ? null : reviewReplyRepository.getReferenceById(replyId))
                .build());
    }

    // ==================== 读 ====================

    /**
     * 通知列表: 最新的在最上面, 分页。
     *
     * <p>五步与 {@code AdminService.getUserPage} / {@code getActionPage} 逐条相同, 包括
     * 那个 {@code (long)} 转换与越界时"报真实 total 的空页": 前者防 {@code page} 接近
     * {@code Integer.MAX_VALUE} 时 int 乘法溢出成负数(库收到的是"从负数开始取一页",
     * 两个库的表现既不统一也不报错), 后者防翻页控件凭空少几页。
     *
     * <p>{@code limit} 在这里再夹一次, 不假设自己只被 controller 调用 —— 那条
     * {@code @Max(50)} 是给接口用的(400), 这一层是兜底。
     */
    public Map<String, Object> getNotificationPage(User user, int page, int limit) {
        int safePage = Math.max(page, 1);
        int safeLimit = limit < 1 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);

        long matched = notificationRepository.countPage(user.getId());
        int total = (int) Math.min(matched, Integer.MAX_VALUE);

        long offset = (long) (safePage - 1) * safeLimit;
        if (offset >= total || offset > PageResults.MAX_SQL_OFFSET) {
            return PageResults.of(Collections.emptyList(), total, safePage);
        }

        Pageable pageable = PageRequest.of(safePage - 1, safeLimit);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Notification n : notificationRepository.findPage(user.getId(), pageable)) {
            rows.add(toRow(n));
        }
        return PageResults.of(rows, total, safePage);
    }

    /**
     * 未读条数。导航栏的红点每次进页面都会问一次, 所以它是一条只回一个数字的 COUNT。
     */
    public long getUnreadCount(User user) {
        return notificationRepository.countUnread(user.getId());
    }

    /**
     * 把当前用户的未读全部标为已读。**幂等**: 重复调用改 0 行, 第一次那个已读时间不会被
     * 覆盖(守卫在仓储语句的 {@code AND n.readAt IS NULL} 上)。
     *
     * <p>刻意不做"单条已读": 用户的心智是"打开看一眼就都算看过了", 而逐条已读要配一套
     * 逐条交互(每条一个按钮 / 滚动到哪算哪), 那套东西才是驱动点击的东西 —— 没有红点的
     * 时候, 没人会去点。
     *
     * @return 这次真的改了几行。没人显示它, 但它是这条路径唯一能被断言的东西
     */
    public int markAllRead(User user) {
        return notificationRepository.markAllRead(user.getId(), LocalDateTime.now());
    }

    /**
     * 一行通知的对外形状。
     *
     * <p>{@code reviewContent} 与 {@code replyContent} 都给(各自可能为 null), 而不是合成
     * 一个"摘要"字段: 前端按类型取用 —— 回复类通知要显示"对方说了什么"(回复正文),
     * 而三种都要显示"这是哪条评论"(评论正文)。合成一个字段就得在服务端按类型改写它的
     * 含义, 而那样同一个键在三种类型下指的是三样东西。
     *
     * <p>两个摘要都走 {@link TextSnippet}(60 字): 评论正文最长 5000 字, 一页 20 行原样
     * 带出去就是几十 KB 的响应。评论只打分不写字时它是 {@code null} 而不是空串, 前端据此
     * 显示「（无文字）」—— 这是"谁回复了我"那一版就定下的口径, 原样保留。
     */
    private Map<String, Object> toRow(Notification n) {
        Review review = n.getReview();
        ReviewReply reply = n.getReply();

        Map<String, Object> row = new HashMap<>();
        row.put("id", n.getId());
        row.put("type", n.getType());
        row.put("actorName", n.getActor().getUsername());
        row.put("actorAvatar", n.getActor().getAvatar());
        // review 为空是"这一行坏了"(三种类型都写 review_id, 且删评论时它会被级联带走),
        // 而 reply 为空是**正常的** —— 赞评论那条本来就没有回复。所以这里只兜住前者,
        // 免得手上被人塞进一行脏数据就把整页变成 500。
        row.put("reviewId", review == null ? null : review.getId());
        row.put("subjectId", review == null ? null : review.getSubjectId());
        row.put("reviewContent", review == null ? null : TextSnippet.of(review.getContent()));
        row.put("replyId", reply == null ? null : reply.getId());
        row.put("replyContent", reply == null ? null : TextSnippet.of(reply.getContent()));
        row.put("createdAt", n.getCreatedAt());
        row.put("read", n.getReadAt() != null);
        return row;
    }
}
