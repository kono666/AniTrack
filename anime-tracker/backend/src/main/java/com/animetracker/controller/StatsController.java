package com.animetracker.controller;

import com.animetracker.config.CurrentUser;
import com.animetracker.dto.ApiResponse;
import com.animetracker.entity.User;
import com.animetracker.service.StatsService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** 类型分布 */
    @GetMapping("/genre-distribution")
    public ApiResponse<Map<String, Integer>> getGenreDistribution(@CurrentUser User user) {
        return ApiResponse.success(statsService.getGenreDistribution(user));
    }

    /** 评分分布 */
    @GetMapping("/score-distribution")
    public ApiResponse<int[]> getScoreDistribution(@CurrentUser User user) {
        return ApiResponse.success(statsService.getScoreDistribution(user));
    }

    /** 整体统计 */
    @GetMapping("/overall")
    public ApiResponse<Map<String, Object>> getOverallStats(@CurrentUser User user) {
        return ApiResponse.success(statsService.getOverallStats(user));
    }

    /** 最近活动 */
    @GetMapping("/recent-activity")
    public ApiResponse<List<Map<String, Object>>> getRecentActivity(@CurrentUser User user) {
        return ApiResponse.success(statsService.getRecentActivity(user));
    }

    /** 获取已看剧集 */
    @GetMapping("/watched-episodes")
    public ApiResponse<List<Integer>> getWatchedEpisodes(
            @CurrentUser User user,
            @RequestParam Integer animeId) {
        return ApiResponse.success(statsService.getWatchedEpisodes(user, animeId));
    }

    /** 切换剧集状态 */
    @PostMapping("/toggle-episode")
    public ApiResponse<Map<String, Object>> toggleEpisode(
            @CurrentUser User user,
            @RequestParam Integer animeId,
            @RequestParam Integer episodeNum) {
        boolean watched = statsService.toggleEpisode(user, animeId, episodeNum);
        Map<String, Object> data = new HashMap<>();
        data.put("watched", watched);
        data.put("episodeNum", episodeNum);
        return ApiResponse.success(watched ? "已标记为看过" : "已取消标记", data);
    }

    /** 番剧热度（公开） */
    @GetMapping("/anime-heat")
    public ApiResponse<Map<String, Object>> getAnimeHeat(@RequestParam Integer animeId) {
        return ApiResponse.success(statsService.getAnimeHeat(animeId));
    }
}
