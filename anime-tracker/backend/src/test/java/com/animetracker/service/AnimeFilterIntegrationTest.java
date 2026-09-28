package com.animetracker.service;

import com.animetracker.dto.BangumiDTO.RatingDTO;
import com.animetracker.dto.BangumiDTO.SubjectDTO;
import com.animetracker.dto.BangumiDTO.TagDTO;
import com.animetracker.entity.Anime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按 season / status / year 筛选真的筛得出来, 默认按 rank 排序也真的排得上.
 *
 * <p>为什么「字段写进库」之外还要单独验这一层: 这两件事之间的路很长 ——
 * 实体字段名要对得上仓储方法的派生查询、比较要用 equals 而不是 ==、排序里那几个
 * 三元表达式不能把条件写反. 任何一处错了, 接口都照样返回 200, 只是结果集是空
 * 或者顺序乱掉, 而调用方(以及 AI 工具 {@code filter_anime})看到的是"没有这部番",
 * 分不清是真没有还是筛坏了. E2E 的验收标准就是这一条.
 *
 * <p>同样用 9xxxxxxx 这种 Bangumi 不可能返回的 id, 断言只在**自己建的这几行**上做:
 * 这个测试 JVM 里 {@code CachePreloader} 会在后台真的调 API 往同一张表插数据,
 * "结果集恰好等于某某"这种全局断言随时可能被它插进来的行打翻.
 *
 * <p>日期一律相对今天算, 理由同 {@code AnimeFieldsIntegrationTest}: 服务里用的是
 * 系统时钟, 写死日期会让用例过几个月自己变红.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-anime-fields;DB_CLOSE_DELAY=-1;MODE=MySQL"
})
@ActiveProfiles("dev")
class AnimeFilterIntegrationTest {

    @Autowired
    private AnimeService animeService;

    /**
     * 调真实的 getFiltered, 但只保留自己建的那几行.
     *
     * <p>这是本类的核心手法: 过滤逻辑(年份前缀、季度相等、状态相等、排序)全部照跑,
     * 只在最后把结果收窄到自己控制的 id 上. 这样既验了完整的筛选链路, 又不受
     * 并发插入的真实数据影响 —— 否则每次跑都要看预加载器拉回来多少条, 结果随机.
     */
    private List<Integer> filteredAmong(Set<Integer> mine, String year, String season,
                                       String status, String sort) {
        return animeService.getFiltered(year, season, status, null, sort).stream()
                .map(Anime::getId)
                .filter(mine::contains)
                .collect(Collectors.toList());
    }

    private void seed(int id, String name, String date, Integer totalEpisodes, Integer rank) {
        seedTagged(id, name, date, totalEpisodes, rank);
    }

    /** 同上, 另外挂上标签 —— 标签会同时写进 tags 列与 anime_tag 关联表 */
    private void seedTagged(int id, String name, String date, Integer totalEpisodes, Integer rank,
                            String... tags) {
        SubjectDTO dto = new SubjectDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setDate(date);
        dto.setTotalEpisodes(totalEpisodes);
        RatingDTO rating = new RatingDTO();
        rating.setScore(7.0);
        rating.setTotal(100);
        rating.setRank(rank);
        dto.setRating(rating);
        if (tags.length > 0) {
            dto.setTags(Arrays.stream(tags).map(t -> {
                TagDTO tag = new TagDTO();
                tag.setName(t);
                return tag;
            }).collect(Collectors.toList()));
        }
        animeService.upsertAnime(dto);
    }

