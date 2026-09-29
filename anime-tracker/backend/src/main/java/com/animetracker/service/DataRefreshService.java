package com.animetracker.service;

import com.animetracker.dto.BangumiDTO.*;
import com.animetracker.repository.AnimeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 定时增量刷新动漫数据.
 *
 * 每小时从 Bangumi API 拉取:
 *   1. 当季新番 (当前年份+月份关键词)
 *   2. 热门排行增量
 *   3. 正在放送的番剧更新
 *
 * 所有操作仅追加/更新，不删除已有数据。
 *
 * <p>这里原本还有一份自己的 {@code toAnime} / {@code mergeUpdate} 映射副本,
 * 现在全部改走 {@link AnimeService#upsertAnime} —— 落库映射只留一处.
 * 原因见那个方法的注释: season/status/rank 三个字段正是因为映射有四份、
 * 要改四处而一处都没改, 结果 470 行数据全是 NULL, 而按这三个字段筛选的
 * 接口一直对外开着, 谁都不报错.
 */
@Service
public class DataRefreshService {

    private static final Logger log = LoggerFactory.getLogger(DataRefreshService.class);

    private final BangumiApiClient apiClient;
    private final AnimeRepository animeRepo;
    private final AnimeService animeService;

    public DataRefreshService(BangumiApiClient apiClient, AnimeRepository animeRepo, AnimeService animeService) {
        this.apiClient = apiClient;
        this.animeRepo = animeRepo;
        this.animeService = animeService;
    }

    /**
     * 每小时第7分钟执行 (错开整点高峰).
     * 首次启动后延迟60秒再执行, 等CachePreloader跑完.
     */
    @CacheEvict(value = {"ranking", "latest", "tags", "calendar"}, allEntries = true)
    @Scheduled(initialDelay = 60_000, fixedRate = 3_600_000)
    public void refreshIncremental() {
        long before = animeRepo.count();
        int added = 0;
        int updated = 0;

        log.info("[定时刷新] 开始, 当前 {} 条", before);

        // 1. 当季关键词搜索
        String[] seasonal = buildSeasonalKeywords();
        for (String kw : seasonal) {
            try {
                SearchResponse resp = apiClient.searchSubjects(kw, 1, 15);
                if (resp != null && resp.getData() != null) {
                    for (SubjectDTO dto : resp.getData()) {
                        if (dto.getId() == null) continue;
                        // 先记下有没有, 只为了统计新增/更新这两个计数 ——
                        // 落库本身统一走 upsertAnime (见该类顶部注释)
                        boolean existed = animeRepo.existsById(dto.getId());
                        animeService.upsertAnime(dto);
                        if (existed) updated++; else added++;
                    }
                }
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[定时刷新] 被中断, 这一轮提前结束(当季关键词)");
                return;
            } catch (Exception e) {
                // 这里原本是个空 catch. 空 catch 的问题不是"丢了异常", 而是
                // "刷新一直在失败"和"刷新一直很正常"在日志上长得一模一样 ——
                // 而这两件事的处理方式完全相反.
                // 用 e.toString() 而不是 e.getMessage(): 后者的值可以是 null,
                // 那时这行日志就只剩"跳过: null", 等于白打. 也不打整份栈:
                // 这段在循环里, 32 个关键词各一份栈会把它淹掉. 真要查栈,
                // 异常类名+消息足够定位到是哪一类失败.
                log.warn("当季关键词 '{}' 搜索失败, 跳过: {}", kw, e.toString());
            }
        }

        // 2. 热门排行前3页
        try {
            for (int page = 1; page <= 2; page++) {
                SearchResponse rankResp = apiClient.searchSubjects("", page, 20);
                if (rankResp != null && rankResp.getData() != null) {
                    for (SubjectDTO dto : rankResp.getData()) {
                        if (dto.getId() == null) continue;
                        // 这一趟是"按名次浏览", 返回的 rating.rank 就是真实的榜单名次,
                        // 也是 rank 这一列唯一的数据来源 —— 所以这里对已存在的行
                        // 也要刷新(以前只插新的, 老数据的 rank 永远是 null).
                        boolean existed = animeRepo.existsById(dto.getId());
                        animeService.upsertAnime(dto);
                        if (existed) updated++; else added++;
                    }
                    if (rankResp.getData().size() < 20) break;
                }
                Thread.sleep(600);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[定时刷新] 被中断, 这一轮提前结束(热门排行)");
            return;
        } catch (Exception e) {
            // 排行榜这一段是 rank 这一列唯一的数据来源(见上面的注释), 它整段失败
            // 意味着榜单名次这一轮没有更新. 以前这里也是空 catch.
            log.warn("热门排行刷新失败, 跳过: {}", e.toString());
        }

        // 3. 日历接口: 拉取全部在播番剧的完整详情
        try {
            var calDays = apiClient.getCalendar();
            if (calDays != null) {
                int calAdded = 0;
                for (var day : calDays) {
                    if (day.getItems() == null) continue;
                    for (var item : day.getItems()) {
                        // 和上面那句 day.getItems() == null 一样是防外部数据:
                        // 日历是第三方 JSON, 数组里出现 null 元素不该让整段刷新停摆.
                        // (下面那个 catch 要打 item.getId(), 它自己先撞 null 的话
                        //  就会把异常抛到外层, 反而变成"一个坏条目废掉整轮日历".)
                        if (item == null) continue;
                        try {
                            if (animeRepo.existsById(item.getId())) {
                                // 已有的行: 只用日历这一条记录刷新(air_date / rank /
                                // "正在播"状态). 以前这里是直接跳过, 代价是老数据
                                // 永远停在什么都没有的状态 —— 实测线上 470 行里
                                // 145 行的 date 是 NULL, 而 date 为空会让 season 和
                                // status 一起推不出来.
                                animeService.upsertCalendarItem(item);
                                updated++;
                            } else {
                                // 新条目先取一次详情(要简介、集数、标签这些日历不给的字段),
                                // 再用日历条目的权威在播标记覆盖一次 status
                                var detail = apiClient.getSubjectDetail(item.getId());
                                if (detail != null && detail.getId() != null) {
                                    animeService.upsertAnime(detail);
                                    animeService.upsertCalendarItem(item);
                                    added++; calAdded++;
                                }
                                Thread.sleep(300); // 礼貌限速
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            log.warn("[定时刷新] 被中断, 这一轮提前结束(日历)");
                            return;
                        } catch (Exception ex) {
                            // 单条日历条目失败不该拖垮整轮, 但也不能像以前那样一声不吭:
                            // 这一条会连带丢掉它的简介/集数, 而失败的是"某几条"还是
                            // "每一条", 只有日志能回答.
                            log.warn("日历条目 id={} 刷新失败, 跳过: {}",
                                    item.getId(), ex.toString());
                        }
                    }
                }
                if (calAdded > 0) log.info("  日历补充: +{} 条当季新番", calAdded);
            }
        } catch (Exception e) {
            // 整段日历拉不到: 这一轮的在播状态/air_date 都不会更新. 原来只按 debug 记录,
            // 而生产日志级别是 info —— 也就是说这件事在线上是完全不可见的.
            log.warn("日历刷新失败, 跳过: {}", e.toString());
        }

        log.info("[定时刷新] 完成. 新增 {} 条, 更新 {} 条, 共 {} 条",
                added, updated, animeRepo.count());
    }

    /** 每天凌晨4点全量更新当年所有番剧 */
    @Scheduled(cron = "0 17 4 * * *")
    public void refreshDailyFull() {
        String year = String.valueOf(java.time.Year.now().getValue());
        log.info("[每日全量刷新] 开始, 年份={}", year);

        int added = 0;
        for (int page = 1; page <= 10; page++) {
            try {
                SearchResponse resp = apiClient.searchSubjects(year, page, 20);
                if (resp == null || resp.getData() == null || resp.getData().isEmpty()) break;
                for (SubjectDTO dto : resp.getData()) {
                    if (dto.getId() == null) continue;
                    boolean existed = animeRepo.existsById(dto.getId());
                    animeService.upsertAnime(dto);
                    if (!existed) added++;
                }
                Thread.sleep(600);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[每日全量刷新] 被中断, 提前结束");
                return;
            } catch (Exception e) {
                // 这里原来是 break: 一页失败就当"没有更多数据了"收工. 全量刷新
                // 少刷一页是能被下一轮补上的, 所以继续 break 没错, 但得留下痕迹 ——
                // 否则"只刷了 3 页就结束"和"确实只有 3 页"同样不可分辨.
                log.warn("[每日全量刷新] 第 {} 页拉取失败, 停止本轮: {}", page, e.toString());
                break;
            }
        }
        log.info("[每日全量刷新] 完成, 新增 {} 条, 共 {} 条", added, animeRepo.count());
    }

    // ── helpers ──

    private String[] buildSeasonalKeywords() {
        LocalDateTime now = LocalDateTime.now();
        int year = now.getYear();
        int month = now.getMonthValue();
        // 当季 + 前后各一个季度
        List<String> kws = new java.util.ArrayList<>();
        for (int m : new int[]{month - 3, month, month + 3}) {
            if (m < 1) { kws.add((year - 1) + "年"); }
            else if (m > 12) { kws.add((year + 1) + "年"); }
            else { kws.add(year + "年"); }
        }
        kws.add(String.valueOf(year));
        kws.add("新番");
        kws.add("剧场版");
        return kws.toArray(new String[0]);
    }

}
