package com.animetracker.service;

import com.animetracker.dto.RequestDTO.TrackRequest;
import com.animetracker.entity.Anime;
import com.animetracker.entity.AnimeTracking;
import com.animetracker.entity.User;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.TrackingRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class TrackService {

    private final TrackingRepository trackingRepository;
    private final AnimeRepository animeRepository;
    private final IsolatedInsert isolatedInsert;

    public TrackService(TrackingRepository trackingRepository,
                        AnimeRepository animeRepository,
                        IsolatedInsert isolatedInsert) {
        this.trackingRepository = trackingRepository;
        this.animeRepository = animeRepository;
        this.isolatedInsert = isolatedInsert;
    }

    /**
     * 添加或更新追番记录.
     *
     * <b>为什么不能只写「先查后插」</b>
     *
     * 查不到就插, 两个请求同时走这条路(双击、前端重试、多标签页)就会落下两行.
     * 重复行的后果不是「多一条数据」这么轻: 该用户之后每次调
     * findByUserAndSubjectId 都会抛 IncorrectResultSizeDataAccessException,
     * 相关接口从此**永久 500 且不自愈** —— 数据躺在库里, 重启也没用.
     *
     * 真正的防线是 anime_tracking 上的唯一约束(V3 迁移建的). 这里做的是它的配套:
     * 让并发落败的那个请求不要以 500 收场, 而是重查一次、改成更新,
     * 对外表现与「它后到」完全一致.
     *
     * <b>插入为什么套一层 IsolatedInsert</b>
     *
     * 冲突会毒化「当前事务」, 而当前事务未必是我们的 —— Agent 工具调用那条路上,
     * 外面套着 ToolTransactionRunner 开的事务. 把插入单独放进一个 REQUIRES_NEW 事务,
     * 它失败只回滚它自己, 外层不受牵连, catch 之后的重查与改写才有意义.
     * 详细理由见 {@link IsolatedInsert}.
     *
     * 另一个办法是给本方法加 @Transactional, 但那样只会更糟: 事务边界套在 catch 外面,
     * 冲突直接把整个方法的事务标记成 rollback-only, 连「重查一次」都救不回来.
     *
     * 代价是「查 + 写」不再是一个原子步骤. 这里丢的是后写覆盖先写的旧值,
     * 与加锁前相比没有变差 —— 但重复行从此不可能出现, 那才是要命的那件事.
     */
    public AnimeTracking saveTracking(User user, TrackRequest req) {
        Optional<AnimeTracking> existing = trackingRepository.findByUserAndSubjectId(user, req.getSubjectId());
        if (existing.isPresent()) {
            return applyAndSave(existing.get(), req);
        }
        try {
            return isolatedInsert.attempt(() -> applyAndSave(AnimeTracking.builder()
                    .user(user)
                    .subjectId(req.getSubjectId())
                    .build(), req));
        } catch (DataIntegrityViolationException e) {
            // 并发对手抢先插了同一行. 重查回来更新它, 不把 500 抛给用户.
            AnimeTracking winner = trackingRepository.findByUserAndSubjectId(user, req.getSubjectId())
                    // 仍查不到, 说明这次冲突与 (user_id, subject_id) 无关, 原样抛出别掩盖
                    .orElseThrow(() -> e);
            return applyAndSave(winner, req);
        }
    }

    /**
     * 把请求字段落到实体上并落库.
     *
     * 用 saveAndFlush 而不是 save: 三个实体的主键都是 IDENTITY, 现在 save 也会立刻发
     * INSERT, 但这是自增主键带来的巧合. saveAndFlush 把 flush 钉死在这次调用里,
     * 约束冲突必定在 try 块内抛出, 换个主键策略也不会悄悄失效.
     */
    private AnimeTracking applyAndSave(AnimeTracking track, TrackRequest req) {
        track.setStatus(req.getStatus());
        if (req.getProgress() != null) track.setProgress(req.getProgress());
        if (req.getScore() != null) track.setScore(req.getScore());
        if (req.getNotes() != null) track.setNotes(req.getNotes());
        return trackingRepository.saveAndFlush(track);
    }

    /** 删除追番记录 */
    public void deleteTracking(User user, Integer subjectId) {
        trackingRepository.findByUserAndSubjectId(user, subjectId)
                .ifPresent(trackingRepository::delete);
    }

    /**
     * 获取用户的追番列表(含番剧标题和封面).
     *
     * <p>番剧信息是<b>一次</b> {@code findAllById} 取回来的, 不是在循环里逐条 findById:
     * 追番 50 部就是 50 次数据库往返, 而这个接口就是追番页本身, 每打开一次付一遍.
     *
     * <p>这里原来的注释写着「批量查询番剧信息(修复N+1)」, 紧跟着的却是一个
     * 逐条 findById 的循环 —— 注释说对了该做什么, 代码没做. 这种注释比没有注释更坏:
     * 读到它的人会放心地把这段跳过. 现在注释与代码说的是同一件事, 并由
     * {@code QueryCountIntegrationTest} 按真实 SQL 条数钉住(不论追番多少条, 恒定 2 条).
     */
    public List<Map<String, Object>> getUserTrackings(User user) {
        List<AnimeTracking> trackings = trackingRepository.findByUserOrderByUpdatedAtDesc(user);
        List<Map<String, Object>> result = new ArrayList<>();

        // 一次取回全部番剧. 这里不需要为「一条追番都没有」挡一道: findAllById 收到
        // 空集合会直接返回空列表, 不会拼出 `WHERE id IN ()` 那种非法 SQL.
        Map<Integer, Anime> animeMap = new HashMap<>();
        animeRepository.findAllById(
                        trackings.stream().map(AnimeTracking::getSubjectId).distinct().toList())
                .forEach(a -> animeMap.put(a.getId(), a));

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
