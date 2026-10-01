package com.animetracker.service;

import com.animetracker.entity.*;
import com.animetracker.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class StatsService {

    /** 首页「最近活动」展示的条数. 取数下推到数据库, 不在这里 limit. */
    private static final int RECENT_ACTIVITY_LIMIT = 10;

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

    /**
     * 用户类型分布.
     *
     * <p>番剧信息一次 {@code findAllById} 取回. 原来的注释同样是「修复N+1」那句,
     * 而下面逐条 findById 的循环正是它声称已经修掉的东西 —— 与
     * {@link TrackService#getUserTrackings} 是同一份注释、同一个坑.
     */
    public Map<String, Integer> getGenreDistribution(User user) {
        List<AnimeTracking> trackings = trackingRepo.findByUserOrderByUpdatedAtDesc(user);
        Map<String, Integer> genreCount = new LinkedHashMap<>();

        Set<Integer> subjectIds = new HashSet<>();
        for (AnimeTracking t : trackings) {
            subjectIds.add(t.getSubjectId());
        }
        Map<Integer, Anime> animeMap = new HashMap<>();
        animeRepo.findAllById(subjectIds).forEach(a -> animeMap.put(a.getId(), a));

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

    /**
     * 最近活动.
     *
     * <p>两条查询: 一条取最近 {@value #RECENT_ACTIVITY_LIMIT} 条追番, 一条把这 10 部的
     * 番剧信息一次取回. 改之前是「先把该用户的<b>全部</b>追番查出来再 {@code limit(10)}」
     * 加上「在这 10 条里逐条 findById」:
     *
     * <ul>
     *   <li>前者让首页这一块的开销随追番总数增长 —— 追番 500 部的人, 打开首页要
     *       先把 500 行读进内存, 再丢掉 490 行. 分页下推到数据库(Pageable)之后,
     *       读进来的就是 10 行;</li>
     *   <li>后者是 N+1 的老样子, 只不过 N 被 limit 压到了 10, 所以更不容易被注意到.</li>
     * </ul>
     */
    public List<Map<String, Object>> getRecentActivity(User user) {
        List<AnimeTracking> recent = trackingRepo.findByUserOrderByUpdatedAtDesc(
                user, PageRequest.of(0, RECENT_ACTIVITY_LIMIT));

        Map<Integer, Anime> animeMap = new HashMap<>();
        animeRepo.findAllById(recent.stream().map(AnimeTracking::getSubjectId).distinct().toList())
                .forEach(a -> animeMap.put(a.getId(), a));

        List<Map<String, Object>> activity = new ArrayList<>();
        for (AnimeTracking t : recent) {
            Map<String, Object> item = new HashMap<>();
            item.put("type", "tracking");
            item.put("status", t.getStatus());
            item.put("subjectId", t.getSubjectId());
            item.put("progress", t.getProgress());
            item.put("score", t.getScore());
            item.put("time", t.getUpdatedAt());
            Anime a = animeMap.get(t.getSubjectId());
            if (a != null) {
                item.put("animeTitle", a.getTitleCn() != null ? a.getTitleCn() : a.getTitle());
            }
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
        stats.put("totalReviews", reviewRepo.findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(user).size());
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
     *
     * <p>打勾成功之后还会顺带把追番进度往前推(没有追番记录就建一条), 见
     * {@link #syncProgressOnWatched}. 那一步也有它自己的代价, 写在那个方法的注释里.
     */
    public boolean toggleEpisode(User user, Integer animeId, Integer episodeNum) {
        if (epWatchedRepo.existsByUserAndAnimeIdAndEpisodeNum(user, animeId, episodeNum)) {
            epWatchedRepo.deleteByUserAndAnimeIdAndEpisodeNum(user, animeId, episodeNum);
            // 取消打勾**不动**追番进度 —— 理由见 syncProgressOnWatched 的「只往前推」一节.
            return false;
        }
        try {
            isolatedInsert.attempt(() -> epWatchedRepo.saveAndFlush(EpisodeWatched.builder()
                    .user(user).animeId(animeId).episodeNum(episodeNum).build()));
        } catch (DataIntegrityViolationException e) {
            // 只认「确实已经有一行」这一种情况; 查不到说明炸的是别的约束(比如 user_id 外键),
            // 那就不是并发落败, 原样抛出, 别把真问题吞成一次「打勾成功」
            if (!epWatchedRepo.existsByUserAndAnimeIdAndEpisodeNum(user, animeId, episodeNum)) {
                throw e;
            }
            // 并发落败(对手刚插进同一行)也要**落到下面那一句**去同步进度, 不能在这里
            // return true 了事: 两边都推, 进度才与「谁先提交」无关. 抽方法时最容易漏这里.
        }
        syncProgressOnWatched(user, animeId, episodeNum);
        return true;
    }

    /**
     * 打勾之后把追番进度往前推; 这个用户对这部番还没有追番记录, 就顺手建一条.
     *
     * <b>只往前推, 不后退</b>
     *
     * <p>progress 是「看到第几集」的水位线, 所以取 {@code max(现值, 这一集)}. 做成
     * 「progress = 打过勾的最大集号」看着更「派生」, 却会吃掉历史数据: 一个进度 12 集、
     * 却一集都没点过勾的人(进度是详情页那个数字框手输的), 第一次打勾就会从 12 掉到 3,
     * 界面上像是丢了数据. 取 max 还顺带解决两件事 —— 取消打勾不需要重算「剩下最大的
     * 集号」, 乱序打勾(先点 5 再点 3)也不会把进度拽回去.
     *
     * <b>没有记录就建一条 watching</b>
     *
     * <p>打卡是这个站上「我在看这部番」最直接的表达. 不建记录的话, 用户点完 12 个格子
     * 回首页仍然看不到这部番 —— 而首页那块「继续看」正是靠 watching 行撑起来的.
     * status 只在**本来没有记录**时才写: 一个已经把某番标成「想看」的人打了勾, 它仍然
     * 是「想看」. 替用户改掉他显式设过的状态, 比留一个看着别扭的组合更糟.
     *
     * <b>插入套 IsolatedInsert, 更新刻意不套</b>
     *
     * <p>插入那条路会撞 (user_id, subject_id) 唯一约束, 与上面插 episode_watched 是同一个
     * 理由, 所以同样要在独立事务里做, 失败了才能安全地重查. <b>更新那条路留在外层事务</b>:
     * Agent 工具调用那条路上外层就是本方法所在的事务, 给同一个实体的写再套一层
     * REQUIRES_NEW 就是自己等自己. 「为了对称把两个分支包成一样」正是这里最容易犯的错.
     *
     * <b>代价: 打勾与推进度是两次写, 不是一次</b>
     *
     * <p>本方法与 {@link #toggleEpisode} 都没有 {@code @Transactional}, 于是
     * episode_watched 的插入在独立事务里先提交, 而这里的更新跟着外层走. 中间任何一步
     * 失败都会留下「勾打上了、进度没推」—— 反过来的情况不可能, 是顺序决定的. 网页上
     * 用户看到失败会再点一次, 而第二次点是**取消**, 所以前端必须把失败说出来(见
     * AnimeDetail.vue 的 toggleEp); Agent 那条路上外层回滚会让两者分叉. 要做成原子的
     * 就得把两次写绑进同一个事务, 而那与上面「更新不能独立事务」直接冲突 —— 这一轮
     * 接受这个中间态.
     */
    private void syncProgressOnWatched(User user, Integer animeId, Integer episodeNum) {
        // 特番的集号会被归一成 0(见 AnimeService#toEpisodeEntity: dto.getEp() 为空即 0),
        // 而「第 0 集」不代表看到了哪里. 没有记录时替它建一条 progress=0 的 watching, 只会在
        // 首页继续看里多出一条永远停在「第 0 集」的假条目; 有记录时 max(progress, 0) 本来就是
        // 空操作, 所以整个跳过即可.
        if (episodeNum == null || episodeNum <= 0) return;

        AnimeTracking track = trackingRepo.findByUserAndSubjectId(user, animeId).orElse(null);
        if (track == null) {
            try {
                isolatedInsert.attempt(() -> trackingRepo.saveAndFlush(AnimeTracking.builder()
                        .user(user).subjectId(animeId).status("watching").progress(episodeNum)
                        .build()));
                // 新建的那一行 progress 已经是这一集, 没有要推的东西
                return;
            } catch (DataIntegrityViolationException e) {
                // 并发对手抢先建了同一条. 重查回来接着往下推, 不把异常抛给用户.
                track = trackingRepo.findByUserAndSubjectId(user, animeId).orElse(null);
                // 仍查不到, 说明这次冲突与 (user_id, subject_id) 无关, 原样抛出别掩盖
                if (track == null) throw e;
            }
        }
        // progress 在库里是 NOT NULL(见 AnimeTracking), 不需要判空
        if (track.getProgress() < episodeNum) {
            track.setProgress(episodeNum);
            trackingRepo.save(track);
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
