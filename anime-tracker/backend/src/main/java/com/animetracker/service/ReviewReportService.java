package com.animetracker.service;

import com.animetracker.entity.Review;
import com.animetracker.entity.ReviewReport;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.ReviewReportRepository;
import com.animetracker.repository.ReviewRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 举报: 提交、看明细、忽略.
 *
 * <p>三件事都在这里, 因为它们围着同一个聚合转 —— 与 {@code ReviewLikeService} 里
 * 「点赞 / 取消 / 谁赞了」同处一个类是同一个判断。**管理侧的两条也放这里**, 不搬去
 * {@code AdminService}: 那个类是后台的读路径门面(用户列表、评论列表、仪表盘、账本),
 * 而「忽略一条举报」是写一条 {@code review_report}, 只有这一个类知道它的状态机。
 */
@Service
public class ReviewReportService {

    /**
     * 明细面板一次最多给几条. 超过的部分由 {@code total} 报出去, 由界面说「等共 N 条」。
     *
     * <p>与 {@code ReviewLikeService.MAX_LIKERS_SHOWN} 同一个量级、同一条理由: 举报是
     * 低频事件, 一条评论被举报几十次已经是很不寻常的情况; 而真的有人恶意刷时, 面板
     * 一次渲染几百行也没有任何人会读。
     */
    public static final int MAX_DETAIL_SHOWN = 50;

    /** 补充说明的长度上限, 与建表那句 {@code VARCHAR(500)} 保持一致 */
    private static final int MAX_DETAIL_LENGTH = 500;

    private final ReviewRepository reviewRepository;
    private final ReviewReportRepository reviewReportRepository;
    private final IsolatedInsert isolatedInsert;

    public ReviewReportService(ReviewRepository reviewRepository,
                               ReviewReportRepository reviewReportRepository,
                               IsolatedInsert isolatedInsert) {
        this.reviewRepository = reviewRepository;
        this.reviewReportRepository = reviewReportRepository;
        this.isolatedInsert = isolatedInsert;
    }

    /**
     * 举报一条评论. <b>幂等</b>: 已经举报过再举报一次, 回 200 且 {@code duplicate=true}。
     *
     * <p>幂等不是礼貌, 是这条路必须有的性质: 一个人对同一条评论只能有一条举报
     * ({@code uk_review_report_review_reporter}), 而他要再点一次时, 「你已经在队列里了」
     * 和「刚刚记下了」对他是一样的。回 409 只会让前端多写一条分支去把它翻译成人话。
     *
     * <p><b>冲突是正常路径, 靠 catch 而不是「先查再插」</b> —— 与
     * {@code ReviewLikeService.like} 逐字相同的理由: 先查再插在并发下照样会撞, 只是把
     * 冲突挪到了提交时; 而「查一次再插一次」在非并发下也要多花一次查询。
     *
     * <p><b>校验顺序: 理由 → 评论存在 → 是不是自己的。</b> 理由排第一, 因为它一次数据库
     * 都不碰(纯比较), 而另外两关都要先把评论读出来; 请求形状不对就不该白读一次库。
     *
     * <p><b>为什么不像读路径那样对未知值沉默放行。</b> 管理端的筛选参数写错一个值, 后果是
     * 结果集放宽一点, 用户看得出不对; 这里的理由值写错, 后果是这条举报落进一个
     * **谁也筛不出来的档位**, 而且事后补不回来 —— 它是一次**写**。同一条 doctrine 的另一半
     * 见 {@code AdminService.setUserRole}(那条也是写, 未知角色回 400)。
     */
    public Map<String, Object> report(User user, Long reviewId, String reason, String detail) {
        String reasonKey = reasonOf(reason);
        String note = detailOf(detail);

        // 带作者读: 下面要判「是不是自己的评论」, 而作者是这条实体上的一个字段。
        // (为什么用 findByIdWithUser: 见那个方法上的注释。)
        Review review = reviewRepository.findByIdWithUser(reviewId)
                .orElseThrow(() -> BusinessException.notFound("评论不存在"));

        if (review.getUser().getId().equals(user.getId())) {
            throw BusinessException.badRequest("不能举报自己的评论");
        }

        boolean duplicate = false;
        try {
            isolatedInsert.attempt(() -> {
                reviewReportRepository.saveAndFlush(ReviewReport.builder()
                        .review(review)
                        .reporter(user)
                        .reason(reasonKey)
                        .detail(note)
                        .build());
                return null;
            });
        } catch (DataIntegrityViolationException e) {
            // 两种冲突都会落到这里, 得分清楚 —— 与 ReviewLikeService.like 同一处判断:
            //   · uk_review_report_review_reporter —— 已经举报过, 这是幂等, 什么都不做;
            //   · fk_review_report_review —— 这条评论刚好在这两步之间被别人删了,
            //     那是真的没有了, 该报 404 而不是假装举报成功。
            // 只在冲突这条罕见路径上多花一条查询, 正常路径一次都不花。
            if (!reviewRepository.existsById(reviewId)) {
                throw BusinessException.notFound("评论不存在");
            }
            duplicate = true;
        }

        return reportResult(duplicate);
    }

