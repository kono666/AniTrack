package com.animetracker.service;

import com.animetracker.dto.BangumiDTO.CalendarItem;
import com.animetracker.dto.BangumiDTO.RatingDTO;
import com.animetracker.dto.BangumiDTO.SubjectDTO;
import com.animetracker.dto.BangumiDTO.TagDTO;
import com.animetracker.entity.Anime;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.util.AnimeFields;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * season / status / rank 三个字段真的落进了库, 而且是**正确的值**.
 *
 * <p>和 {@code AnimeFieldsTest} 的分工: 那个类只验推导函数本身(纯计算, 毫秒级),
 * 这个类验的是「推导出来的值有没有走到数据库里」—— 中间隔着 DTO 字段有没有读到、
 * 实体有没有 set、save 是 merge 还是脏检查. 这三个字段之所以当初一个都没填上,
 * 恰恰不是因为推导写错了, 而是因为映射分散在四处、没人改到. 所以这一层必须单独验.
 *
 * <p><b>断言一律重新查库, 不看返回值</b>
 *
 * <p>{@code upsertAnime} 返回的是传进去的那个对象; 而 id 非空时 Spring Data 走的是
 * merge, merge 会另建一个受管副本, 传进去的那个始终是游离的. 于是"返回的对象上
 * 字段都对"这件事, 完全不能证明库里的行是对的. 每次都 {@code findById} 重新读一遍.
 *
 * <p><b>为什么类上没有 @Transactional</b>
 *
 * <p>同 {@code WriteConflictIntegrationTest}: 生产上的网页请求本来就没有外层事务,
 * 每条 repository 调用各自提交, 不挂事务才更接近真实路径. 挂了外层事务的话,
 * 被测代码内部的事务会变成空操作(加入已有事务), 提交时机也就不一样了.
 *
 * <p><b>为什么 id 都取 9xxxxxxx 这种大数</b>
 *
 * <p>这个测试 JVM 里 {@code CachePreloader} 会被 ApplicationReadyEvent 触发, 真的去
 * 调 api.bgm.tv 并往同一张表里插数据(它在后台线程里跑, 与用例并发). 所以
 * "表里有几行""返回列表里有没有"这类全局断言都是不稳定的. 本类只用固定的、
 * Bangumi 不可能返回的 id 建自己的行, 断言全部按 id 定位.
 *
 * <p>同理, 用例里的日期都是**相对今天算的**, 而不是写死的 "2026-07-01":
 * 服务里推导用的是系统时钟(没有为了一次测试去引入 Clock 抽象), 写死日期的话
 * 这些用例过几个月就会自己变红, 而红的原因和被改坏与否毫无关系.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-anime-fields;DB_CLOSE_DELAY=-1;MODE=MySQL"
})
@ActiveProfiles("dev")
class AnimeFieldsIntegrationTest {

    @Autowired
    private AnimeService animeService;
    @Autowired
    private AnimeRepository animeRepository;
    @Autowired
    private JdbcTemplate jdbc;

    /** 重新查库, 不信任返回值 */
    private Anime reload(int id) {
        return animeRepository.findById(id).orElseThrow(
                () -> new AssertionError("id=" + id + " 没有落库"));
    }

    private static SubjectDTO subject(int id, String name, String date, Integer totalEpisodes) {
        SubjectDTO dto = new SubjectDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setDate(date);
        dto.setTotalEpisodes(totalEpisodes);
        return dto;
    }

    private static RatingDTO rating(Double score, Integer total, Integer rank) {
        RatingDTO r = new RatingDTO();
        r.setScore(score);
        r.setTotal(total);
        r.setRank(rank);
        return r;
    }

    // ========== 同步路径 ==========

