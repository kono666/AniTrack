package com.animetracker.service;

import com.animetracker.entity.Review;
import com.animetracker.entity.ReviewLike;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.ReviewLikeRepository;
import com.animetracker.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 点赞的两个写路径在「已经赞过 / 没赞过」这两侧的行为。
 *
 * <p>为什么这些用例值得存在 —— 它们盯的都不是"能不能赞上", 而是**重复点击**:
 *
 * <ul>
 *   <li>连点两下不能插出两行(幂等靠唯一约束 + 冲突分支, 不靠"先查再插");</li>
 *   <li>取消一个本来就没赞过的, 计数**不能**跟着减 —— 那种偏差没有任何报错,
 *       而且点得越多偏得越远(每个用户取消两次就永久少一);</li>
 *   <li>冲突分支里要分得清"已经赞过"(唯一约束)和"这条评论刚被删了"(外键):
 *       前者是幂等, 后者该报 404。两者都是 {@code DataIntegrityViolationException},
 *       唯一的区别是那一刻评论还在不在。</li>
 * </ul>
 *
 * <p>用 mock 而不是起库: 上面几条的分支取决于"仓储返回了什么", 而真库里要造出
 * 「外键冲突」得先让评论在两步之间消失, 那是无法稳定复现的时序。库那一侧另有
 * {@code controller/ReviewLikeIntegrationTest} 用真库验(计数对账、> 0 守卫)。
 *
 * <p>与 {@link ReviewServiceTest} 一样用真的 {@link IsolatedInsert}: 它只是一个
 * 带 REQUIRES_NEW 的 {@code supplier.get()}, 没有事务管理器时就是个直通调用,
 * 而"两个写有没有包在同一次 attempt 里"正是这里要能断言的东西。
 */
class ReviewLikeServiceTest {

    /** 被赞的那条评论的作者。通知要送到他手上, 所以每条桩里的 Review 都得带上他 */
    private static final Long AUTHOR_ID = 2L;

    private ReviewRepository reviewRepository;
    private ReviewLikeRepository reviewLikeRepository;
    private NotificationService notificationService;
    private CountingInsert isolatedInsert;
    private ReviewLikeService reviewLikeService;

    /**
     * 真的 {@link IsolatedInsert}, 外加数一下自己被调了几次.
     *
     * <p>数这个数是因为「两个写在同一个事务里」这条**在别处看不出来**: 拆成两次
     * {@code attempt} 之后, 一切正常路径的结果都一模一样 —— 行照样写进去、计数照样涨,
     * 只有"中间挂掉"时才分得出高下, 而那是造不出来的时序。所以直接盯"开了几个事务":
     * 一次点赞只允许开一个。
     *
     * <p>没有事务管理器时 {@code attempt} 就是个直通调用({@code insert.get()}),
     * 所以这个替身除计数之外不改变任何行为, 上面那些用例仍然在测真的代码。
     */
    private static class CountingInsert extends IsolatedInsert {
        int calls;

