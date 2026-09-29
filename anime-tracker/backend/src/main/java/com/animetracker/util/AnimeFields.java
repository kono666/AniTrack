package com.animetracker.util;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * anime 表里三个"算出来的"字段: season / status / rank.
 *
 * <p>为什么要有这么一个单独的地方: 这三个字段在这之前**全代码库没有任何写入点**
 * (470 行全部是 NULL), 而读取它们的地方一直在按正常值工作 ——
 * `/api/bangumi/filter?season=` 永远返回空, rank 排序退化成不排序, 传给前端和
 * AI 工具的 JSON 里挂着 `"rank":null`. 谁都没报错, 功能就是坏的.
 *
 * <p>根因不是"忘了写", 是映射代码有四份: {@code AnimeService.toAnimeEntity}、
 * {@code CachePreloader.toAnime}、{@code DataRefreshService.toAnime} 和
 * {@code mergeUpdate}. 要加一个字段得改四处, 于是谁都没加全. 这次把映射收敛成
 * 一处({@code AnimeService.applySubject}), 推导逻辑收敛到这里 —— 纯函数,
 * 不碰数据库、不看系统时钟(today 由调用方传进来), 所以边界能被测试钉死.
 *
 * <p>口径: season 是 {@code yyyy-MM}(不是"2024冬"), 与实体上的注释、
 * 以及 AI 工具 {@code filter_anime} 对外的参数说明一致 —— 这两处已经是对外契约,
 * 改动它们会打断调用方, 所以这里跟随它们而不是另立一套.
 */
public final class AnimeFields {

    private AnimeFields() {
    }

    /** 状态取值. 与 getFilterMeta 提供给调用方的选项、以及实体的注释保持一致 */
    public static final String STATUS_AIRING = "airing";
    public static final String STATUS_FINISHED = "finished";

    /**
     * 总集数未知时, 按多少周估算放送长度.
     *
     * <p>26 周 ≈ 半年, 是"一季 + 一季"的常见长度. 这个数字只影响"未知长度"那一档
     * 的判断, 是估算而不是事实 —— 宁可写清楚它在估算, 也不要假装精确.
     */
    public static final int UNKNOWN_LENGTH_WEEKS = 26;

    /**
     * 估算的放送结束日之后再多给几周宽限.
     *
     * <p>集数与真实播出节奏对不上是常态(停播、总集篇、两季之间隔一季),
     * 宽限期让"刚播完"的番剧还能在放送中停留一阵子, 别在最后一集当天就翻成已完结.
     */
    public static final int GRACE_WEEKS = 2;

    /**
     * 匹配 {@code 2024-10-05} 与 {@code 2024-10}, 也接受不补零的 {@code 2024-1-5}.
     *
     * <p>只给年份的 {@code 2024} 刻意不匹配 —— 见 {@link #seasonOf}.
     */
    private static final Pattern DATE = Pattern.compile("^(\\d{4})-(\\d{1,2})(?:-(\\d{1,2}))?$");

    /**
     * 播出日期 → 季度 {@code yyyy-MM}.
     *
     * <p>三种情况返回 null(不猜):
     * <ul>
     *   <li>日期为 null / 空白 —— 库里 470 行有 145 行是这种, 它们的 date 从来没被写过;</li>
     *   <li>只有年份({@code 2024})—— 一个年份横跨四个季度, 挑哪个都是编的.
     *       编出来的危害比 null 大: 调用方筛 {@code 2024-04} 时会看到一部
     *       实际在 10 月播的番, 而且没有任何迹象表明这个值是猜的;</li>
     *   <li>月份越界或日期非法({@code 2024-13}, {@code 2024-02-31}),
     *       说明这一列的内容不可信, 不该参与筛选.</li>
     * </ul>
     *
     * <p>不补零的月份会被归一化成 {@code 2024-01}: 调用方按 {@code 2024-01} 去筛,
     * 库里存 {@code 2024-1} 的话两者永远匹配不上, 而这不是调用方能预料到的.
     */
    public static String seasonOf(String date) {
        LocalDate d = parse(date);
        if (d == null) {
            return null;
        }
        return String.format("%04d-%02d", d.getYear(), d.getMonthValue());
    }

    /**
     * 播出日期 + 总集数 + 今天 → 放送状态.
     *
     * <p>语义是"播完了没有", 不是"这周有没有更新": 停播中的番剧仍然算 airing.
     * 反过来(把停播算成完结)会让筛选结果少掉正在追的番, 是更糟的一档.
     *
     * <p>结束日是按集数估的(每周一集), 所以对**分割放送**和**长连载**会不准:
     * 24 集分两季播的番在第 20 周会被算成还在放送(正确), 但如果是长连载且
     * 总集数未知(如海贼王), 估值会把它算成早已完结 —— 这一类由日历接口纠正:
     * 日历返回的就是"当前在播"的权威清单, 见
     * {@code AnimeService.upsertCalendarItem}.
     *
     * <p>还没开播的返回 null 而不是 airing: 契约里只有 airing/finished 两个值,
     * 未播的番两个都不是, 硬塞进 airing 会让"放送中"这个筛选混进一堆还没播的番.
     */
    public static String statusOf(String date, Integer totalEpisodes, LocalDate today) {
        LocalDate start = parse(date);
        if (start == null || today == null) {
            return null;
        }
        if (start.isAfter(today)) {
            return null;   // 未播
        }

        int weeks = (totalEpisodes != null && totalEpisodes > 0)
                ? totalEpisodes
                : UNKNOWN_LENGTH_WEEKS;
        LocalDate estimatedEnd = start.plusWeeks(weeks).plusWeeks(GRACE_WEEKS);

        // 结束日当天算"还在放送": 最后一集播出的那一天, 说它已完结是提前了一天
        return estimatedEnd.isBefore(today) ? STATUS_FINISHED : STATUS_AIRING;
    }

    /**
     * Bangumi 的 {@code rating.rank} → 本项目要的排名.
     *
     * <p>关键是 {@code 0} 要转成 null: Bangumi 对"评分人数不够、还没进榜"的作品
     * 给的就是 0(实测一个 15 人评分的条目返回 {@code "rank":0}).
     * 直接把这个 0 存进去, 它会在 {@code ORDER BY sort_rank ASC} 里排到**第一位** ——
     * 一个没人看过的番剧挂在排行榜榜首. 转成 null 之后, 排序那一侧的"没名次的排最后"
     * 才会把它放到正确的位置 —— 那条口径现在是 SQL, 见
     * {@code AnimeQueries.ORDER_RANK_ASC_NULL_LAST}(它把 {@code <= 0} 也一并当成
     * "没有名次", 与这里同一个理由: <b>Bangumi 的 0 表示"没有这个值", 不是"值为零"</b>,
     * 于是这一层对归一之前落库的历史行也仍然成立).
     */
    public static Integer rankOf(Integer rawRank) {
        return (rawRank == null || rawRank <= 0) ? null : rawRank;
    }

    /** 解析成 {@link LocalDate}; 解析不了就返回 null, 由调用方决定怎么处理 */
    static LocalDate parse(String date) {
        if (date == null) {
            return null;
        }
        Matcher m = DATE.matcher(date.trim());
        if (!m.matches()) {
            return null;
        }
        try {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int day = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            // 2024-13 / 2024-02-31 这类: 这一列的内容不可信, 当作没有
            return null;
        }
    }
}
