package com.animetracker.agent.tool;

import com.animetracker.agent.tool.ToolDefinition.Access;
import com.animetracker.entity.User;
import com.animetracker.service.AdminService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营分析类工具 —— 仅管理员可用.
 *
 * 两道防线:
 * 1. ToolRegistry 在把工具暴露给模型之前就按角色过滤, 普通用户根本看不到这些工具;
 * 2. 每个执行器里再调一次 checkAdmin —— 万一将来有人绕过注册表直接执行, 这里仍然拦得住.
 *    权限判断永远放在服务端, 不能依赖「模型不会乱调」.
 *
 * 另外所有列表类结果都做了截断: 全站用户/评论直接塞给模型既费 token 也没必要,
 * 模型要的是「概况 + 样本」, 不是全量导出.
 */
@Component
public class AdminTools implements ToolProvider {

    /** 单次最多返回多少条明细, 超出部分只给总数 */
    private static final int DETAIL_LIMIT = 30;
    /** 评论正文在概览里的截断长度 */
    private static final int REVIEW_EXCERPT = 120;

    private final AdminService adminService;

    public AdminTools(AdminService adminService) {
        this.adminService = adminService;
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(dashboard(), listUsers(), listReviews(), animeHeat(), weeklyReport());
    }

    private ToolDefinition dashboard() {
        return ToolDefinition.builder()
                .name("platform_dashboard")
                .description("获取平台核心指标：用户总数、管理员数、启用/禁用数、评论总数、追番记录总数。"
                        + "用户问「平台现在什么情况」「有多少人用」时使用。")
                .access(Access.ADMIN)
                .executor((call, user) -> {
                    adminService.checkAdmin(user);
                    return adminService.getDashboard();
                })
                .build();
    }

    private ToolDefinition listUsers() {
        return ToolDefinition.builder()
                .name("list_users")
                .description("查看平台注册用户列表（按注册时间倒序）。"
                        + "用户问「最近有哪些人注册」「用户都是什么状态」时使用。"
                        + "只返回最近 " + DETAIL_LIMIT + " 条明细。")
                .access(Access.ADMIN)
                .executor((call, user) -> {
                    adminService.checkAdmin(user);
                    List<Map<String, Object>> all = adminService.getUserList();

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("total", all.size());
                    out.put("returned", Math.min(all.size(), DETAIL_LIMIT));
                    out.put("list", all.subList(0, Math.min(all.size(), DETAIL_LIMIT)));
                    if (all.size() > DETAIL_LIMIT) {
                        out.put("note", "仅展示最近 " + DETAIL_LIMIT + " 条，完整数据请到后台用户管理页查看");
                    }
                    return out;
                })
                .build();
    }

    private ToolDefinition listReviews() {
        return ToolDefinition.builder()
                .name("list_all_reviews")
                .description("查看全站最新评论（跨所有番剧）。"
                        + "用户想了解「最近大家在讨论什么」「有没有异常评论」时使用。"
                        + "只返回最近 " + DETAIL_LIMIT + " 条明细。")
                .access(Access.ADMIN)
                .executor((call, user) -> {
                    adminService.checkAdmin(user);
                    return reviewSample(DETAIL_LIMIT);
                })
                .build();
    }

    private ToolDefinition animeHeat() {
        return ToolDefinition.builder()
                .name("analyze_anime_heat")
                .description("全站热度榜：按「追番人数」统计最受欢迎的番剧，含追番人数与站内评分。"
                        + "这是数据库层聚合出的真实站内数据，不是外部榜单。"
                        + "用户问「站里最火的是什么」「大家在追什么」时使用。")
                .access(Access.ADMIN)
                .intParam("topN", "取前几名，默认 10，最大 30", false)
                .executor((call, user) -> {
                    adminService.checkAdmin(user);
                    int topN = Math.max(1, Math.min(30, call.integer("topN", 10)));

                    List<Map<String, Object>> ranking = adminService.getAnimeHeatRanking(topN);
                    // 补上番剧标记, 前端才能把榜单画成卡片 (见 ToolCards)
                    ToolViews.tagAnimeRows(ranking);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ranking", ranking);
                    if (ranking.isEmpty()) {
                        out.put("note", "目前还没有任何用户添加追番记录，热度榜为空。"
                                + "给结论时要说明这是「暂无数据」，不要编造排行。");
                    }
                    return out;
                })
                .build();
    }