    /**
     * 新建一个条目时, 三个字段连同来源字段一起落库.
     *
     * <p>这是 checklist 2.1 的主症状: 改动前 season/status/rank 在库里全是 NULL,
     * 而按它们筛选的接口一直对外开着.
     */
    @Test
    @DisplayName("新条目落库: season 从 date 推、status 从 date+集数 推、rank 从 rating.rank 取")
    void writesSeasonAndStatusOnANewSubject() {
        LocalDate start = LocalDate.now().minusWeeks(2);
        SubjectDTO dto = subject(90000001, "字段填充用例A", start.toString(), 12);
        dto.setRating(rating(8.5, 1200, 137));

        animeService.upsertAnime(dto);

        Anime saved = reload(90000001);
        assertThat(saved.getSeason()).isEqualTo(AnimeFields.seasonOf(start.toString()));
        assertThat(saved.getStatus()).isEqualTo(AnimeFields.STATUS_AIRING);
        assertThat(saved.getRank()).isEqualTo(137);
        // 顺带确认来源字段也进去了 —— 否则上面三个值可能来自别处
        assertThat(saved.getDate()).isEqualTo(start.toString());
        assertThat(saved.getRating()).isEqualTo(8.5);
        assertThat(saved.getRatingCount()).isEqualTo(1200);
    }

    /**
     * rank=0 落库时要变成 NULL.
     *
     * <p>Bangumi 对"评分人数不够、还没进榜"的条目返回的就是 {@code rank: 0}.
     * 原样存进去的话, 它在 {@code ORDER BY sort_rank ASC} 里会排到第一名 ——
     * 排行榜首位挂一部没人看过的番. 而 NULL 会被排序那侧的 9999 兜底放到最后.
     */
    @Test
    @DisplayName("rating.rank=0（未上榜）落库为 NULL, 不是 0 —— 否则它会排到排行榜第一名")
    void zeroRankIsStoredAsNull() {
        SubjectDTO dto = subject(90000002, "未上榜用例", LocalDate.now().minusWeeks(1).toString(), 12);
        dto.setRating(rating(6.0, 15, 0));

        animeService.upsertAnime(dto);

        assertThat(reload(90000002).getRank()).isNull();
        // 评分人数这类字段照常写, 只有 rank 被特殊处理
        assertThat(reload(90000002).getRatingCount()).isEqualTo(15);
    }

    /**
     * **本类最重要的一条**: 字段不全的 DTO 不能把已经知道的值抹成 NULL.
     *
     * <p>Bangumi 的搜索接口与详情接口返回的字段并不一致 —— 搜索结果常常没有简介、
     * 没有集数. 同步路径又是"搜到什么就写什么", 于是同一条目会被反复写很多次.
     * 无条件覆盖的后果是: 详情接口刚拿到的简介, 被下一次搜索结果清成 NULL;
     * season/status 刚算出来, 又变回 NULL. 而且**不报错**, 只表现为"字段过一阵子
     * 又空了", 极难查.
     *
     * <p>这条也是当初那四份映射副本最直接的后果: merge 会把实体上没赋值的字段
     * 一起写成 null, 只要有一次走了那条路, 前面填的全白填.
     */
    @Test
    @DisplayName("字段不全的 DTO 不清空已有数据：第二次同步（只有 id 和名字）之后, 之前拿到的仍然在")
    void partialPayloadDoesNotEraseWhatWasAlreadyKnown() {
        int id = 90000003;
        LocalDate start = LocalDate.now().minusWeeks(3);

        SubjectDTO full = subject(id, "局部覆盖用例", start.toString(), 24);
        full.setSummary("这段简介只有详情接口才给");
        full.setPlatform("TV");
        full.setRating(rating(7.7, 300, 42));
        full.setTags(List.of(tag("科幻"), tag("日常")));
        animeService.upsertAnime(full);

        // 模拟一次"搜索结果"式的同步: 只有 id 和名字, 其余全是 null
        animeService.upsertAnime(subject(id, "局部覆盖用例", null, null));

        Anime saved = reload(id);
        // 这四项是"没给就不该动"的
        assertThat(saved.getDate()).as("date 被抹掉了").isEqualTo(start.toString());
        assertThat(saved.getSummary()).as("summary 被抹掉了").isEqualTo("这段简介只有详情接口才给");
        assertThat(saved.getPlatform()).as("platform 被抹掉了").isEqualTo("TV");
        assertThat(saved.getTotalEpisodes()).as("总集数被抹掉了").isEqualTo(24);
        assertThat(saved.getRating()).as("评分被抹掉了").isEqualTo(7.7);
        assertThat(saved.getRank()).as("名次被抹掉了").isEqualTo(42);
        assertThat(saved.getTags()).as("标签被抹掉了").contains("科幻").contains("日常");
        // 推导字段同理: date 还在, 就该仍然推得出来
        assertThat(saved.getSeason()).isEqualTo(AnimeFields.seasonOf(start.toString()));
        assertThat(saved.getStatus()).isEqualTo(AnimeFields.STATUS_AIRING);
    }

