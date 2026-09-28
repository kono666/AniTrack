package com.animetracker.service;

import com.animetracker.entity.*;
import com.animetracker.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class StatsService {

    private final TrackingRepository trackingRepo;
    private final AnimeRepository animeRepo;
    private final EpisodeWatchedRepository epWatchedRepo;
    private final ReviewRepository reviewRepo;
    private final IsolatedInsert isolatedInsert;

    public StatsService(TrackingRepository trackingRepo, AnimeRepository animeRepo,
                        EpisodeWatchedRepository epWatchedRepo, ReviewRepository reviewRepo,
                        IsolatedInsert isolatedInsert) {
        this.trackingRepo = trackingRepo;
        this.animeRepo = animeRepo;
        this.epWatchedRepo = epWatchedRepo;
        this.reviewRepo = reviewRepo;
        this.isolatedInsert = isolatedInsert;
    }

    /** 用户类型分布 */
    public Map<String, Integer> getGenreDistribution(User user) {
        List<AnimeTracking> trackings = trackingRepo.findByUserOrderByUpdatedAtDesc(user);
        Map<String, Integer> genreCount = new LinkedHashMap<>();

        // 批量查询番剧(修复N+1)
        Set<Integer> subjectIds = new HashSet<>();
        for (AnimeTracking t : trackings) {
            subjectIds.add(t.getSubjectId());
        }
        Map<Integer, Anime> animeMap = new HashMap<>();
        for (Integer sid : subjectIds) {
            animeRepo.findById(sid).ifPresent(a -> animeMap.put(sid, a));
        }

        for (AnimeTracking t : trackings) {
            Anime a = animeMap.get(t.getSubjectId());
            if (a != null && a.getTags() != null) {
                for (String tag : a.getTags().split(",")) {
                    String g = tag.trim();
                    if (!g.isEmpty()) genreCount.merge(g, 1, Integer::sum);
                }
            }
        }
        return genreCount;
    }

    /** 评分分布 */
    public int[] getScoreDistribution(User user) {
        List<AnimeTracking> trackings = trackingRepo.findByUserOrderByUpdatedAtDesc(user);
        int[] dist = new int[10];
        for (AnimeTracking t : trackings) {
            if (t.getScore() != null && t.getScore() >= 1 && t.getScore() <= 10) {
                dist[t.getScore() - 1]++;
            }
        }
        return dist;
    }

    /** 最近活动 */
    public List<Map<String, Object>> getRecentActivity(User user) {
        List<AnimeTracking> trackings = trackingRepo.findByUserOrderByUpdatedAtDesc(user);
        List<Map<String, Object>> activity = new ArrayList<>();

        for (AnimeTracking t : trackings.stream().limit(10).toList()) {
            Map<String, Object> item = new HashMap<>();
            item.put("type", "tracking");
            item.put("status", t.getStatus());
            item.put("subjectId", t.getSubjectId());
            item.put("progress", t.getProgress());
            item.put("score", t.getScore());
            item.put("time", t.getUpdatedAt());
            animeRepo.findById(t.getSubjectId()).ifPresent(a ->
                    item.put("animeTitle", a.getTitleCn() != null ? a.getTitleCn() : a.getTitle()));
            activity.add(item);
        }
        return activity;
    }

    /** 整体统计 */
    public Map<String, Object> getOverallStats(User user) {
        Map<String, Object> stats = new HashMap<>();
        List<AnimeTracking> all = trackingRepo.findByUserOrderByUpdatedAtDesc(user);

        stats.put("totalAnime", all.size());
        stats.put("totalEpisodes", epWatchedRepo.countByUser(user));
        stats.put("totalReviews", reviewRepo.findByUserOrderByCreatedAtDesc(user).size());
        double avg = all.stream().filter(t -> t.getScore() != null)
                .mapToInt(AnimeTracking::getScore).average().orElse(0);
        stats.put("avgScore", Math.round(avg * 10.0) / 10.0);

        return stats;
    }

    /** 获取用户在某番剧已看剧集 */
    public List<Integer> getWatchedEpisodes(User user, Integer animeId) {
        return epWatchedRepo.findByUserAndAnimeId(user, animeId).stream()
                .map(EpisodeWatched::getEpisodeNum).toList();
    }

    /**
     * 切换剧集观看状态.
     *
     * <b>这里的 @Transactional 被摘掉了, 说清原因和代价</b>
     *
     * episode_watched 上加了 (user_id, anime_id, episode_num) 唯一约束, 并发下两次
     * 「打勾」必有一次撞约束. 插入套一层 {@link IsolatedInsert}, 让冲突只回滚它自己 ——
     * 冲突会毒化当前事务, 而当前事务未必是我们的(Agent 工具调用外面套着
     * ToolTransactionRunner 的事务), 不隔离的话补救会被 UnexpectedRollbackException 吃掉.
     * 删除需要的事务边界移到了 EpisodeWatchedRepository 的那个派生删除方法上.
     *
     * 类上/方法上不加 @Transactional 也是同一个理由: 事务边界套在 catch 外面,
     * 冲突会把整个方法的事务标记成 rollback-only, 返回 true 也救不回来.
     *
     * 代价是「查 + 写」不再原子: 两个请求可能同时看到「未看过」. 但唯一约束保证最多插进
     * 一行, 落败的那个返回 true(已看过) —— 与「两个都点了打勾」该有的结果一致.
     */
    public boolean toggleEpisode(User user, Integer animeId, Integer episodeNum) {
        if (epWatchedRepo.existsByUserAndAnimeIdAndEpisodeNum(user, animeId, episodeNum)) {
            epWatchedRepo.deleteByUserAndAnimeIdAndEpisodeNum(user, animeId, episodeNum);
            return false;
        }
        try {
            isolatedInsert.attempt(() -> epWatchedRepo.saveAndFlush(EpisodeWatched.builder()
                    .user(user).animeId(animeId).episodeNum(episodeNum).build()));
            return true;
        } catch (DataIntegrityViolationException e) {
            // 只认「确实已经有一行」这一种情况; 查不到说明炸的是别的约束(比如 user_id 外键),
            // 那就不是并发落败, 原样抛出, 别把真问题吞成一次「打勾成功」
            if (epWatchedRepo.existsByUserAndAnimeIdAndEpisodeNum(user, animeId, episodeNum)) {
                return true;
            }
            throw e;
        }
    }

    /** 番剧热度统计 */
    public Map<String, Object> getAnimeHeat(Integer animeId) {
        Map<String, Object> heat = new HashMap<>();
        heat.put("watching", trackingRepo.countBySubjectIdAndStatus(animeId, "watching"));
        heat.put("wantToWatch", trackingRepo.countBySubjectIdAndStatus(animeId, "want_to_watch"));
        heat.put("watched", trackingRepo.countBySubjectIdAndStatus(animeId, "watched"));
        heat.put("onHold", trackingRepo.countBySubjectIdAndStatus(animeId, "on_hold"));
        heat.put("dropped", trackingRepo.countBySubjectIdAndStatus(animeId, "dropped"));
        heat.put("total", trackingRepo.countBySubjectId(animeId));
        return heat;
    }
}
