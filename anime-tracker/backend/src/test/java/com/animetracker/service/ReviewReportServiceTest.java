package com.animetracker.service;

import com.animetracker.entity.Review;
import com.animetracker.entity.ReviewReport;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.ReviewReportRepository;
import com.animetracker.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 举报的两个写路径(提交 / 忽略)与读路径(明细)。
 *
 * <p>这一层盯的是**分支与顺序**, 不是"能不能举报上" —— 后者由
 * {@code controller/ReviewReportIntegrationTest} 在真库上验。值得在这里钉住的有三类:
 *
 * <ul>
 *   <li><b>理由白名单</b>: 它是**写**路径的参数校验, 未知值必须 400 而不是像读路径那样
 *       沉默放行(见下); 而校验排在最前面, 意味着一次都不该碰库;</li>
 *   <li><b>冲突的两种含义</b>: 唯一约束撞了是幂等({@code duplicate=true}), 外键撞了是
 *       "这条评论刚被删了"(404)。两者都是 {@code DataIntegrityViolationException},
 *       唯一的区别是那一刻评论还在不在;</li>
 *   <li><b>忽略的幂等</b>: 再忽略一次不能把 {@code handled_by} / {@code handled_at}
 *       覆盖成新的值 —— 那会让"谁在什么时候处理的"变成一个会漂移的答案。</li>
 * </ul>
 *
 * <p>用真的 {@link IsolatedInsert}(没有事务管理器时它就是个直通调用), 与
 * {@link ReviewLikeServiceTest} 同一个取舍: "这件事有没有包在一次 attempt 里"正是这里
 * 要能断言的东西, 换成 mock 就把被断言的东西本身换掉了。
 */
class ReviewReportServiceTest {

    private static final Long AUTHOR_ID = 2L;
    private static final Long REPORTER_ID = 9L;
    private static final Long REVIEW_ID = 77L;

    private ReviewRepository reviewRepository;
    private ReviewReportRepository reviewReportRepository;
    private CountingInsert isolatedInsert;
    private ReviewReportService reviewReportService;