    // ========== 日历路径 ==========

    /**
     * 日历条目要写 air_date, 并且直接定成"放送中".
     *
     * <p>两个都被修过的地方:
     * <ul>
     *   <li>{@code air_date} 以前既没读也没写, 于是从日历来的番剧 date 全是 NULL
     *       (实测线上 470 行里 145 行), 而 date 为空意味着 season 和 status 都推不出来
     *       —— 一个字段没写, 连带两个字段一起废掉.</li>
     *   <li>status 用推导值的话, 长连载(总集数未知)会被算成"早已完结". 日历是
     *       "当前在播"的权威清单, 在里面就该是 airing.</li>
     * </ul>
     */
    @Test
    @DisplayName("日历条目: 落 air_date, 且 status 直接是 airing（长连载推导会误判成已完结）")
    void calendarItemWritesAirDateAndIsAuthoritativelyAiring() {
        int id = 90000004;
        // 播了很久的番: 光看日期推, 早就该是 finished
        LocalDate longAgo = LocalDate.now().minusWeeks(200);

        CalendarItem item = new CalendarItem();
        item.setId(id);
        item.setName("日历长连载用例");
        item.setAirDate(longAgo.toString());
        item.setRank(88);

        animeService.upsertCalendarItem(item);

        Anime saved = reload(id);
        assertThat(saved.getDate()).as("air_date 被丢掉了").isEqualTo(longAgo.toString());
        assertThat(saved.getSeason()).isEqualTo(AnimeFields.seasonOf(longAgo.toString()));
        assertThat(saved.getStatus())
                .as("在日历里就是正在播, 不该用推导值覆盖")
                .isEqualTo(AnimeFields.STATUS_AIRING);
        assertThat(saved.getRank()).isEqualTo(88);
    }

    /**
     * 日历条目不带 rank 时, 不能把已经知道的名次抹掉.
     *
     * <p>日历返回的条目时有时无 rank, 那种情况是"不知道", 不是"没有排名".
     * 无条件 setRank(null) 会让每次日历刷新都把详情接口辛苦拿到的名次清一次 ——
     * 而筛选接口默认就按 rank 排序, 结果是排序随机地退化.
     */
    @Test
    @DisplayName("日历条目不带 rank 时保留原名次（日历的「没给」不等于「没有排名」）")
    void calendarItemWithoutRankKeepsTheKnownRank() {
        int id = 90000005;
        LocalDate start = LocalDate.now().minusWeeks(2);

        SubjectDTO dto = subject(id, "日历无rank用例", start.toString(), 12);
        dto.setRating(rating(7.0, 200, 55));
        animeService.upsertAnime(dto);
        assertThat(reload(id).getRank()).isEqualTo(55);

        CalendarItem item = new CalendarItem();
        item.setId(id);
        item.setName("日历无rank用例");
        item.setAirDate(start.toString());
        // rank 刻意不设
        animeService.upsertCalendarItem(item);

        assertThat(reload(id).getRank()).as("名次被日历刷掉了").isEqualTo(55);
    }

    // ========== 存量补齐 ==========

