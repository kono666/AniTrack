package com.animetracker.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * season / status / rank 三个推导函数的边界.
 *
 * <p>这三个值的特殊性在于: 它们算错了**不会报错**. 过滤接口照样返回 200,
 * 只是结果里少几部番或者多几部不该出现的番, 而看的人无从判断是"真没有"
 * 还是"算错了". 所以边界必须逐条钉住.
 *
 * <p>today 是参数而不是取系统时钟, 就是为了这个: 用 LocalDate.now() 的话,
 * "播出日正好是今天"这种边界只在极少数日子里能被跑到, 而且测试会在某一天
 * 突然变红或变绿 —— 那种测试不如没有.
 */
class AnimeFieldsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    // ========== season ==========

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "2024-10-05, 2024-10",
            "2024-10-01, 2024-10",
            "2024-01-31, 2024-01",
            "2024-12-31, 2024-12",
            "2024-10,    2024-10",      // 只有年月
    })
    @DisplayName("播出日期 -> 季度 yyyy-MM")
    void derivesSeason(String date, String expected) {
        assertThat(AnimeFields.seasonOf(date)).isEqualTo(expected);
    }

    /**
     * 不补零的月份要归一化.
     *
     * <p>不归一化的话库里会存下 {@code 2024-1}, 而调用方(以及 AI 工具的参数说明)
     * 按 {@code 2024-01} 去筛 —— 两者永远匹配不上, 且没有任何提示.
     */
    @Test
    @DisplayName("月份不补零时归一化成两位")
    void normalizesUnpaddedMonth() {
        assertThat(AnimeFields.seasonOf("2024-1-05")).isEqualTo("2024-01");
        assertThat(AnimeFields.seasonOf("2024-1")).isEqualTo("2024-01");
    }

    /**
     * 只有年份时必须返回 null, 不能挑一个月.
     *
     * <p>编一个的后果比 null 大: 调用方筛某个季度会看到一部实际不在那个季度的番,
     * 而且从返回结果上看不出这个值是猜的.
     */
    @Test
    @DisplayName("只有年份 -> null, 不挑一个季度填上")
    void yearOnlyYieldsNothing() {
        assertThat(AnimeFields.seasonOf("2024")).isNull();
        assertThat(AnimeFields.seasonOf("2024年")).isNull();
    }

    @ParameterizedTest(name = "无法推导: {0}")
    @ValueSource(strings = {"", "   ", "未知", "2024/10", "2024-13", "2024-00", "2024-02-31", "10-2024"})
    @DisplayName("空白、乱码、越界月份、不存在的日期一律 null —— 这一列的内容不可信时不该参与筛选")
    void unusableDatesYieldNothing(String date) {
        assertThat(AnimeFields.seasonOf(date)).as("date=%s", date).isNull();
    }

    @Test
    @DisplayName("date 为 null 时返回 null（线上 470 行里有 145 行是这种）")
    void nullDateYieldsNothing() {
        assertThat(AnimeFields.seasonOf(null)).isNull();
    }

    // ========== status ==========

    @Test
    @DisplayName("播出日还没到 -> null（未播既不是 airing 也不是 finished）")
    void notYetAiredHasNoStatus() {
        assertThat(AnimeFields.statusOf("2026-10-05", 12, TODAY)).isNull();
        // 明天开播
        assertThat(AnimeFields.statusOf("2026-09-29", 12, TODAY)).isNull();
    }

    @Test
    @DisplayName("今天开播 -> airing")
    void startsAiringToday() {
        assertThat(AnimeFields.statusOf("2026-09-28", 12, TODAY)).isEqualTo(AnimeFields.STATUS_AIRING);
    }

    @Test
    @DisplayName("12 集番开播 5 周 -> airing, 15 周 -> finished")
    void twelveEpisodeRun() {
        assertThat(AnimeFields.statusOf("2026-08-24", 12, TODAY)).isEqualTo(AnimeFields.STATUS_AIRING);
        assertThat(AnimeFields.statusOf("2026-06-15", 12, TODAY)).isEqualTo(AnimeFields.STATUS_FINISHED);
    }

    /**
     * 边界: 估出来的结束日**当天**还算在放送.
     *
     * <p>12 集 + 2 周宽限 = 14 周. 2026-06-22 加 14 周正好是 2026-09-28(今天),
     * 差一天的那次则是昨天. 最后一集播出的那一天说它已完结是提前了一天,
     * 而反过来把已完结的多留一天只是显示上的小偏差 —— 两害相权取后者.
     */
    @Test
    @DisplayName("估算结束日当天算 airing, 隔天算 finished")
    void estimatedEndDayIsInclusive() {
        assertThat(AnimeFields.statusOf("2026-06-22", 12, TODAY))
                .as("结束日正好是今天").isEqualTo(AnimeFields.STATUS_AIRING);
        assertThat(AnimeFields.statusOf("2026-06-21", 12, TODAY))
                .as("结束日是昨天").isEqualTo(AnimeFields.STATUS_FINISHED);
    }

    @Test
    @DisplayName("剧场版(1 集)开播三周就完结, 开播当天在放送")
    void movieFinishesQuickly() {
        assertThat(AnimeFields.statusOf("2026-09-20", 1, TODAY)).isEqualTo(AnimeFields.STATUS_AIRING);
        assertThat(AnimeFields.statusOf("2026-08-01", 1, TODAY)).isEqualTo(AnimeFields.STATUS_FINISHED);
    }

    /**
     * 总集数未知/为 0 时按 {@link AnimeFields#UNKNOWN_LENGTH_WEEKS} 估.
     *
     * <p>线上 470 行里有 162 行是这种(108 行 NULL + 54 行 0 或负) —— 不是边角情况.
     * 这一档会**误判长连载**: 海贼王这类总集数未知的番播出日很早, 估出来就是
     * "早已完结". 这是已知的取舍, 由日历接口纠正(日历返回的就是当前在播清单,
     * 见 AnimeService.upsertCalendarItem) —— 这里把口径钉住, 免得后人以为
     * 这档不存在.
     */
    @Test
    @DisplayName("总集数未知按 26 周估: 半年内 airing, 更早 finished")
    void unknownLengthFallsBackToTheDefaultEstimate() {
        // 6 个月前: 26 周 + 2 周宽限 = 28 周, 差几天还没到
        assertThat(AnimeFields.statusOf("2026-03-20", null, TODAY)).isEqualTo(AnimeFields.STATUS_AIRING);
        // 8 个月前: 已经超了
        assertThat(AnimeFields.statusOf("2026-01-20", null, TODAY)).isEqualTo(AnimeFields.STATUS_FINISHED);

        // 0 与负数同样当作"未知", 而不是"0 集所以立刻就完了"
        assertThat(AnimeFields.statusOf("2026-09-01", 0, TODAY)).isEqualTo(AnimeFields.STATUS_AIRING);
        assertThat(AnimeFields.statusOf("2026-09-01", -1, TODAY)).isEqualTo(AnimeFields.STATUS_AIRING);
    }

    @Test
    @DisplayName("推不出播出日 -> null, 不猜状态")
    void unparseableDateYieldsNoStatus() {
        assertThat(AnimeFields.statusOf(null, 12, TODAY)).isNull();
        assertThat(AnimeFields.statusOf("", 12, TODAY)).isNull();
        assertThat(AnimeFields.statusOf("2024", 12, TODAY)).isNull();
    }

    @Test
    @DisplayName("播出日只有年月时按当月 1 日算")
    void monthOnlyDateIsTreatedAsTheFirst() {
        // 2026-06-01 起 12 集 + 2 周宽限 = 98 天后 = 2026-09-07, 已经过了 -> finished
        assertThat(AnimeFields.statusOf("2026-06", 12, TODAY)).isEqualTo(AnimeFields.STATUS_FINISHED);
        // 2026-08-01 起算 = 2026-11-07 -> airing
        assertThat(AnimeFields.statusOf("2026-08", 12, TODAY)).isEqualTo(AnimeFields.STATUS_AIRING);
    }

    /** 跨年也是常见边界: 10 月开播的番, 结束日落在下一年 */
    @Test
    @DisplayName("跨年的番按绝对日期比较, 不受年份边界影响")
    void worksAcrossYearBoundary() {
        // 2026-10-05 开播, 12 集 + 2 周宽限 = 98 天后 = 2027-01-11
        assertThat(AnimeFields.statusOf("2026-10-05", 12, TODAY))
                .as("还没开播").isNull();
        assertThat(AnimeFields.statusOf("2026-10-05", 12, LocalDate.of(2027, 1, 10)))
                .as("结束日前一天").isEqualTo(AnimeFields.STATUS_AIRING);
        assertThat(AnimeFields.statusOf("2026-10-05", 12, LocalDate.of(2027, 1, 11)))
                .as("结束日当天").isEqualTo(AnimeFields.STATUS_AIRING);
        assertThat(AnimeFields.statusOf("2026-10-05", 12, LocalDate.of(2027, 1, 12)))
                .as("结束日之后").isEqualTo(AnimeFields.STATUS_FINISHED);
    }

    // ========== rank ==========

    /**
     * 0 必须变成 null.
     *
     * <p>Bangumi 对"评分人数不够、还没进榜"的作品返回的就是 {@code rank: 0}
     * (实测一个只有 15 人评分的条目返回 {@code "rank":0}). 把 0 存进 sort_rank,
     * 它会在 {@code ORDER BY sort_rank ASC} 里排到第一位 —— 排行榜第一名的位置上
     * 挂一部没人看过的番剧. 而 null 会被排序那侧的 9999 兜底放到最后.
     */
    @Test
    @DisplayName("rank=0 是「未上榜」, 要转成 null 而不是原样存 0")
    void zeroRankMeansUnranked() {
        assertThat(AnimeFields.rankOf(0)).isNull();
        assertThat(AnimeFields.rankOf(null)).isNull();
        assertThat(AnimeFields.rankOf(-1)).isNull();
    }

    @Test
    @DisplayName("真实名次原样保留")
    void realRankIsKept() {
        assertThat(AnimeFields.rankOf(1)).isEqualTo(1);
        assertThat(AnimeFields.rankOf(137)).isEqualTo(137);
    }
}