    /**
     * 真的 {@link IsolatedInsert}, 外加数一下自己被调了几次。
     *
     * <p>"开了几次事务"在正常路径上完全看不出来 —— 拆成两次 {@code attempt} 之后行照样
     * 写进去、状态照样改对, 只有"中间挂掉"时才分得出高下, 而那是造不出来的时序。
     * 所以直接盯这个数。
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
        reviewReportRepository = mock(ReviewReportRepository.class);
        isolatedInsert = new CountingInsert();
        reviewReportService =
                new ReviewReportService(reviewRepository, reviewReportRepository, isolatedInsert);

        when(reviewRepository.findByIdWithUser(REVIEW_ID))
                .thenReturn(Optional.of(reviewBy(AUTHOR_ID)));
        when(reviewReportRepository.saveAndFlush(any(ReviewReport.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== 提交: 理由白名单 ====================

    /**
     * 未知理由回 400, <b>而且一次库都不碰</b>。
     *
     * <p>两个方向都要断。只断 400 的话, 把校验挪到读库之后仍然能过 —— 而那正是
     * "请求形状不对却白读一次库"的写法: 功能一模一样, 只是每个坏请求都多一次查询,
     * 而这恰好是有人会利用的那种请求。
     *
     * <p>参数里同时放进 {@code null} 与 {@code "  "}: {@code ReviewReport.REASONS} 是
     * {@code LinkedHashSet}(不是 {@code Set.of}), 所以 {@code contains(null)} 返回 false
     * 而不是抛 NPE —— 但代码里仍然先判 null, 为的是给出「请选择举报理由」这句具体的话。
     */
    @ParameterizedTest(name = "reason=<{0}>")
    @ValueSource(strings = {"", "   ", "BOGUS", "spamX", "SPAMED"})
    @DisplayName("理由不在白名单里 → 400, 且根本没读库")
    void anUnknownReasonIsRejectedBeforeTouchingTheDatabase(String reason) {
        assertThatThrownBy(() -> reviewReportService.report(reporter(), REVIEW_ID, reason, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("举报理由");

        verify(reviewRepository, never()).findByIdWithUser(any());
        verify(reviewReportRepository, never()).saveAndFlush(any());
        assertThat(isolatedInsert.calls).as("校验没过就不该开事务").isZero();
    }

    /** {@code null} 单独来一条: 它不该走「不合法」那句, 而该走「请选择」那句 */
    @Test
    @DisplayName("理由为 null → 400「请选择举报理由」, 与「不合法」不是同一句话")
    void aMissingReasonGetsItsOwnMessage() {
        assertThatThrownBy(() -> reviewReportService.report(reporter(), REVIEW_ID, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请选择举报理由");
    }

    /**
     * 大小写与空白都归一化后再比 —— 存进库的是**大写的那个**。
     *
     * <p>断言落在"库里存了什么"上, 而不是"接口回没回 200": 后者对
     * {@code reason=spam} 与 {@code reason=SPAM} 都成立, 但只有前者会被存成
     * {@code spam} —— 而管理端是按 {@code reason} 精确筛的(与 {@code AdminService} 里
     * 那些筛选参数同一条 doctrine), 存成小写就意味着这条举报**永远筛不出来**。
     */
    @ParameterizedTest(name = "reason=<{0}>")
    @ValueSource(strings = {"SPAM", "spam", "Spam", "  spam  "})
    @DisplayName("理由归一化: 大小写与首尾空白都不算数, 落库的一律是大写那个")
    void theReasonIsNormalizedBeforeItIsStored(String reason) {
        reviewReportService.report(reporter(), REVIEW_ID, reason, null);

        assertThat(capturedReport().getReason())
                .as("存成小写的话, 管理端按 reason 精确筛就永远找不到它")
                .isEqualTo("SPAM");
    }

    @Test
    @DisplayName("补充说明: 空白存 null(不是空串), 首尾空白被去掉")
    void theDetailIsTrimmedAndBlankBecomesNull() {
        reviewReportService.report(reporter(), REVIEW_ID, "SPAM", "   ");

        assertThat(capturedReport().getDetail())
                .as("空串与 null 在库里是两个值, 而这个字段只有「写了」与「没写」两种状态")
                .isNull();
    }

    /**
     * 补充说明的上限在 service 里还有第二道。
     *
     * <p>{@code ReportRequest} 上的 {@code @Size(max = 500)} 只挡得住走接口的请求 ——
     * Agent 工具、测试里的直调、将来的批处理都绕得过去。数据库那一列是
     * {@code VARCHAR(500)}, 真超了会在插入时炸成一个 500。
     */
    @Test
    @DisplayName("补充说明超长 → 400(第二道; 第一道是 DTO 上的 @Size)")
    void anOverlongDetailIsRejectedInTheServiceToo() {
        String tooLong = "x".repeat(501);

        assertThatThrownBy(() -> reviewReportService.report(reporter(), REVIEW_ID, "SPAM", tooLong))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("500");
    }

    // ==================== 提交: 评论存在性与归属 ====================

    @Test
    @DisplayName("评论不存在 → 404")
    void reportingAMissingReviewIsNotFound() {
        when(reviewRepository.findByIdWithUser(REVIEW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewReportService.report(reporter(), REVIEW_ID, "SPAM", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("评论不存在");
    }

    /**
     * 举报自己的评论 → 400, 且**不开事务**。
     *
     * <p>为什么值得回一个错误而不是干脆忽略: 那多半是误点(点错了行), 静默成功会让用户
     * 以为举报了别人。而这又是个不需要的一条数据 —— 它只会让管理端多一条永远该被忽略的
     * 记录。
     */
    @Test
    @DisplayName("举报自己的评论 → 400, 不写行")
    void reportingYourOwnReviewIsRejected() {
        // 这条评论的作者**就是举报人自己** —— 上面 setUp 里那条桩的作者是别人
        when(reviewRepository.findByIdWithUser(REVIEW_ID))
                .thenReturn(Optional.of(Review.builder().id(REVIEW_ID).user(reporter()).build()));

        assertThatThrownBy(() -> reviewReportService.report(reporter(), REVIEW_ID, "SPAM", null))
                .as("这条评论的作者就是举报人自己")
                .isInstanceOf(BusinessException.class)
                .hasMessage("不能举报自己的评论");

        verify(reviewReportRepository, never()).saveAndFlush(any());
        assertThat(isolatedInsert.calls).isZero();
    }

    // ==================== 提交: 幂等与冲突分流 ====================

    @Test
    @DisplayName("成功提交: {reported:true, duplicate:false}, 且只开一个事务")
    void aFreshReportSaysItIsNotADuplicate() {
        Map<String, Object> result = reviewReportService.report(reporter(), REVIEW_ID, "SPAM", "广告");

        assertThat(result).containsEntry("reported", true).containsEntry("duplicate", false);
        assertThat(isolatedInsert.calls).as("插入只允许开一次事务").isEqualTo(1);
        assertThat(capturedReport().getReview().getId()).isEqualTo(REVIEW_ID);
        assertThat(capturedReport().getReporter().getId()).isEqualTo(REPORTER_ID);
    }

    /**
     * 重复举报回 200 + {@code duplicate=true}, <b>不是</b>报错。
     *
     * <p>冲突靠 catch 而不是"先查再插"(与 {@code ReviewLikeService.like} 逐字相同的
     * 理由): 先查再插在并发下照样会撞, 只是把冲突挪到了提交时; 而"查一次再插一次"在
     * 非并发下也要多花一次查询。
     */
    @Test
    @DisplayName("唯一约束冲突 → 200 + duplicate=true(幂等, 不是错误)")
    void aDuplicateReportIsIdempotentNotAnError() {
        when(reviewReportRepository.saveAndFlush(any(ReviewReport.class)))
                .thenThrow(new DataIntegrityViolationException("uk_review_report_review_reporter"));
        when(reviewRepository.existsById(REVIEW_ID)).thenReturn(true);

        Map<String, Object> result = reviewReportService.report(reporter(), REVIEW_ID, "SPAM", null);

        assertThat(result).containsEntry("reported", true).containsEntry("duplicate", true);
    }

    /**
     * 同一个异常、另一种含义: 评论在"读出来"与"插进去"之间被别人删了。
     *
     * <p>两种冲突都是 {@code DataIntegrityViolationException}, 唯一的区别是那一刻评论
     * 还在不在。把它们当成一件事的后果是把一条真的没了的评论报成"举报成功" —— 用户看到
     * "已收到举报", 而那条举报因为外键失败根本不存在。
     *
     * <p>只在冲突这条罕见路径上多花一次 {@code existsById}, 正常路径一次都不花。
     */
    @Test
    @DisplayName("冲突 + 评论已不在 → 404(与上一条是同一个异常, 靠 existsById 分流)")
    void aConflictWithAVanishedReviewIsNotFound() {
        when(reviewReportRepository.saveAndFlush(any(ReviewReport.class)))
                .thenThrow(new DataIntegrityViolationException("fk_review_report_review"));
        when(reviewRepository.existsById(REVIEW_ID)).thenReturn(false);

        assertThatThrownBy(() -> reviewReportService.report(reporter(), REVIEW_ID, "SPAM", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("评论不存在");
    }

    // ==================== 忽略 ====================

    @Test
    @DisplayName("忽略: 状态、处理人、处理时间三个字段一起写上")
    void dismissingWritesAllThreeFields() {
        ReviewReport pending = report(1L, ReviewReport.PENDING, null, null);
        when(reviewReportRepository.findById(1L)).thenReturn(Optional.of(pending));

        Map<String, Object> result = reviewReportService.dismiss(admin(), 1L);

        assertThat(result).containsEntry("status", ReviewReport.DISMISSED);
        assertThat(pending.getStatus()).isEqualTo(ReviewReport.DISMISSED);
        assertThat(pending.getHandler().getId()).as("处理人是调用方那个管理员").isEqualTo(1L);
        assertThat(pending.getHandledAt()).as("未处理时它是 null, 处理之后必须有值").isNotNull();
    }

    /**
     * 再忽略一次: 仍然 200, 而 {@code handled_by} / {@code handled_at} <b>不被覆盖</b>。
     *
     * <p>只断"状态还是 DISMISSED"是不够的 —— 那在覆盖了处理人和时间的实现上照样成立,
     * 而覆盖的后果是「谁在什么时候处理的」变成一个会漂移的答案: 管理员每多点一次忽略,
     * 那条记录就换一个人、换一个时间。审计面上这是最坏的一种错(记录还在, 但内容是假的)。
     *
     * <p>时间戳另起一个明显不同的值: 用同一个 {@code now()} 的话, 覆盖与否看起来一样。
     */
    @Test
    @DisplayName("再忽略一次: 状态不变, 且处理人与处理时间**不被覆盖**")
    void dismissingTwiceKeepsTheOriginalHandlerAndTimestamp() {
        LocalDateTime original = LocalDateTime.of(2026, 1, 1, 8, 0);
        ReviewReport already = report(1L, ReviewReport.DISMISSED, original, admin(7L, "先处理的那个人"));
        when(reviewReportRepository.findById(1L)).thenReturn(Optional.of(already));

        Map<String, Object> result = reviewReportService.dismiss(admin(), 1L);

        assertThat(result).containsEntry("status", ReviewReport.DISMISSED);
        assertThat(already.getHandler().getId())
                .as("被覆盖的话, 每多点一次忽略, 这条记录就换一个人")
                .isEqualTo(7L);
        assertThat(already.getHandler().getUsername()).isEqualTo("先处理的那个人");
        assertThat(already.getHandledAt())
                .as("只断状态是拦不住覆盖的: 覆盖之后状态照样是 DISMISSED")
                .isEqualTo(original);
    }

    @Test
    @DisplayName("忽略一条不存在的举报 → 404")
    void dismissingAMissingReportIsNotFound() {
        when(reviewReportRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewReportService.dismiss(admin(), 404L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("举报不存在");
    }

    // ==================== 明细 ====================

    /**
     * 评论不存在时 404 而不是空列表。
     *
     * <p>空列表的含义是「这条评论没有任何举报」, 而「这条评论根本不在」是另一件事 ——
     * 合并之后界面分不清自己该说「暂无举报」还是「这条已经没了」。
     */
    @Test
    @DisplayName("评论不存在 → 404, 不是空列表")
    void detailsOfAMissingReviewAreNotFound() {
        when(reviewRepository.existsById(REVIEW_ID)).thenReturn(false);

        assertThatThrownBy(() -> reviewReportService.getDetails(REVIEW_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("评论不存在");
        verify(reviewReportRepository, never()).findDetails(any(), any());
    }

    /**
     * 明细行的键与值。
     *
     * <p>{@code handlerName} 与 {@code handledAt} 在未处理时**都要在**(值为 null)——
     * 与 {@code animeTitle} 同一条理由: 少一个键与"这个值恰好为空"在界面上长得一样,
     * 而前端那一格会走 undefined 分支。所以这里断的是 {@code containsEntry(..., null)}
     * 而不是"没有这个键"。
     */
    @Test
    @DisplayName("明细行: 十个键都在, 未处理时 handlerName/handledAt 是 null 而不是缺键")
    void detailRowsCarryEveryKeyEvenWhenUnhandled() {
        givenDetails(List.of(report(1L, ReviewReport.PENDING, null, null)), 1L);

        Map<String, Object> out = reviewReportService.getDetails(REVIEW_ID);

        assertThat(out).containsEntry("total", 1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("list");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("id", 1L)
                .containsEntry("reporterName", "举报的人")
                .containsEntry("reason", "SPAM")
                .containsEntry("detail", "广告")
                .containsEntry("status", ReviewReport.PENDING)
                .containsEntry("handlerName", null)
                .containsEntry("handledAt", null)
                .containsKeys("createdAt");
    }

    /**
     * {@code total} 是**不分状态**的全量条数, 而 {@code list} 是封顶后的那些。
     *
     * <p>两个数不是一回事: 只有 {@code list} 的话, 面板上分不清「就这 50 条」与
     * 「有 300 条, 只给你看 50 条」。而 {@code total} 若也跟着状态筛, 就会出现
     * "已忽略的不算数" —— 那正好丢掉了这个面板最想让人看见的东西。
     */
    @Test
    @DisplayName("total 是全部条数(不分状态), list 封顶在 MAX_DETAIL_SHOWN")
    void totalCountsEveryStatusWhileTheListIsCapped() {
        givenDetails(List.of(report(1L, ReviewReport.PENDING, null, null)), 300L);

        Map<String, Object> out = reviewReportService.getDetails(REVIEW_ID);

        assertThat(out).containsEntry("total", 300L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("list");
        assertThat(rows).as("封顶与总数是两件事, 分不清就说不清「共 300 条」").hasSize(1);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewReportRepository).findDetails(eq(REVIEW_ID), captor.capture());
        assertThat(captor.getValue().getPageSize())
                .as("封顶值只有一处定义")
                .isEqualTo(ReviewReportService.MAX_DETAIL_SHOWN);
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    // ==================== 替身数据 ====================

    private static User user(Long id, String name) {
        return User.builder().id(id).username(name).build();
    }

    private static User reporter() {
        return user(REPORTER_ID, "举报的人");
    }

    private static User admin() {
        return admin(1L, "管理员");
    }

    private static User admin(Long id, String name) {
        return user(id, name);
    }

    /** 评论存在 + 明细查询返回给定几行 —— 明细那几条用例的公共前半段 */
    private void givenDetails(List<ReviewReport> rows, long total) {
        when(reviewRepository.existsById(REVIEW_ID)).thenReturn(true);
        when(reviewReportRepository.findDetails(eq(REVIEW_ID), any(Pageable.class)))
                .thenReturn(rows);
        when(reviewReportRepository.countByReviewId(REVIEW_ID)).thenReturn(total);
    }

    private static Review reviewBy(Long authorId) {
        return Review.builder().id(REVIEW_ID).user(user(AUTHOR_ID, "作者")).build();
    }

    /** 一条已存在的举报行; {@code handledAt}/{@code handler} 为 null 表示未处理 */
    private static ReviewReport report(Long id, String status, LocalDateTime handledAt, User handler) {
        return ReviewReport.builder()
                .id(id)
                .review(reviewBy(AUTHOR_ID))
                .reporter(reporter())
                .reason("SPAM")
                .detail("广告")
                .status(status)
                .handler(handler)
                .handledAt(handledAt)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
    }

    /** {@code saveAndFlush} 收到的那条实体 */
    private ReviewReport capturedReport() {
        ArgumentCaptor<ReviewReport> captor = ArgumentCaptor.forClass(ReviewReport.class);
        verify(reviewReportRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }
}
