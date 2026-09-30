package com.animetracker.service;

import com.animetracker.dto.BangumiDTO.SubjectDTO;
import com.animetracker.entity.Anime;
import com.animetracker.repository.AnimeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「最近更新」不包含还没上映的条目, 而**没有日期的条目仍然要在**.
 *
 * <p><b>为什么要单独一个集成测试.</b> 这条谓词在 JPQL 里是**字符串比较**, 它踩在两个
 * Java 层看不出来的假设上: 日期的存储形状是 ISO 的, 以及数据库真会把
 * {@code '2027' <= '2026-09-30'} 判成假. 更麻烦的是它最容易写漏的那半句
 * ({@code a.date IS NULL OR}) —— 漏了之后症状是"榜单少了若干行", 接口照样 200,
 * 顺序照样对, 没有任何人会看出来. 这种"静默变少"只有真把语句发给数据库才发现.
 *
 * <p><b>为什么绕开 {@code AnimeService.getLatest} 直接用仓储.</b> 那一层还压着
 * {@code @Cacheable} 与后台回源, 会让用例变成"看时钟和网络脸色"(回源是真的会去调
 * Bangumi 的 API, 见 {@code AnimeServiceLatestTest}). 要被钉住的是那条查询本身;
 * {@code today} 拿到自己手上之后, 这个用例就不再依赖运行时刻.
 * 「服务层传下去的 today 格式对不对」由 {@code AnimeServiceLatestTest} 用
 * {@code ArgumentCaptor} 单独钉 —— 那是同一个改动的另一半.
 *
 * <p>id 一律用 94xxxxxx 这种 Bangumi 不可能返回的值, 断言只在**自己建的这几行**上做:
 * 这个测试 JVM 里 {@code CachePreloader} 会并发往同一张表插真实数据
 * (理由与 {@code AnimeFilterIntegrationTest} 完全一样).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-latest-not-future;DB_CLOSE_DELAY=-1;MODE=MySQL"
})
@ActiveProfiles("dev")
class AnimeLatestIntegrationTest {

    @Autowired
    private AnimeService animeService;

    @Autowired
    private AnimeRepository animeRepository;

    private void seed(int id, String name, String date) {
        SubjectDTO dto = new SubjectDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setDate(date);
        animeService.upsertAnime(dto);
    }

    /**
     * 调真实的查询, 但只保留自己建的那几行.
     *
     * <p>limit 开得很大, 免得预加载器插进来的行把"我这几条"挤出这一页 ——
     * 挤出去之后断言会变成"少了几条", 而那正好和被测的 bug 长得一模一样.
     */
    private List<Integer> latestAmong(Set<Integer> mine, String today) {
        return animeRepository.findLatest(today, PageRequest.of(0, 500)).stream()
                .map(Anime::getId)
                .filter(mine::contains)
                .collect(Collectors.toList());
    }

    private static Set<Integer> ids(Integer... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    @Test
    @DisplayName("还没上映的不在; 没日期的仍在且排最后")
    void excludesFutureButKeepsRowsWithoutADate() {
        LocalDate today = LocalDate.now();
        String t = today.toString();

        seed(94000001, "三年前", today.minusYears(3).toString());
        seed(94000002, "今天", t);
        seed(94000003, "三年后", today.plusYears(3).toString());
        seed(94000004, "没日期", null);

        List<Integer> got = latestAmong(ids(94000001, 94000002, 94000003, 94000004), t);

        // 这一句是整个改动存在的理由: 去掉 LATEST 里的 NOT_FUTURE 它就红.
        // 库里那批 2027-2029 的条目是从搜索关键词「剧场版」来的, 日期最大,
        // 按播出日倒序就顶在第一屏 —— 「最近更新」于是变成「最远未来」
        assertThat(got).doesNotContain(94000003);

        // 没日期那条**必须在**, 而且排在最后(ORDER_DATE_DESC_NULL_LAST 的口径就是
        // "缺日期的排最后", 不是"不要它们"). 谓词写成 `a.date <= :today`、漏掉
        // `a.date IS NULL OR` 时它会静默消失 —— NULL 参与比较的结果还是 NULL,
        // 也就是假, 而这件事在 Java 侧完全看不出来
        assertThat(got).contains(94000004);
        assertThat(got).last().isEqualTo(94000004);

        // 已经播出的按日期倒序: 今天这条在我这几行里排第一
        assertThat(got).first().isEqualTo(94000002);
    }

    @Test
    @DisplayName("只有年月/只有年份的日期同样按字符串比得对")
    void partialDatesCompareCorrectly() {
        LocalDate today = LocalDate.now();
        String t = today.toString();
        String ym = today.getYear() + "-" + String.format("%02d", today.getMonthValue());

        // 库里的 date 是字符串, 形状有 'yyyy-MM-dd' / 'yyyy-MM' / 'yyyy' 三种.
        // 短的那些没有"补零"问题: '2026' 是 '2026-09-30' 的前缀, 字典序天然排在它前面.
        // 这一条钉的就是"比字符串"这个假设 —— 它同时撑起上面那条 ORDER BY
        seed(94000011, "本月(只有年月)", ym);
        seed(94000012, "明年(只有年份)", String.valueOf(today.getYear() + 1));

        List<Integer> got = latestAmong(ids(94000011, 94000012), t);

        // 本月那条: '2026-09' <= '2026-09-30' 为真(是前缀且更短)
        assertThat(got).contains(94000011);
        // 明年那条: '2027' <= '2026-09-30' 为假
        assertThat(got).doesNotContain(94000012);
    }
}