        @Override
        public <T> T attempt(Supplier<T> insert) {
            calls++;
            return insert.get();
        }
    }

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ReviewRepository.class);
        reviewLikeRepository = mock(ReviewLikeRepository.class);
        // 通知那侧是替身: 这一组盯的是"赞"这条路径的分支, 通知有没有写、写在哪个事务里
        // 由 NotificationWriteTest 管。但 like() 会去取评论作者的 id, 所以下面每条桩里
        // 的 Review **必须带 user**, 否则是 NPE 而不是断言失败。
        notificationService = mock(NotificationService.class);
        isolatedInsert = new CountingInsert();
        reviewLikeService = new ReviewLikeService(reviewRepository, reviewLikeRepository,
                isolatedInsert, notificationService);
        // 默认: 评论存在(带作者), 计数读回来是 1
        when(reviewRepository.findByIdWithUser(any())).thenReturn(Optional.of(reviewWithAuthor()));
        when(reviewRepository.existsById(any())).thenReturn(true);
        when(reviewRepository.readLikeCount(any())).thenReturn(1L);
    }

    private static Review reviewWithAuthor() {
        return Review.builder().id(7L).subjectId(200)
                .user(User.builder().id(AUTHOR_ID).username("owner").build())
                .build();
    }

    private static User user() {
        return User.builder().id(1L).username("alice").role("USER").status("ACTIVE").build();
    }

    // ========== 点赞 ==========

    @Test
    @DisplayName("点赞: 写一行赞 + 计数加一, 回 {liked=true, likeCount}")
    void likeWritesTheRowAndBumpsTheCounter() {
        when(reviewRepository.readLikeCount(7L)).thenReturn(1L);

        Map<String, Object> result = reviewLikeService.like(user(), 7L);

        assertThat(result).containsOnlyKeys("liked", "likeCount");
        assertThat(result.get("liked")).isEqualTo(true);
        assertThat(result.get("likeCount")).isEqualTo(1L);
        // 记下插进去的那行: 两个外键都必须真的填上, 否则写进去的是一行"谁都没赞"的赞
        ArgumentCaptor<ReviewLike> saved = ArgumentCaptor.forClass(ReviewLike.class);
        verify(reviewLikeRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUser().getId()).isEqualTo(1L);
        assertThat(saved.getValue().getReview().getId()).isEqualTo(7L);
        verify(reviewRepository).incrementLikeCount(7L);
    }

    /**
     * 已经赞过再点一次: 插入撞唯一约束, 但**不是错误**。
     *
     * <p>这就是幂等: 结果与第一次完全相同(200 + liked=true), 计数也不变。
     * 之所以不写"先查再插", 是因为那样在并发下照样会撞, 只是把冲突推迟到提交那一刻
     * —— 而那时候已经在事务里, 异常不再是"可以捕获并当成正常"的东西了。
     */
    @Test
    @DisplayName("重复点赞: 插入撞唯一约束仍然回 liked=true, 且不再动计数")
    void likingTwiceIsIdempotent() {
        when(reviewLikeRepository.saveAndFlush(any(ReviewLike.class)))
                .thenThrow(new DataIntegrityViolationException("uk_review_like_review_user"));
        when(reviewRepository.readLikeCount(7L)).thenReturn(1L);

        Map<String, Object> result = reviewLikeService.like(user(), 7L);

        assertThat(result.get("liked")).isEqualTo(true);
        assertThat(result.get("likeCount")).as("计数不该被加第二次").isEqualTo(1L);
        verify(reviewRepository, never()).incrementLikeCount(anyLong());
    }

    /**
     * 冲突的另一半: 评论在这两步之间被别人删了。
     *
     * <p>这时撞的是外键, 而它和"已经赞过"长得一模一样(同一个异常类型)。区别只在
     * 评论还在不在 —— 所以冲突后必须再问一次。不问的话, 用户会收到"点赞成功"
     * 而那条评论已经不存在了, 前端拿着一个永远刷不出来的状态。
     */
    @Test
    @DisplayName("冲突后发现评论已被删: 报 404, 不假装点赞成功")
    void conflictBecauseTheReviewVanishedIsNotFound() {
        // 第一次(进 like 时)还在, 是 setUp 里那条 findByIdWithUser 给的;
        // 冲突之后重问的那一次(existsById)才是不在
        when(reviewRepository.existsById(any())).thenReturn(false);
        when(reviewLikeRepository.saveAndFlush(any(ReviewLike.class)))
                .thenThrow(new DataIntegrityViolationException("fk_review_like_review"));

        assertThatThrownBy(() -> reviewLikeService.like(user(), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("评论不存在");
    }

    @Test
    @DisplayName("赞一条不存在的评论: 404, 而且一行都不写")
    void likingAMissingReviewIsNotFound() {
        when(reviewRepository.findByIdWithUser(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewLikeService.like(user(), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("评论不存在");

        verify(reviewLikeRepository, never()).saveAndFlush(any(ReviewLike.class));
    }

    /**
     * 计数读回来是 null(评论在插入与读计数之间被删了)时回 0, 不把 null 塞进响应。
     *
     * <p>前端的赞数就是直接渲染这个字段, null 会显示成空白 —— 那是"渲染坏了"的样子,
     * 而真相只是这条评论已经没了。
     */
    @Test
    @DisplayName("读不到计数(评论刚被删)时回 0, 而不是 null")
    void nullCounterBecomesZero() {
        when(reviewRepository.readLikeCount(7L)).thenReturn(null);

        assertThat(reviewLikeService.like(user(), 7L).get("likeCount")).isEqualTo(0L);
    }

    // ========== 取消点赞 ==========

    @Test
    @DisplayName("取消点赞: 真的删掉了一行才减计数, 回 {liked=false}")
    void unlikeDecrementsOnlyWhenARowWasActuallyRemoved() {
        when(reviewLikeRepository.deleteByReviewIdAndUserId(7L, 1L)).thenReturn(1);
        when(reviewRepository.readLikeCount(7L)).thenReturn(0L);

        Map<String, Object> result = reviewLikeService.unlike(user(), 7L);

        assertThat(result).containsOnlyKeys("liked", "likeCount");
        assertThat(result.get("liked")).isEqualTo(false);
        assertThat(result.get("likeCount")).isEqualTo(0L);
        verify(reviewRepository).decrementLikeCount(7L);
    }

    /**
     * 没赞过再取消一次: 一行都没删掉, 于是计数**一个都不减**。
     *
     * <p>这条是这次改动里最容易写错的一处: 不判断返回值(或者用派生删除)的写法在这里
     * 会把计数减掉。它不会报错, 界面上只是数字少了一个 —— 而每个用户重复点两次取消,
     * 就永久少一个, 越点越偏。这也是 {@code deleteByReviewIdAndUserId} 写成返回
     * {@code int} 的唯一理由。
     */
    @Test
    @DisplayName("取消一个没赞过的: 不减计数")
    void unlikingSomethingNeverLikedDoesNotTouchTheCounter() {
        when(reviewLikeRepository.deleteByReviewIdAndUserId(7L, 1L)).thenReturn(0);
        when(reviewRepository.readLikeCount(7L)).thenReturn(3L);

        Map<String, Object> result = reviewLikeService.unlike(user(), 7L);

        assertThat(result.get("liked")).isEqualTo(false);
        assertThat(result.get("likeCount")).as("计数该原样不动").isEqualTo(3L);
        verify(reviewRepository, never()).decrementLikeCount(anyLong());
    }

    @Test
    @DisplayName("取消一条不存在的评论的赞: 404")
    void unlikingAMissingReviewIsNotFound() {
        when(reviewRepository.existsById(any())).thenReturn(false);

        assertThatThrownBy(() -> reviewLikeService.unlike(user(), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("评论不存在");

        verify(reviewLikeRepository, never()).deleteByReviewIdAndUserId(anyLong(), anyLong());
    }

    // ========== 事务边界 ==========

    /**
     * <b>一次点赞只开一个事务。</b>
     *
     * <p>这是 {@code like} 里那句「两个写必须在同一个 {@code isolatedInsert} 里」的
     * 唯一自动化守卫。拆开之后所有正常路径的结果完全相同(行照样写、计数照样涨),
     * 差别只在中间失败时: 于是"行写进去了、计数没涨"和"计数涨了、行没写进去"都变成
     * 可能, 两种都不报错, 只让计数越漂越远 —— 而那正是 {@code theCounterAlwaysMatchesTheRows}
     * 想守的东西, 它却守不住这一条(它比的是终态, 不是原子性)。
     */
    @Test
    @DisplayName("点赞的两个写共用一个事务, 不是两次独立提交")
    void likeOpensExactlyOneTransaction() {
        reviewLikeService.like(user(), 7L);

        assertThat(isolatedInsert.calls)
                .as("拆成两次 attempt = 两个事务: 中间失败时行与计数会只落一半, 且没有任何报错")
                .isEqualTo(1);
    }

    /** 取消点赞同理: 删行与减计数必须一起成或一起不成 */
    @Test
    @DisplayName("取消点赞的两个写也共用一个事务")
    void unlikeOpensExactlyOneTransaction() {
        when(reviewLikeRepository.deleteByReviewIdAndUserId(7L, 1L)).thenReturn(1);

        reviewLikeService.unlike(user(), 7L);

        assertThat(isolatedInsert.calls).isEqualTo(1);
    }

    // ========== 谁赞了 ==========

    @Test
    @DisplayName("谁赞了: 名字按顺序列出来, total 取冗余计数而不是另发一条 COUNT")
    void likersAreListedWithTheCounterAsTotal() {
        when(reviewRepository.findById(7L)).thenReturn(Optional.of(
                Review.builder().id(7L).subjectId(200).likeCount(12L).build()));
        when(reviewLikeRepository.findLikers(eq(7L), any(Pageable.class))).thenReturn(List.of(
                ReviewLike.builder().user(User.builder().id(3L).username("bob").build()).build(),
                ReviewLike.builder().user(User.builder().id(4L).username("carol").build()).build()));

        Map<String, Object> result = reviewLikeService.getLikers(7L);

        assertThat(result.get("total")).as("必须与界面上那个赞数同源, 否则'共 12 人'下面只有 2 个名字")
                .isEqualTo(12L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("list");
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).containsEntry("userId", 3L).containsEntry("username", "bob");
        assertThat(list.get(1)).containsEntry("username", "carol");
    }

    /**
     * 名字封顶 {@link ReviewLikeService#MAX_LIKERS_SHOWN} 个, 且这个上限是**下推**给
     * 数据库的(Pageable 而不是"全读出来再 subList")。
     *
     * <p>分页参数从返回值上看不出来(库里就两条时, 传 50 和传 1000 拿到的一样),
     * 所以断言的是真正交给仓储的那个 Pageable。
     */
    @Test
    @DisplayName("谁赞了: 上限下推给数据库, 不是全读回来再截断")
    void likersAreCappedInTheDatabase() {
        when(reviewRepository.findById(7L)).thenReturn(Optional.of(
                Review.builder().id(7L).likeCount(0L).build()));
        when(reviewLikeRepository.findLikers(eq(7L), any(Pageable.class))).thenReturn(List.of());

        reviewLikeService.getLikers(7L);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewLikeRepository).findLikers(eq(7L), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(ReviewLikeService.MAX_LIKERS_SHOWN);
        assertThat(captor.getValue().getOffset()).isZero();
    }

    @Test
    @DisplayName("谁赞了一条不存在的评论: 404")
    void likersOfAMissingReviewIsNotFound() {
        when(reviewRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewLikeService.getLikers(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("评论不存在");
    }
}