    /**
     * 某条评论的举报明细(管理端). 最新的在前.
     *
     * <p>评论不存在时 404 而不是回一个空列表: 空列表的含义是「这条评论没有任何举报」,
     * 而「这条评论根本不在」是另一件事 —— 两者合并之后, 界面分不清自己该说
     * 「暂无举报」还是「这条已经没了」。
     *
     * <p>返回里同时给 {@code total}(不分状态的全量条数)与 {@code list}(封顶后的那些),
     * 理由见 {@link #MAX_DETAIL_SHOWN}: 只有 {@code list} 的话, 面板上就分不清
     * 「就这 50 条」和「有 300 条, 只给你看 50 条」。
     */
    public Map<String, Object> getDetails(Long reviewId) {
        if (!reviewRepository.existsById(reviewId)) {
            throw BusinessException.notFound("评论不存在");
        }

        List<ReviewReport> rows =
                reviewReportRepository.findDetails(reviewId, PageRequest.of(0, MAX_DETAIL_SHOWN));

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (ReviewReport r : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", r.getId());
            map.put("reporterName", r.getReporter().getUsername());
            map.put("reason", r.getReason());
            map.put("detail", r.getDetail());
            map.put("status", r.getStatus());
            // 未处理时两者都是 null, 且**永远一起**为 null —— 它们在同一个分支里被赋值,
            // 见下面的 dismiss。界面上「谁处理的」与「什么时候处理的」本来就是一句话。
            map.put("handlerName", r.getHandler() == null ? null : r.getHandler().getUsername());
            map.put("handledAt", r.getHandledAt());
            map.put("createdAt", r.getCreatedAt());
            list.add(map);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", reviewReportRepository.countByReviewId(reviewId));
        return out;
    }

    /**
     * 忽略一条举报. <b>幂等</b>: 已经忽略过再调一次, 同样回 200。
     *
     * <p>与 {@code unlockUser} 是同一条理由 —— 它把状态改回一个**确定值**, 重复执行与
     * 执行一次的结果相同, 所以没有 400 可言。两条并发的「忽略」也不会互相打架:
     * 第二次进来时 {@code status} 已经是 DISMISSED, 那个 if 直接跳过。
     *
     * <p><b>为什么这一步不记审计账。</b> {@code admin_action_log} 记的是四个破坏性动作
     * 加管理员重置密码 —— 那些动作**改的是别人看得见的东西**(账号能不能登录、评论还在不在),
     * 事后只能靠账本还原。而忽略一条举报什么也没抹掉: 那一行还在 {@code review_report} 里,
     * 带着 {@code handled_by} 与 {@code handled_at}。「谁在什么时候忽略了哪条举报」这个
     * 问题, 直接读那张表就有答案 —— 往账本里再抄一份就是同一个事实的第二处定义,
     * 而两处迟早对不上。
     *
     * <p>用 {@link IsolatedInsert} 而不是给方法加 {@code @Transactional}: 与
     * {@code AdminService} 那四个动作同一个取舍(本仓刻意让这些 service 不带类级事务,
     * 而 Agent 工具那条链上已经有外层事务, 再加一层就是嵌套)。
     */
    public Map<String, Object> dismiss(User actor, Long reportId) {
        return isolatedInsert.attempt(() -> {
            ReviewReport report = reviewReportRepository.findById(reportId)
                    .orElseThrow(() -> BusinessException.notFound("举报不存在"));

            if (!ReviewReport.DISMISSED.equals(report.getStatus())) {
                report.setStatus(ReviewReport.DISMISSED);
                report.setHandler(actor);
                report.setHandledAt(LocalDateTime.now());
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", report.getStatus());
            return out;
        });
    }

    // ==================== 参数解析 ====================

    /**
     * 理由: 必须在 {@link ReviewReport#REASONS} 里, 否则 400.
     *
     * <p>去空白 + 转大写之后再比: 前端传的是常量, 但接口是公开的, 而
     * {@code reason=spam} 与 {@code reason=SPAM} 在调用方看来是同一件事 —— 为大小写
     * 回一个 400 是在惩罚一个没有歧义的输入。
     *
     * <p>{@code REASONS} 是 {@code LinkedHashSet}, 所以 {@code contains(null)} 返回
     * false 而不是抛 NPE —— 但这里仍然先判 null: 那样能给出「请选择举报理由」这句
     * 具体的话, 而不是一句笼统的「举报理由不合法」。
     */
    private static String reasonOf(String reason) {
        if (reason == null || reason.isBlank()) {
            throw BusinessException.badRequest("请选择举报理由");
        }
        String key = reason.trim().toUpperCase(Locale.ROOT);
        if (!ReviewReport.REASONS.contains(key)) {
            throw BusinessException.badRequest("举报理由不合法");
        }
        return key;
    }

    /** 补充说明: 空白等于没写(存 null 而不是空串); 超长在 DTO 那一层已被拦下, 这里是第二道 */
    private static String detailOf(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String trimmed = detail.trim();
        if (trimmed.length() > MAX_DETAIL_LENGTH) {
            throw BusinessException.badRequest("补充说明最多 " + MAX_DETAIL_LENGTH + " 字");
        }
        return trimmed;
    }

    /**
     * 提交举报的返回: {@code {reported: true, duplicate: boolean}}。
     *
     * <p>{@code reported} 恒为 true —— 它与 {@code duplicate} 不是同一件事: 前者说的是
     * 「这条评论现在在队列里」, 后者说的是「是你刚刚放进去的, 还是本来就在」。两个都给
     * 出来, 前端才能说清那句提示到底该是「已收到举报」还是「你已经举报过这条评论」。
     */
    private static Map<String, Object> reportResult(boolean duplicate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reported", true);
        out.put("duplicate", duplicate);
        return out;
    }
}
