package com.animetracker.service;

import com.animetracker.entity.Notification;
import com.animetracker.entity.ReplyLike;
import com.animetracker.entity.Review;
import com.animetracker.entity.ReviewLike;
import com.animetracker.entity.ReviewReply;
import com.animetracker.entity.User;
import com.animetracker.repository.NotificationRepository;
import com.animetracker.repository.ReplyLikeRepository;
import com.animetracker.repository.ReviewLikeRepository;
import com.animetracker.repository.ReviewReplyRepository;
import com.animetracker.repository.ReviewRepository;
import com.animetracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知的**写入侧**: 三个写入点各写出一条正确的通知, 且都写在别人那个事务里。
 *
 * <p>与前两个测试的分工: {@code NotificationWriteTest}(本类)管"什么时候写、写成什么"、
 * {@code NotificationReadTest} 管"读回来长什么样"、{@code controller/NotificationIntegrationTest}
 * 管端到端(真库、真 HTTP)。{@link ReviewLikeServiceTest} 里那个 {@code NotificationService}
 * 是替身 —— 那边盯的是"赞"这条路径自己的分支, 通知写没写、写在哪个事务里是这里的事。
 *
 * <p><b>为什么要驱动真的 {@code ReviewReplyService} / {@code ReviewLikeService}, 而不是
 * 直接调 {@code NotificationService} 的三个方法。</b> 因为这个类要守的东西有一半在**调用点**
 * 上, 不在被调方:
 *
 * <ul>
 *   <li>「自赞不通知」的判据是"评论/回复的作者是不是动手的人", 那个 id 只有调用点手上有;</li>
 *   <li>「重复点赞不产生第二条」靠的是"通知写在 {@code saveAndFlush} 之后、{@code attempt}
 *       之内"这个**位置** —— 幂等那半是仓储抛异常走 catch, 与通知无关, 但它决定了通知
 *       该不该写上;</li>
 *   <li>「与计数器同一个事务」更是纯粹的调用点性质。</li>
 * </ul>
 *
 * <p>三个写入点里两个在 {@code ReviewReplyService}, 一个是 {@code ReviewLikeRepository}
 * 那条路 —— 所以这个类同时驱动两个 service。它们用的 {@link IsolatedInsert} 是**真的**
 * (没有事务管理器时它就是一次直通调用), 只有它被换成一个记深度的替身, 见下。
 */
class NotificationWriteTest {

    /** 被回复 / 被赞的那位 —— 通知的收件人 */
    private static final Long AUTHOR_ID = 2L;
    /** 动手的那位 —— 通知的 actor */
    private static final Long ACTOR_ID = 1L;
    private static final Long REVIEW_ID = 7L;
    private static final Long REPLY_ID = 50L;

    private ReviewRepository reviewRepository;
    private ReviewReplyRepository reviewReplyRepository;
    private ReplyLikeRepository replyLikeRepository;
    private ReviewLikeRepository reviewLikeRepository;
    private NotificationRepository notificationRepository;
    private UserRepository userRepository;

    private DepthTrackingInsert isolatedInsert;
    private ReviewReplyService reviewReplyService;
    private ReviewLikeService reviewLikeService;

    /**
     * 真的 {@link IsolatedInsert}, 外加记下"通知那一行是在第几层 attempt 里写的"。
     *
     * <p>这是「通知与计数器同一个事务」唯一能自动化验的地方。拆成两个事务之后, 一切
     * 正常路径的结果完全相同 —— 通知照样写、计数照样涨, 差别只在中间失败时: 于是
     * "赞记上了、通知没写"变成可能, 而它不报错, 只是收件人永远不知道有人赞了他。
     * 所以这里不看终态, 直接盯**通知的 save 发生在 attempt 之内还是之外**(期望 1, 外面是 0)。
     *
     * <p>没有事务管理器时 {@code attempt} 就是 {@code insert.get()}, 所以这个替身除记账
     * 之外不改变任何行为, 上面那些用例仍然在测真的代码。
     */
    private static class DepthTrackingInsert extends IsolatedInsert {
        int calls;
        int depth;
        /** 通知行被保存那一刻的事务深度; -1 = 一次都没有保存过 */
        int depthWhenNotified = -1;

