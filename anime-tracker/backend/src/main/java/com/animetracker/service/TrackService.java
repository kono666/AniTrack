package com.animetracker.service;

import com.animetracker.dto.RequestDTO.TrackRequest;
import com.animetracker.entity.Anime;
import com.animetracker.entity.AnimeTracking;
import com.animetracker.entity.User;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.TrackingRepository;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class TrackService {

    private final TrackingRepository trackingRepository;
    private final AnimeRepository animeRepository;

    public TrackService(TrackingRepository trackingRepository,
                        AnimeRepository animeRepository) {
        this.trackingRepository = trackingRepository;
        this.animeRepository = animeRepository;
    }

    /** 添加或更新追番记录 */
    public AnimeTracking saveTracking(User user, TrackRequest req) {
        Optional<AnimeTracking> existing = trackingRepository.findByUserAndSubjectId(user, req.getSubjectId());

        AnimeTracking track;
        if (existing.isPresent()) {
            track = existing.get();
        } else {
            track = AnimeTracking.builder()
                    .user(user)
                    .subjectId(req.getSubjectId())
                    .build();
        }

        track.setStatus(req.getStatus());
        if (req.getProgress() != null) track.setProgress(req.getProgress());
        if (req.getScore() != null) track.setScore(req.getScore());
        if (req.getNotes() != null) track.setNotes(req.getNotes());

        return trackingRepository.save(track);
    }

    /** 删除追番记录 */
    public void deleteTracking(User user, Integer subjectId) {
        trackingRepository.findByUserAndSubjectId(user, subjectId)
                .ifPresent(trackingRepository::delete);
    }

    /** 获取用户的追番列表(含番剧标题和封面) */
    public List<Map<String, Object>> getUserTrackings(User user) {
        List<AnimeTracking> trackings = trackingRepository.findByUserOrderByUpdatedAtDesc(user);
        List<Map<String, Object>> result = new ArrayList<>();

        // 批量查询番剧信息(修复N+1)
        Map<Integer, Anime> animeMap = new HashMap<>();
        for (AnimeTracking t : trackings) {
            animeRepository.findById(t.getSubjectId()).ifPresent(a -> animeMap.put(t.getSubjectId(), a));
        }

        for (AnimeTracking t : trackings) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", t.getId());
            map.put("subjectId", t.getSubjectId());
            map.put("status", t.getStatus());
            map.put("progress", t.getProgress());
            map.put("score", t.getScore());
            map.put("notes", t.getNotes());
            map.put("createdAt", t.getCreatedAt());
            map.put("updatedAt", t.getUpdatedAt());

            Anime a = animeMap.get(t.getSubjectId());
            if (a != null) {
                map.put("animeTitle", a.getTitleCn() != null ? a.getTitleCn() : a.getTitle());
                map.put("animeCover", a.getCoverUrl());
                map.put("totalEpisodes", a.getTotalEpisodes());
            }

            result.add(map);
        }
        return result;
    }

    /** 获取用户对某番剧的追番状态 */
    public Map<String, Object> getTrackingStatus(User user, Integer subjectId) {
        Optional<AnimeTracking> track = trackingRepository.findByUserAndSubjectId(user, subjectId);
        Map<String, Object> result = new HashMap<>();
        result.put("tracked", track.isPresent());
        track.ifPresent(t -> {
            result.put("id", t.getId());
            result.put("status", t.getStatus());
            result.put("progress", t.getProgress());
            result.put("score", t.getScore());
            result.put("notes", t.getNotes());
        });
        return result;
    }

    /** 用户追番统计 */
    public Map<String, Object> getUserStats(User user) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("watching", trackingRepository.countByUserAndStatus(user, "watching"));
        stats.put("wantToWatch", trackingRepository.countByUserAndStatus(user, "want_to_watch"));
        stats.put("watched", trackingRepository.countByUserAndStatus(user, "watched"));
        stats.put("onHold", trackingRepository.countByUserAndStatus(user, "on_hold"));
        stats.put("dropped", trackingRepository.countByUserAndStatus(user, "dropped"));
        return stats;
    }
}