    private ToolDefinition weeklyReport() {
        return ToolDefinition.builder()
                .name("weekly_ops_report")
                .description("一次性取回运营周报所需的全部数据：核心指标、热度榜、最新评论样本、用户构成。"
                        + "用户要「周报」「整体分析」「运营情况总结」时优先用它，"
                        + "一次调用胜过分别查好几个工具。"
                        + "注意：平台目前没有按时间分区的留存/增长数据，"
                        + "所有指标都是累计值，写结论时不要把它描述成「本周新增」。")
                .access(Access.ADMIN)
                .executor((call, user) -> {
                    adminService.checkAdmin(user);

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("metrics", adminService.getDashboard());
                    List<Map<String, Object>> heat = adminService.getAnimeHeatRanking(10);
                    ToolViews.tagAnimeRows(heat);
                    out.put("heatRanking", heat);

                    out.put("reviewSample", reviewSample(15));

                    Map<String, Object> users = summarizeUsers(adminService.getUserList());
                    out.put("userBreakdown", users);

                    out.put("caveat", "以上均为累计口径，不含时间维度（无日增/周增/留存数据）。"
                            + "给出结论时请明确这是累计快照。");
                    return out;
                })
                .build();
    }

    /**
     * 评论样本: 最新的 N 条 + **真实的**总数.
     *
     * <p>改前这两处调的是 {@code adminService.getAllReviews()} —— 它把整张 review 表
     * 读进 JVM 再截前 30 / 15 条, 而报出去的 {@code total} 是 {@code all.size()}:
     * 那个数**只在取全表时才等于总数**。现在底层换成管理端分页那条, 总数由独立的
     * count 查询给, 于是"一共有多少条"与"这次给了几条"第一次成为两件互不牵连的事。
     *
     * <p><b>为什么这两处可以走分页, 而 {@code list_users} 那两处不行。</b>
     * 用户那两个工具要的是**全量构成**({@code byRole}/{@code byStatus} 是全体用户的
     * 统计), 指到分页上会静默变成"最新 30 个人的构成"; 而这两个工具要的只是
     * "最新 N 条 + 一个总数", 分页那条**恰好**就是它们要的 —— 语义一个字都不变,
     * 少读的是一整张表。同一条判断在 {@code AdminService.getUserList} 的注释里
     * 写的是另一半(为什么那个方法留着)。
     *
     * <p>返回的三个键({@code total} / {@code returned} / {@code list})与改前逐字相同,
     * 所以消费这两处的提示词与卡片渲染都不用动。
     */
    private Map<String, Object> reviewSample(int limit) {
        // 四个 null 依次是 keyword / rating / sort / order —— 全给 null 表示
        // "不筛 + 后端的默认序(主键倒序, 也就是最新在前)". 刻意不在这里写死 "id"/"desc":
        // 那是 AdminService 的私有常量, 抄一份过来就是同一个约定的第二处定义。
        Map<String, Object> page = adminService.getReviewPage(null, null, null, null, 1, limit);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("list");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", page.get("total"));
        // returned 与 list 的长度恒等: excerpt 只截**正文**, 一行都不丢
        out.put("returned", rows.size());
        out.put("list", excerpt(rows, limit));
        return out;
    }

    /** 截断评论正文, 只保留样本量的内容 */
    private List<Map<String, Object>> excerpt(List<Map<String, Object>> reviews, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : reviews.subList(0, Math.min(reviews.size(), limit))) {
            Map<String, Object> item = new LinkedHashMap<>(r);
            Object content = item.get("content");
            if (content instanceof String s && s.length() > REVIEW_EXCERPT) {
                item.put("content", s.substring(0, REVIEW_EXCERPT) + "…");
            }
            out.add(item);
        }
        return out;
    }

    /** 用户构成: 只统计, 不列明细 */
    private Map<String, Object> summarizeUsers(List<Map<String, Object>> users) {
        Map<String, Integer> byRole = new LinkedHashMap<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> u : users) {
            byRole.merge(String.valueOf(u.get("role")), 1, Integer::sum);
            byStatus.merge(String.valueOf(u.get("status")), 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", users.size());
        out.put("byRole", byRole);
        out.put("byStatus", byStatus);
        return out;
    }
}