        @Override
        public <T> T attempt(Supplier<T> insert) {
            calls++;
            depth++;
            try {
                return insert.get();
            } finally {
                depth--;
            }
        }
    }

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ReviewRepository.class);
        reviewReplyRepository = mock(ReviewReplyRepository.class);
        replyLikeRepository = mock(ReplyLikeRepository.class);
        reviewLikeRepository = mock(ReviewLikeRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        userRepository = mock(UserRepository.class);
        isolatedInsert = new DepthTrackingInsert();

        // 真的 NotificationService: 这个类要验的正是"调用点交给它的东西对不对",
        // 换成替身就等于把待验的那一段挖掉了
        NotificationService notificationService = new NotificationService(
                notificationRepository, userRepository, reviewRepository, reviewReplyRepository);

        reviewReplyService = new ReviewReplyService(reviewRepository, reviewReplyRepository,
                replyLikeRepository, isolatedInsert, notificationService);
        reviewLikeService = new ReviewLikeService(reviewRepository, reviewLikeRepository,
                isolatedInsert, notificationService);

        /* 三个 getReferenceById 都回一个带 id 的空壳. NotificationService 刻意只写外键
           (不产生查询), 所以断言收件人时只能看这个壳上的 id —— 那也正是要验的东西。 */
        when(userRepository.getReferenceById(anyLong()))
                .thenAnswer(i -> User.builder().id(i.getArgument(0)).build());
        when(reviewRepository.getReferenceById(anyLong()))
                .thenAnswer(i -> Review.builder().id(i.getArgument(0)).build());
        when(reviewReplyRepository.getReferenceById(anyLong()))
                .thenAnswer(i -> ReviewReply.builder().id(i.getArgument(0)).build());

        // 通知行落库那一刻顺便记下深度
        when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> {
            isolatedInsert.depthWhenNotified = isolatedInsert.depth;
            return i.getArgument(0);
        });

        // 被回复/被赞的那条评论: 作者是 AUTHOR_ID
        when(reviewRepository.findByIdWithUser(REVIEW_ID)).thenReturn(Optional.of(review()));
        when(reviewRepository.readLikeCount(anyLong())).thenReturn(1L);
        /* 三条 existsById 是取消赞/取消回复的赞的前置判断("在架上吗"), 也是幂等路径的
           catch 里"这条东西还在不在"那一次复查 —— 默认的 false 会让它们全都变成 404。
           V14 之后评论那两个是**两个不同的方法**(一个带 DeletedAtIsNull 判在不在架上,
           一个只是问那一行还在不在), 所以两条桩都得给 */
        when(reviewRepository.existsById(anyLong())).thenReturn(true);
        when(reviewRepository.existsByIdAndDeletedAtIsNull(anyLong())).thenReturn(true);
        when(reviewReplyRepository.existsById(anyLong())).thenReturn(true);
    }

    private static User actor() {
        return User.builder().id(ACTOR_ID).username("alice").role("USER").status("ACTIVE").build();
    }

    private static Review review() {
        return review(AUTHOR_ID);
    }

    private static Review review(Long authorId) {
        return Review.builder().id(REVIEW_ID).subjectId(200)
                .user(User.builder().id(authorId).username("owner").build())
                .build();
    }

    private static ReviewReply replyWithAuthor(Long authorId) {
        return ReviewReply.builder().id(REPLY_ID).review(review()).content("说得好")
                .user(User.builder().id(authorId).username("owner").build())
                .build();
    }

    /** 记下唯一那条通知 */
    private Notification capturedNotification() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        return captor.getValue();
    }

    // ========== 三个写入点 ==========

    /**
     * 有人回复了我的评论 → 收件人是**评论作者**(不是被回复的人 —— 回复是扁平的,
     * 没有"回复的回复")。
     */
    @Test
    @DisplayName("发回复: 写一条 REPLY 通知给评论作者")
    void replyingNotifiesTheReviewAuthor() {
        // saveAndFlush 的返回值就是刚插进去那行, 它的 id 要写进通知
        when(reviewReplyRepository.saveAndFlush(any(ReviewReply.class))).thenAnswer(i -> {
            ReviewReply saved = i.getArgument(0);
            saved.setId(REPLY_ID);
            return saved;
        });
        // 写完回复还要读回来给前端
        when(reviewReplyRepository.findByIdWithUser(REPLY_ID))
                .thenReturn(Optional.of(replyWithAuthor(AUTHOR_ID)));

        reviewReplyService.addReply(actor(), REVIEW_ID, "同感");

        Notification n = capturedNotification();
        assertThat(n.getType()).isEqualTo(Notification.REPLY);
        assertThat(n.getRecipient().getId()).as("收件人是评论作者").isEqualTo(AUTHOR_ID);
        assertThat(n.getActor().getId()).isEqualTo(ACTOR_ID);
        assertThat(n.getReview().getId()).isEqualTo(REVIEW_ID);
        assertThat(n.getReply().getId()).as("回复类通知要带上那条回复").isEqualTo(REPLY_ID);
        verify(reviewRepository).incrementReplyCount(REVIEW_ID);
    }

    @Test
    @DisplayName("赞评论: 写一条 REVIEW_LIKE 通知给评论作者, 它没有回复 id")
    void likingAReviewNotifiesTheReviewAuthor() {
        reviewLikeService.like(actor(), REVIEW_ID);

        Notification n = capturedNotification();
        assertThat(n.getType()).isEqualTo(Notification.REVIEW_LIKE);
        assertThat(n.getRecipient().getId()).isEqualTo(AUTHOR_ID);
        assertThat(n.getReview().getId()).isEqualTo(REVIEW_ID);
        // 赞评论那条本来就没有回复 —— 写进去一个 reply_id 会让读路径去 fetch 一个空关联
        assertThat(n.getReply()).isNull();
        verify(reviewRepository).incrementLikeCount(REVIEW_ID);
    }

    /** 赞回复 → 收件人是**回复作者**, 不是评论作者: 赞的是他的话 */
    @Test
    @DisplayName("赞回复: 写一条 REPLY_LIKE 通知给回复作者, 并带上它所属的评论")
    void likingAReplyNotifiesTheReplyAuthor() {
        when(reviewReplyRepository.findByIdWithUser(REPLY_ID))
                .thenReturn(Optional.of(replyWithAuthor(AUTHOR_ID)));
        when(reviewReplyRepository.readLikeCount(REPLY_ID)).thenReturn(1L);

        reviewReplyService.likeReply(actor(), REPLY_ID);

        Notification n = capturedNotification();
        assertThat(n.getType()).isEqualTo(Notification.REPLY_LIKE);
        assertThat(n.getRecipient().getId()).isEqualTo(AUTHOR_ID);
        assertThat(n.getReply().getId()).isEqualTo(REPLY_ID);
        /* 三种类型都写 review_id(见 V11 的口径): 读列表时"点进那部番"要靠它, 而
           reply_id 只能定位到回复、定位不到番剧。漏了它的症状是列表里那一行点不动 */
        assertThat(n.getReview().getId()).as("通知行要能跳回番剧, 靠的是 review_id")
                .isEqualTo(REVIEW_ID);
        verify(reviewReplyRepository).incrementLikeCount(REPLY_ID);
    }

    // ========== 自己对自己的事不通知 ==========

    /**
     * 三种自互动都合法(赞自己的评论、回复自己的评论、赞自己的回复), 但都不该给自己发通知。
     *
     * <p>不只是噪音: 那个红点会**永远点不掉** —— 它只在查看时被清掉, 而下次自赞又亮起来。
     */
    @Test
    @DisplayName("自赞 / 自回都不写通知, 但动作本身照常完成")
    void selfActionsAreNotNotified() {
        // 1) 回复自己的评论
        when(reviewRepository.findByIdWithUser(REVIEW_ID))
                .thenReturn(Optional.of(review(ACTOR_ID)));
        when(reviewReplyRepository.saveAndFlush(any(ReviewReply.class))).thenAnswer(i -> {
            ReviewReply saved = i.getArgument(0);
            saved.setId(REPLY_ID);
            return saved;
        });
        when(reviewReplyRepository.findByIdWithUser(REPLY_ID))
                .thenReturn(Optional.of(replyWithAuthor(ACTOR_ID)));
        reviewReplyService.addReply(actor(), REVIEW_ID, "补充一句");

        // 2) 赞自己的评论
        reviewLikeService.like(actor(), REVIEW_ID);

        // 3) 赞自己的回复
        when(reviewReplyRepository.findByIdWithUser(REPLY_ID))
                .thenReturn(Optional.of(replyWithAuthor(ACTOR_ID)));
        reviewReplyService.likeReply(actor(), REPLY_ID);

        verify(notificationRepository, never()).save(any(Notification.class));
        // 反过来: 三次动作本身都做完了(否则上面那条 never 可能是因为压根没跑)
        verify(reviewRepository).incrementReplyCount(REVIEW_ID);
        verify(reviewRepository).incrementLikeCount(REVIEW_ID);
        verify(reviewReplyRepository).incrementLikeCount(REPLY_ID);
    }

    /**
     * 收件人取不到时(理论上的脏数据)安静地不写, 而不是写一条 recipient_id 为 null 的行
     * 或者当场 NPE —— 一条通知不值得把"点赞成功"变成 500。
     */
    @Test
    @DisplayName("收件人 id 为 null 时不写通知")
    void noRecipientMeansNoNotification() {
        NotificationService service = new NotificationService(
                notificationRepository, userRepository, reviewRepository, reviewReplyRepository);

        service.onReviewLike(actor(), null, REVIEW_ID);

        verify(notificationRepository, never()).save(any(Notification.class));
    }

    // ========== 幂等 ==========

    /**
     * <b>重复点赞不产生第二条通知。</b>
     *
     * <p>点赞是幂等的: 已经赞过再点一次会撞唯一约束, 走 catch 那条分支(不报错)。通知
     * 写在 {@code saveAndFlush} 之后、{@code attempt} 之内, 于是那条幂等路径根本走不到
     * 通知那一句 —— 用户连点十下"赞", 收件人只该收到一条。
     *
     * <p>把它挪到 catch 后面(或者 attempt 外面), 这个用例就红: 那正是"手滑点两次 =
     * 人家收到两条通知"的写法。
     */
    @Test
    @DisplayName("已经赞过再点一次: 不产生第二条通知")
    void duplicateLikesDoNotNotifyAgain() {
        reviewLikeService.like(actor(), REVIEW_ID);

        when(reviewLikeRepository.saveAndFlush(any(ReviewLike.class)))
                .thenThrow(new DataIntegrityViolationException("uk_review_like_review_user"));
        reviewLikeService.like(actor(), REVIEW_ID);

        verify(notificationRepository, times(1)).save(any(Notification.class));
    }

    @Test
    @DisplayName("赞回复的幂等路径同理: 不产生第二条通知")
    void duplicateReplyLikesDoNotNotifyAgain() {
        when(reviewReplyRepository.findByIdWithUser(REPLY_ID))
                .thenReturn(Optional.of(replyWithAuthor(AUTHOR_ID)));
        when(reviewReplyRepository.readLikeCount(REPLY_ID)).thenReturn(1L);
        reviewReplyService.likeReply(actor(), REPLY_ID);

        when(replyLikeRepository.saveAndFlush(any(ReplyLike.class)))
                .thenThrow(new DataIntegrityViolationException("uk_reply_like_reply_user"));
        reviewReplyService.likeReply(actor(), REPLY_ID);

        verify(notificationRepository, times(1)).save(any(Notification.class));
    }

    // ========== 事务边界 ==========

    /**
     * <b>三个写入点写的通知都在 {@code attempt} 之内, 也就是与"插入行 + 增减计数器"
     * 同一个事务。</b>
     *
     * <p>分开写就是第三个事务: "赞记上了、通知没写"会静默发生 —— 不报错, 只是收件人
     * 永远不知道, 而这类半截状态没有别的东西守得住。所以这里直接盯深度(期望 1)与
     * 事务个数(期望 1), 而不是看结果对不对(拆开之后结果一模一样)。
     */
    @Test
    @DisplayName("发回复: 通知与回复行、计数器在同一个事务里")
    void theReplyNotificationSharesTheTransaction() {
        when(reviewReplyRepository.saveAndFlush(any(ReviewReply.class))).thenAnswer(i -> {
            ReviewReply saved = i.getArgument(0);
            saved.setId(REPLY_ID);
            return saved;
        });
        when(reviewReplyRepository.findByIdWithUser(REPLY_ID))
                .thenReturn(Optional.of(replyWithAuthor(AUTHOR_ID)));

        reviewReplyService.addReply(actor(), REVIEW_ID, "同感");

        assertThat(isolatedInsert.depthWhenNotified)
                .as("通知的 save 必须发生在 attempt 内部(=1); 0 表示它被挪到了外面, 那是第三个事务")
                .isEqualTo(1);
        assertThat(isolatedInsert.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("赞评论: 通知与赞行、计数器在同一个事务里")
    void theReviewLikeNotificationSharesTheTransaction() {
        reviewLikeService.like(actor(), REVIEW_ID);

        assertThat(isolatedInsert.depthWhenNotified).isEqualTo(1);
        assertThat(isolatedInsert.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("赞回复: 通知与赞行、计数器在同一个事务里")
    void theReplyLikeNotificationSharesTheTransaction() {
        when(reviewReplyRepository.findByIdWithUser(REPLY_ID))
                .thenReturn(Optional.of(replyWithAuthor(AUTHOR_ID)));
        when(reviewReplyRepository.readLikeCount(REPLY_ID)).thenReturn(1L);

        reviewReplyService.likeReply(actor(), REPLY_ID);

        assertThat(isolatedInsert.depthWhenNotified).isEqualTo(1);
        assertThat(isolatedInsert.calls).isEqualTo(1);
    }

    /** 取消赞不该写任何通知 —— 这一条防的是"顺手在 unlike 里也加一句" */
    @Test
    @DisplayName("取消点赞 / 取消回复的赞: 一条通知都不写")
    void unlikesDoNotNotify() {
        when(reviewLikeRepository.deleteByReviewIdAndUserId(REVIEW_ID, ACTOR_ID)).thenReturn(1);
        when(reviewReplyRepository.findByIdWithUser(REPLY_ID))
                .thenReturn(Optional.of(replyWithAuthor(AUTHOR_ID)));
        when(replyLikeRepository.deleteByReplyIdAndUserId(REPLY_ID, ACTOR_ID)).thenReturn(1);

        reviewLikeService.unlike(actor(), REVIEW_ID);
        reviewReplyService.unlikeReply(actor(), REPLY_ID);

        verify(notificationRepository, never()).save(any(Notification.class));
    }

    /**
     * 删回复不改通知: 指向它的那一行由库级的 {@code ON DELETE CASCADE} 带走(两级的
     * 第二级: 删评论 → 删回复 → 删通知), 不在 Java 侧手写 —— 手写一遍就多一处会漏的
     * 地方, 而漏掉的表现是列表里堆着一堆点进去什么都没有的通知。
     *
     * <p>删评论那条对应的断言在 {@code NotificationReadTest}(真库, 靠真的级联)。
     */
    @Test
    @DisplayName("删回复不手写删通知 —— 那是库的级联该做的事")
    void deletingAReplyDoesNotTouchNotificationsByHand() {
        // 由 actor 自己写的那条回复(删自己的), 否则会先撞权限判断
        when(reviewReplyRepository.findByIdWithUser(REPLY_ID))
                .thenReturn(Optional.of(replyWithAuthor(ACTOR_ID)));

        reviewReplyService.deleteReply(actor(), REPLY_ID);

        verify(reviewReplyRepository).deleteById(REPLY_ID);
        verify(reviewRepository).decrementReplyCount(REVIEW_ID);
        verify(notificationRepository, never()).save(any(Notification.class));
        verify(notificationRepository, never()).deleteById(anyLong());
    }
}
