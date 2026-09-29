package com.animetracker.service;

import com.animetracker.dto.BangumiDTO.InfoboxItem;
import com.animetracker.dto.BangumiDTO.SubjectDTO;
import com.animetracker.entity.Anime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 搜索的 SQL 真的能匹配别名, 且不会因为大小写或用户输入的 %/_ 而失准.
 *
 * <p>为什么这一层不能用 mock 验: 上面 {@code AnimeSearchBackfillTest} 验的是"回源落库了、
 * 又重查了一次"这几步控制流, 它把仓库换成了 mock —— 于是**这条 LIKE 本身对不对完全没验到**.
 * 而这次的 bug 恰恰就在这条 LIKE 上. JPQL 的那几个关键字({@code ESCAPE}、{@code LOWER})、
 * 大小写敏感与否、NULL 行会不会把整个 OR 链吞掉, 只有真库能回答.
 *
 * <p>用 H2 而不是 PG: 开发与 CI 的单元测试跑的都是 H2(MODE=MySQL), 而 H2 在 MODE=MySQL 下
 * **不会**把 LIKE 变成大小写不敏感(那要靠 SET IGNORECASE) —— 这正是要用 {@code LOWER()} 的
 * 原因, 也让这个库成了合适的"最坏情况"验证对象.
 *
 * <p>只断言**自己建的那几行**(9xxxxxxx 这种 Bangumi 不可能返回的 id), 与
 * {@code AnimeFilterIntegrationTest} 同一套手法: 断言"结果集恰好等于某某"会被别处插进来的
 * 数据打翻, 而这几个 id 只可能来自本类.
 *
 * <p>{@code anitrack.preload.enabled=false} + 独立的库名: 这个类要的是"库里只有我建的这几行"
 * 这个确定性, 而启动预加载会联网往同一张表里插数据. 回源接口本身也被 mock 掉
 * —— 搜索的懒回源会在本地不够时打外网, 用例不该依赖网络.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-anime-search;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false"
})
@ActiveProfiles("dev")
class AnimeSearchQueryIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private AnimeService animeService;

    /**
     * 回源那条路整条掐掉: 本类要验的是本地 LIKE, 而 mock 返回 null 时服务端会
     * 直接停手(见 {@code AnimeService.searchAnime} 里的空响应分支), 于是用例离线、确定.
     */
    @MockBean
    private BangumiApiClient bangumiApiClient;

    /** 建一行: name 是日文原名(name), nameCn 是中文名, aliases 走 infobox 那条真实路径写进去 */
    private void seed(int id, String name, String nameCn, String... aliases) {
        SubjectDTO dto = new SubjectDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setNameCn(nameCn);
        if (aliases.length > 0) {
            dto.setInfobox(List.of(aliasInfobox(aliases)));
        }
        animeService.upsertAnime(dto);
    }

    /** infobox 的形状照抄真实响应: {"key":"别名","value":[{"v":"EVA"},...]} */
    private static InfoboxItem aliasInfobox(String... aliases) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < aliases.length; i++) {
            if (i > 0) json.append(',');
            json.append("{\"v\":\"").append(aliases[i]).append("\"}");
        }
        json.append(']');

        InfoboxItem item = new InfoboxItem();
        item.setKey("别名");
        try {
            item.setValue(MAPPER.readTree(json.toString()));
        } catch (Exception e) {
            throw new IllegalStateException("测试用的 JSON 写错了", e);
        }
        return item;
    }

    /**
     * 搜关键词, 但只保留自己建的那几行 —— 过滤逻辑照跑, 断言只落在自己控制的 id 上.
     *
     * <p>为什么可以放心用每页 20 条: 这个库里一共只有本类建的十几行, 而且回源被 mock 成
     * 空响应, 不会有别的数据挤进来把结果顶出第一页.
     */
    private List<Integer> searchAmong(Set<Integer> mine, String keyword) {
        Map<String, Object> result = animeService.searchAnime(keyword, 1, 20);
        @SuppressWarnings("unchecked")
        List<Anime> list = (List<Anime>) result.get("list");
        return list.stream().map(Anime::getId).filter(mine::contains).collect(Collectors.toList());
    }

    private static Set<Integer> ids(Integer... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    // ========== 别名: 这次要修的那一条 ==========

    /**
     * 按别名能搜到 —— 这就是整个改动的目的.
     *
     * <p>搜的是 {@code EVA}, 而这一行的日文原名是 {@code 新世紀エヴァンゲリオン}、
     * 中文名是 {@code 新世纪福音战士}, 两者都不含 {@code EVA}. 改动前这条番明明在库里,
     * 搜 EVA 却是空的.
     */
    @Test
    @DisplayName("按别名能搜到: 日文名与中文名里都没有那个词")
    void matchesByAlias() {
        int id = 97000001;
        seed(id, "新世紀エヴァンゲリオン", "新世纪福音战士", "EVA", "Neon Genesis Evangelion");

        assertThat(searchAmong(ids(id), "EVA")).containsExactly(id);
        assertThat(searchAmong(ids(id), "Neon Genesis")).containsExactly(id);
        assertThat(searchAmong(ids(id), "福音战士")).as("中文名这条老路不能被新加的列挤掉").containsExactly(id);
        assertThat(searchAmong(ids(id), "エヴァンゲリオン")).as("日文原名这条老路同样要在").containsExactly(id);
    }

    /**
     * 别名命中里最要紧的一条: 大小写不敏感.
     *
     * <p>用户搜的是 {@code eva}, 库里存的是 {@code EVA} —— 大小写敏感的话这条番搜不出来,
     * 而中文名那条路又完全不涉及大小写, 所以"中文能搜、英文不能搜"这种半坏状态在人工点检时
     * 很容易被当成"这个词就是没有".
     *
     * <p>两个方向都验: 库里大写搜小写、库里小写搜大写. 只验一个方向的话, 一个"只在
     * 某一侧加了 LOWER"的写法(比如模式串降了、列没降)照样能通过.
     */
    @Test
    @DisplayName("大小写不敏感: 大写别名能搜到, 小写别名也能被大写搜到")
    void matchesCaseInsensitively() {
        int upper = 97000002;
        int lower = 97000003;
        seed(upper, "カウボーイビバップ", "星际牛仔", "COWBOY BEBOP");
        seed(lower, "カウボーイビバップ", "星际牛仔", "cowboy bebop");

        assertThat(searchAmong(ids(upper), "cowboy")).containsExactly(upper);
        assertThat(searchAmong(ids(lower), "COWBOY")).containsExactly(lower);
        assertThat(searchAmong(ids(upper), "CoWbOy BeBoP")).containsExactly(upper);
    }

    /**
     * 别名是**逗号分隔的一个字符串**, 命中可以落在中间的某一项上.
     *
     * <p>这不是多余的一条: 换个存法(比如每行只存第一个别名、或者存成 JSON)就会让
     * "第三个别名搜不到", 而前两个搜得到 —— 那种错更难被发现.
     */
    @Test
    @DisplayName("多别名: 逗号分隔串里靠后的那一项也能命中")
    void matchesAliasAnywhereInTheList() {
        int id = 97000004;
        seed(id, "進撃の巨人", "进击的巨人", "AOT", "Attack on Titan", "巨人");

        assertThat(searchAmong(ids(id), "Attack on Titan")).containsExactly(id);
        assertThat(searchAmong(ids(id), "AOT")).containsExactly(id);
        assertThat(searchAmong(ids(id), "巨人")).containsExactly(id);
    }

    // ========== 负向: 不能变成"搜什么都命中" ==========

    /**
     * 少一个字就不该命中.
     *
     * <p>没有这条负向对照的话, 一个「永远返回全部」的写法也能让上面每条正面用例通过.
     */
    @Test
    @DisplayName("负向: 关键词对不上就不命中")
    void doesNotMatchUnrelatedKeyword() {
        int id = 97000005;
        seed(id, "カウボーイビバップ", "星际牛仔", "COWBOY BEBOP");

        assertThat(searchAmong(ids(id), "cowboyx")).isEmpty();
        assertThat(searchAmong(ids(id), "牛仔x")).isEmpty();
        assertThat(searchAmong(ids(id), "星际牛")).as("子串也要能命中, 这条是上面空的对照").containsExactly(id);
    }

    // ========== 用户输入里的通配符 ==========

    /**
     * 用户搜 {@code %} 时, 匹配的是**字面量 %**, 不是"全部".
     *
     * <p>这是改动前一个更隐蔽的毛病: LIKE 里 {@code %} 是通配符, 而用户输入是直接拼进
     * 模式串的. 搜一个 {@code %} 命中全表 —— 结果"看起来有几百条", 像是搜索好得很,
     * 完全不会让人想到是转义问题.
     *
     * <p>一行含 {@code %}、一行不含, 在同一张表里对照: 前者必须在, 后者必须不在.
     */
    @Test
    @DisplayName("搜 % : 只命中含字面量 % 的那一行, 不是命中全表")
    void doesNotTreatPercentAsWildcard() {
        int hasPercent = 97000006;
        int withoutPercent = 97000007;
        seed(hasPercent, "フィフティ・パーセント", "五折", "50%off");
        seed(withoutPercent, "フィフティ", "五折", "50off");

        assertThat(searchAmong(ids(hasPercent, withoutPercent), "%"))
                .as("只该有那一行含字面量 %")
                .containsExactly(hasPercent);
    }

    /**
     * {@code _} 同理: 它是"任意一个字符", 不转义的话搜 {@code a_b} 会把 {@code axb} 也带出来.
     *
     * <p>用一个不含其它字符的短关键词, 让两种解释的差别只体现在"中间那个字符"上.
     */
    @Test
    @DisplayName("搜 a_b : 不再把中间任意一个字符的串一起带出来")
    void doesNotTreatUnderscoreAsWildcard() {
        int literal = 97000008;
        int wildcardWouldMatch = 97000009;
        seed(literal, "アンダースコア", "下划线", "zz_qq");
        seed(wildcardWouldMatch, "エックス", "字母x", "zzxqq");

        assertThat(searchAmong(ids(literal, wildcardWouldMatch), "zz_qq"))
                .as("_ 必须是字面量, 否则 zzxqq 会被一起带出来")
                .containsExactly(literal);
        assertThat(searchAmong(ids(literal, wildcardWouldMatch), "zzxqq"))
                .as("反过来, 不含 _ 的关键词仍然正常按字面量匹配")
                .containsExactly(wildcardWouldMatch);
    }

    // ========== NULL 别名 ==========

    /**
     * 别名是 NULL 的行(存量数据全是这样)不能被 OR 链整个吞掉.
     *
     * <p>SQL 里 {@code NULL LIKE '%x%'} 是 NULL 而不是 FALSE, 而 {@code TRUE OR NULL}
     * 才是 TRUE —— 只要有人把这条 OR 改写成别的形状(比如套一层 AND、或者用 COALESCE
     * 之外的方式"保护"它), 存量那 470 行会**整体从搜索结果里消失**, 包括按中文名搜.
     * 那比原来的 bug 严重得多, 所以这里专门用一行没有别名的数据把老路钉住.
     */
    @Test
    @DisplayName("没有别名的行(该列为 NULL)按中/日名照样搜得到")
    void rowsWithoutAliasesStillMatchByName() {
        int id = 97000010;
        seed(id, "オリジナル", "原创番");

        assertThat(searchAmong(ids(id), "原创番")).containsExactly(id);
        assertThat(searchAmong(ids(id), "オリジナル")).containsExactly(id);
        assertThat(searchAmong(ids(id), "nothing-matches-this")).isEmpty();
    }
}