    /**
     * 启动时的补齐任务能给老数据填上 season/status, 且是幂等的.
     *
     * <p>用裸 JDBC 插入"只有 date 和集数、三个新字段全空"的行, 模拟引入这三个字段
     * 之前就存在的数据 —— 走 service 是造不出这种行的(它一定会填上).
     *
     * <p>幂等这条以前面/后面两次快照比对来验, 而不是断言返回的"变更行数=0":
     * 那个数字把并发预加载器插进来的行也算在内, 拿它做断言会随机变红. 比对的
     * 是两次之间每行 season/status 有没有变, 与表里有多少行无关.
     */
    @Test
    @DisplayName("存量补齐: 只有 date 的老行被填上 season/status, 再跑一次不再改动任何一行")
    void backfillFillsLegacyRowsAndIsIdempotent() {
        String airingDate = LocalDate.now().minusWeeks(1).toString();
        String finishedDate = LocalDate.now().minusWeeks(60).toString();
        // 只有年份的脏数据: 推导不出来, 只能留空
        String unusableDate = "2019";

        jdbc.update("INSERT INTO anime (id, title, date, total_episodes, season, status) VALUES (?,?,?,?,NULL,NULL)",
                90000011, "存量在播用例", airingDate, 12);
        jdbc.update("INSERT INTO anime (id, title, date, total_episodes, season, status) VALUES (?,?,?,?,NULL,NULL)",
                90000012, "存量已完结用例", finishedDate, 12);
        jdbc.update("INSERT INTO anime (id, title, date, total_episodes, season, status) VALUES (?,?,?,?,NULL,NULL)",
                90000013, "存量脏日期用例", unusableDate, 12);

        int changed = animeService.backfillDerivedFields();

        assertThat(reload(90000011).getSeason()).isEqualTo(AnimeFields.seasonOf(airingDate));
        assertThat(reload(90000011).getStatus()).isEqualTo(AnimeFields.STATUS_AIRING);
        assertThat(reload(90000012).getSeason()).isEqualTo(AnimeFields.seasonOf(finishedDate));
        assertThat(reload(90000012).getStatus()).isEqualTo(AnimeFields.STATUS_FINISHED);
        // 推不出来的就留空, 不猜一个季度填上
        assertThat(reload(90000013).getSeason()).isNull();
        assertThat(reload(90000013).getStatus()).isNull();
        // 至少这 3 行变了(表里还有并发插进来的行, 所以是 >= 而不是 ==)
        assertThat(changed).isGreaterThanOrEqualTo(3);

        Map<Integer, String> before = derivedSnapshot();
        animeService.backfillDerivedFields();
        Map<Integer, String> after = derivedSnapshot();

        // 只比对两次都在的行: 并发插入的行不在交集里, 不会影响结论
        List<Integer> common = before.keySet().stream()
                .filter(after::containsKey)
                .sorted()
                .collect(Collectors.toList());
        assertThat(common).as("两次快照之间没有可比对的行, 用例失去意义").isNotEmpty();
        assertThat(common).allSatisfy(id ->
                assertThat(after.get(id)).as("id=%d 的推导字段被第二次重算改动了", id)
                        .isEqualTo(before.get(id)));
    }

    /** 全表的 id -> "season/status" 快照 */
    private Map<Integer, String> derivedSnapshot() {
        Map<Integer, String> snapshot = new LinkedHashMap<>();
        // 用块状 lambda: 表达式 lambda 的返回值可以被丢弃, 于是在
        // ResultSetExtractor 与 RowCallbackHandler 两个重载之间无法定夺
        jdbc.query("SELECT id, season, status FROM anime", rs -> {
            snapshot.put(rs.getInt("id"),
                    String.valueOf(rs.getString("season")) + "/" + rs.getString("status"));
        });
        return snapshot;
    }

    private static TagDTO tag(String name) {
        TagDTO t = new TagDTO();
        t.setName(name);
        return t;
    }
}