    private static Set<Integer> ids(Integer... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    // ========== 季度 ==========

    /**
     * season 筛选: 同月份的都在, 上个月的不在.
     *
     * <p>两个"同月"的日期是构造出来的, 不是碰运气: 取今天前一周的那天, 再把日改成 1 号,
     * 就一定落在同一个月里(哪怕今天正好是月初, 前一周跨了月, 两者也仍然同月).
     * 而"上个月"那条是负向对照 —— 没有它的话, 一个「永远返回全部」的筛选器
     * 也能让正面断言通过.
     */
    @Test
    @DisplayName("按季度筛: 同月的两条都在, 上个月的不在")
    void filtersBySeason() {
        LocalDate anchor = LocalDate.now().minusWeeks(1);
        LocalDate sameMonthA = anchor;
        LocalDate sameMonthB = anchor.withDayOfMonth(1);
        LocalDate previousMonth = anchor.minusMonths(1);

        // 期望值独立算一遍(不走 AnimeFields), 免得"用被测代码算期望"把错误一起验过去
        YearMonth ym = YearMonth.from(anchor);
        String season = String.format("%04d-%02d", ym.getYear(), ym.getMonthValue());
        YearMonth prevYm = YearMonth.from(previousMonth);
        String prevSeason = String.format("%04d-%02d", prevYm.getYear(), prevYm.getMonthValue());

        seed(90000101, "季度用例A", sameMonthA.toString(), 12, 1);
        seed(90000102, "季度用例B", sameMonthB.toString(), 12, 2);
        seed(90000103, "季度用例C", previousMonth.toString(), 12, 3);

        assertThat(filteredAmong(ids(90000101, 90000102, 90000103), null, season, null, null))
                .containsExactlyInAnyOrder(90000101, 90000102);
        assertThat(filteredAmong(ids(90000101, 90000102, 90000103), null, prevSeason, null, null))
                .containsExactly(90000103);
    }

    /**
     * 季度筛选要写成 {@code season.equals(a.getSeason())} 才对得上.
     *
     * <p>反过来说: 库里 season 为 NULL 的行(推不出播出日的那些)在筛任何季度时
     * 都不该出现 —— 一行"不知道是哪个季度"的番混进某个季度的结果里, 比缺一部更难发现.
     * 这里顺手钉住这一条: 没有日期的行, 三个筛选条件都筛不出来.
     */
    @Test
    @DisplayName("没有播出日的行(推不出季度)不会被任何季度/年份筛出来, 也不出现在 status 结果里")
    void rowsWithoutADateNeverMatch() {
        seed(90000104, "无日期用例", null, 12, null);
        Set<Integer> mine = ids(90000104);

        assertThat(filteredAmong(mine, String.valueOf(LocalDate.now().getYear()), null, null, null)).isEmpty();
        assertThat(filteredAmong(mine, null, String.format("%04d-%02d", LocalDate.now().getYear(),
                LocalDate.now().getMonthValue()), null, null)).isEmpty();
        assertThat(filteredAmong(mine, null, null, "airing", null)).isEmpty();
    }

    // ========== 状态 ==========

    @Test
    @DisplayName("按状态筛: 刚开播的在 airing 里, 播完一年的在 finished 里, 互不串门")
    void filtersByStatus() {
        seed(90000111, "状态用例在播", LocalDate.now().minusWeeks(1).toString(), 12, 1);
        seed(90000112, "状态用例已完结", LocalDate.now().minusWeeks(60).toString(), 12, 2);
        Set<Integer> mine = ids(90000111, 90000112);

        assertThat(filteredAmong(mine, null, null, "airing", null)).containsExactly(90000111);
        assertThat(filteredAmong(mine, null, null, "finished", null)).containsExactly(90000112);
    }

    // ========== 年份 ==========

    @Test
    @DisplayName("按年份筛: 今年的一条, 去年的一条, 各归各的")
    void filtersByYear() {
        LocalDate today = LocalDate.now();
        LocalDate lastYear = today.minusYears(1);

        seed(90000121, "年份用例今年", today.toString(), 12, 1);
        seed(90000122, "年份用例去年", lastYear.toString(), 12, 2);
        Set<Integer> mine = ids(90000121, 90000122);

        assertThat(filteredAmong(mine, String.valueOf(today.getYear()), null, null, null))
                .containsExactly(90000121);
        assertThat(filteredAmong(mine, String.valueOf(lastYear.getYear()), null, null, null))
                .containsExactly(90000122);
    }

    // ========== 排序 ==========

    /**
     * 默认排序按 rank 升序, 且**没有名次的排在最后**.
     *
     * <p>最后那半句是这条用例的重点. 库里名次为 NULL 的行占多数(未上榜的都存成 NULL),
     * 若把 NULL 当 0 参与比较, 它们会集体排到榜首 —— 用户打开筛选页看到的是一屏
     * "没评分没名次"的番, 而真正的第一名在后面. 兜底成 9999 就是为了这个.
     *
     * <p>顺带把 rank=0(未上榜)那条路也串起来验: 它落库为 NULL, 于是应当排最后.
     */
    @Test
    @DisplayName("默认按 rank 升序, 没有名次(含 rank=0 未上榜)的排最后")
    void sortsByRankWithUnrankedLast() {
        String date = LocalDate.now().minusWeeks(1).toString();
        seed(90000131, "排序用例30", date, 12, 30);
        seed(90000132, "排序用例10", date, 12, 10);
        seed(90000133, "排序用例20", date, 12, 20);
        seed(90000134, "排序用例未上榜", date, 12, 0);   // rank=0 -> 落库为 NULL

        assertThat(filteredAmong(ids(90000131, 90000132, 90000133, 90000134), null, null, null, null))
                .containsExactly(90000132, 90000133, 90000131, 90000134);
    }

    /**
     * {@code sort=date} 时按播出日倒序, 没有播出日的排最后.
     *
     * <p>同样是"缺值不能排到前面"的问题: 日期为 NULL 的行(实测线上 145 行)
     * 排在最新番剧之前, 首页"最近更新"就会是一屏空白日期.
     */
    @Test
    @DisplayName("sort=date 按播出日倒序, 没有播出日的排最后")
    void sortsByDateDescendingWithUnknownLast() {
        seed(90000141, "日期排序较新", LocalDate.now().minusWeeks(1).toString(), 12, 1);
        seed(90000142, "日期排序较旧", LocalDate.now().minusWeeks(30).toString(), 12, 2);
        seed(90000143, "日期排序未知", null, 12, 3);

        assertThat(filteredAmong(ids(90000141, 90000142, 90000143), null, null, null, "date"))
                .containsExactly(90000141, 90000142, 90000143);
    }

    /**
     * 三个条件叠加时是「与」的关系, 不是「或」也不是「后者覆盖前者」.
     *
     * <p>单独验每个条件全绿、叠加起来却筛不出东西, 是这类接口最典型的一种坏法.
     * 这里建的三条里, 两条各差**一个**条件, 只有一条全中 —— 只留下那一条,
     * 才能说明三个条件都在真的起作用.
     *
     * <p>为什么"只差年份"的那一行造不出来: season 是 {@code yyyy-MM}, 本身就把年份
     * 编进去了, "同季度不同年"在逻辑上就不存在. 所以年份这一条改用"换一个年份,
     * 结果为空"来钉(见下面的第二次断言).
     *
     * <p>为什么锚点是 20 周前而不是上周: 同一个月里的日期推出来的状态是一样的
     * (刚开播的月份不可能算出"已完结"), 想让两条只差状态, 就得挑一个"按集数已经
     * 播完、但集数未知时还在播"的月份 —— 20 周前正好落在这一档.
     */
    @Test
    @DisplayName("三个条件叠加是「与」: year + season + status 同时命中才留下")
    void combinesConditionsWithAnd() {
        LocalDate anchor = LocalDate.now().minusWeeks(20);
        YearMonth ym = YearMonth.from(anchor);
        String season = String.format("%04d-%02d", ym.getYear(), ym.getMonthValue());
        String year = String.valueOf(ym.getYear());

        // 全中: 同年、同季度、12 集已经播完
        seed(90000151, "叠加用例全中", anchor.toString(), 12, 1);
        // 只差状态: 同年同季度, 但 200 集 -> 还在放送
        seed(90000152, "叠加用例差状态", anchor.toString(), 200, 2);
        // 只差季度: 同年、同样已播完, 但提前 8 周(8 周必定跨月)
        seed(90000153, "叠加用例差季度", anchor.minusWeeks(8).toString(), 12, 3);

        assertThat(filteredAmong(ids(90000151, 90000152, 90000153), year, season, "finished", null))
                .containsExactly(90000151);
        // 年份换掉 -> 一条都不该剩
        assertThat(filteredAmong(ids(90000151, 90000152, 90000153),
                String.valueOf(ym.getYear() - 1), season, "finished", null)).isEmpty();
    }

    // ========== 标签 ==========

    /** 调真实的 getFiltered 带上 tag, 结果收窄到自己建的这几行 */
    private List<Integer> taggedAmong(Set<Integer> mine, String tag, String year) {
        return animeService.getFiltered(year, null, null, tag, null).stream()
                .map(Anime::getId)
                .filter(mine::contains)
                .collect(Collectors.toList());
    }

    /**
     * 标签筛选: 传来的名字会**同时试**中文原文和它的英文写法, 两个写法各挂一部番时
     * 两部都要出现.
     *
     * <p>为什么这件事要专门验: 库里同一批标签是两套写法混着的(Bangumi 那边给的是英文,
     * 界面上显示的是中文), 所以"按百合找"必须也能找到只挂了 Yuri 的那一部.
     * 这条路和别的筛选条件一样, 坏了只会安静地少给几条 —— 调用方分不清"没有"和"筛丢了".
     *
     * <p>第三部挂了不相干的标签, 是负向对照: 少了它, 一个"永远返回全部"的筛选器
     * 也能让前两条断言通过. 最后一条断言年份与标签叠加是「与」——
     * 只查去年时, 这批今年播的行一条都不该剩.
     */
    @Test
    @DisplayName("按标签筛: 中文名与英文写法都命中, 与年份叠加是「与」")
    void filtersByTagIncludingItsEnglishSpelling() {
        String date = LocalDate.now().minusWeeks(1).toString();
        seedTagged(90000161, "标签用例挂中文名", date, 12, 1, "百合");
        seedTagged(90000162, "标签用例挂英文名", date, 12, 2, "Yuri");
        seedTagged(90000163, "标签用例不相干", date, 12, 3, "科幻");
        Set<Integer> mine = ids(90000161, 90000162, 90000163);

        // 传中文名: 中文名和英文名各挂的那部都在
        assertThat(taggedAmong(mine, "百合", null)).containsExactlyInAnyOrder(90000161, 90000162);
        // 传英文名: 只按这个写法找 —— 中文原文不在候选里(方向是"中文→英文", 没有反向表)
        assertThat(taggedAmong(mine, "Yuri", null)).containsExactly(90000162);
        // 不相干的标签不在结果里
        assertThat(taggedAmong(mine, "百合", null)).doesNotContain(90000163);
        // 叠加年份: 这批行都在今年, 查去年就一条都没有
        assertThat(taggedAmong(mine, "百合", String.valueOf(LocalDate.now().getYear() - 1))).isEmpty();
    }
}
