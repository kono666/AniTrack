package com.animetracker.agent.tool;

import com.animetracker.agent.tool.ToolDefinition.Access;
import com.animetracker.dto.RequestDTO.TrackRequest;
import com.animetracker.entity.AnimeTracking;
import com.animetracker.entity.User;
import com.animetracker.service.StatsService;
import com.animetracker.service.TrackService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 追番管理类工具.
 *
 * 注意所有执行器的签名都是 (call, user), 没有一处从 call 里读 userId ——
 * 当前用户由服务端从登录态注入. 模型无法指定「操作哪个用户的数据」.
 */
@Component
public class TrackingTools implements ToolProvider {

    /**
     * 这份清单不再在这里维护, 直接引用 TrackRequest 那一份.
     *
     * <p>网页接口和 Agent 工具认的必须是同一组状态: 两个地方各存一份的话,
     * 迟早有一边被改漏, 表现是「同一个值走 Agent 被拒、走 HTTP 收下」(或反过来),
     * 存进去的数据统计口径对不上, 而且不会有任何一处报错.
     */
    private static final List<String> STATUSES = TrackRequest.STATUSES;

    private final TrackService trackService;
    private final StatsService statsService;

    public TrackingTools(TrackService trackService, StatsService statsService) {
        this.trackService = trackService;
        this.statsService = statsService;
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(listTracking(), saveTracking(), removeTracking(),
                toggleEpisode(), myStats());
    }

    private ToolDefinition listTracking() {
        return ToolDefinition.builder()
                .name("list_my_tracking")
                .description("查看当前用户自己的追番列表。用户问「我在追什么」「我的追番进度」时使用。"
                        + "只能查到自己当次登录账号的数据。")
                .access(Access.USER)
                .executor((call, user) -> {
                    requireLogin(user);
                    return Map.of("list", trackService.getUserTrackings(user));
                })
                .build();
    }

    private ToolDefinition saveTracking() {
        return ToolDefinition.builder()
                .name("add_or_update_tracking")
                .description("把某部番剧加入追番列表，或更新已有的追番状态与进度。"
                        + "调用前必须先用 search_anime 确认是哪一部作品，同名番剧很常见，不要凭猜测传 id。")
                .access(Access.USER)
                .intParam("subjectId", "番剧 id，必须来自 search_anime 等工具的返回结果", true)
                .enumParam("status", "追番状态。不填则保持原值；这部番还没进过追番列表时不填按「想看」建一条",
                        false, STATUSES.toArray(String[]::new))
                .intParam("progress", "已看到第几集。不填则保持原值", false)
                .intParam("score", "个人评分 1-10。不填则保持原值，要清掉已打的评分才填 0", false)
                .stringParam("notes", "个人备注。不填则保持原值", false)
                .executor((call, user) -> {
                    requireLogin(user);
                    // 缺席 = 别碰这个字段, 所以默认值只能是 null —— 不能拿 "" 当缺席的替身:
                    // 传了键就得是个合法值, 空串在 DTO 那边会被 @Pattern 拒掉.
                    // status 改前是必填, 于是「把进度改成 5」这个动作一定会顺带替你决定一次状态,
                    // 助手只能按自己的理解挑一个(常挑 watching), 用户看到的是"我就改了个进度,
                    // 它怎么自己变成在看了".
                    String status = call.str("status", null);
                    if (status != null && !STATUSES.contains(status)) {
                        throw new IllegalArgumentException(
                                "status 只能是 " + STATUSES + " 之一，收到的是「" + status + "」");
                    }

                    TrackRequest req = new TrackRequest();
                    req.setSubjectId(call.requireInteger("subjectId"));
                    req.setStatus(status);
                    req.setProgress(call.integerOrNull("progress"));
                    req.setScore(call.integerOrNull("score"));
                    req.setNotes(call.str("notes", null));

                    AnimeTracking saved = trackService.saveTracking(user, req);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("subjectId", saved.getSubjectId());
                    out.put("status", saved.getStatus());
                    out.put("progress", saved.getProgress());
                    out.put("score", saved.getScore());
                    return out;
                })
                .build();
    }

    private ToolDefinition removeTracking() {
        return ToolDefinition.builder()
                .name("remove_tracking")
                .description("把某部番剧从追番列表里移除。这是删除操作，"
                        + "务必先用文字向用户确认「确定要移除《XX》吗」，得到肯定答复后再调用。")
                .access(Access.USER)
                .intParam("subjectId", "要移除的番剧 id", true)
                .executor((call, user) -> {
                    requireLogin(user);
                    Integer id = call.requireInteger("subjectId");
                    trackService.deleteTracking(user, id);
                    return Map.of("subjectId", id, "removed", true);
                })
                .build();
    }

    private ToolDefinition toggleEpisode() {
        return ToolDefinition.builder()
                .name("toggle_episode_watched")
                .description("把某一集标记为看过，或取消标记。用户说「第3集看完了」「把第3集取消」时使用。"
                        + "这个操作是切换语义：已标记会取消，未标记会标记。")
                .access(Access.USER)
                .intParam("animeId", "番剧 id", true)
                .intParam("episodeNum", "第几集", true)
                .executor((call, user) -> {
                    requireLogin(user);
                    Integer animeId = call.requireInteger("animeId");
                    Integer ep = call.requireInteger("episodeNum");
                    boolean watched = statsService.toggleEpisode(user, animeId, ep);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("animeId", animeId);
                    out.put("episodeNum", ep);
                    out.put("watched", watched);
                    out.put("message", watched ? "已标记为看过" : "已取消看过标记");
                    return out;
                })
                .build();
    }

    private ToolDefinition myStats() {
        return ToolDefinition.builder()
                .name("get_my_stats")
                .description("查看当前用户的追番总览：各状态数量、总集数、评论数、平均分、以及偏好的题材分布。"
                        + "用户问「我的追番数据」「我看得最多的是什么类型」时使用。")
                .access(Access.USER)
                .executor((call, user) -> {
                    requireLogin(user);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("counts", trackService.getUserStats(user));
                    out.put("overall", statsService.getOverallStats(user));
                    out.put("topGenres", topGenres(statsService.getGenreDistribution(user), 5));
                    return out;
                })
                .build();
    }

    /** 题材分布只取前几名, 避免把几十个题材全灌给模型 */
    private Map<String, Integer> topGenres(Map<String, Integer> dist, int n) {
        Map<String, Integer> out = new LinkedHashMap<>();
        dist.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private void requireLogin(User user) {
        if (user == null) {
            throw new IllegalStateException("这个操作需要先登录");
        }
    }
}
