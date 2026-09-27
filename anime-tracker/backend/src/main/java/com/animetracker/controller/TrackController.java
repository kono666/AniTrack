package com.animetracker.controller;

import com.animetracker.config.CurrentUser;
import com.animetracker.dto.ApiResponse;
import com.animetracker.dto.RequestDTO.TrackRequest;
import com.animetracker.entity.AnimeTracking;
import com.animetracker.entity.User;
import com.animetracker.service.TrackService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/track")
public class TrackController {

    private final TrackService trackService;

    public TrackController(TrackService trackService) {
        this.trackService = trackService;
    }

    /** 添加或更新追番 */
    @PostMapping
    public ApiResponse<Map<String, Object>> saveTracking(
            @CurrentUser User user,
            @Valid @RequestBody TrackRequest req) {
        AnimeTracking track = trackService.saveTracking(user, req);
        Map<String, Object> data = new HashMap<>();
        data.put("id", track.getId());
        data.put("status", track.getStatus());
        data.put("progress", track.getProgress());
        data.put("score", track.getScore());
        return ApiResponse.success("追番记录已保存", data);
    }

    /** 删除追番 */
    @DeleteMapping
    public ApiResponse<Void> deleteTracking(
            @CurrentUser User user,
            @RequestParam Integer subjectId) {
        trackService.deleteTracking(user, subjectId);
        return ApiResponse.success("已取消追番", null);
    }

    /** 获取用户追番列表 */
    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> getUserTrackings(@CurrentUser User user) {
        return ApiResponse.success(trackService.getUserTrackings(user));
    }

    /** 获取用户对某番剧的追番状态 */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getTrackingStatus(
            @CurrentUser User user,
            @RequestParam Integer subjectId) {
        return ApiResponse.success(trackService.getTrackingStatus(user, subjectId));
    }

    /** 用户追番统计 */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getUserStats(@CurrentUser User user) {
        return ApiResponse.success(trackService.getUserStats(user));
    }
}
