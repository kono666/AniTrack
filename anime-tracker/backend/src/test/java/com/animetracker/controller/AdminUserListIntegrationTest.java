package com.animetracker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/admin/users} 的对外契约: 形状、分页、关键词、筛选、排序、兜底、403.
 *
 * <p><b>为什么这一组必须走 HTTP, 而 {@code AdminServiceTest} 里的 mock 桩不够.</b>
 * service 层的桩问的是"发给仓储的参数是什么", 而这里有四类事情根本不在那一层:
 * {@code @Min}/{@code @Max} 要靠类级 {@code @Validated} 才装配, 少了它约束被静默忽略、
 * 接口照样 200; 排序拼错的 JPQL 要真的交给 H2 编译才知道; {@code ESCAPE '!'} 少了
 * 一半, 模式串在 Java 里长得一模一样、只有数据库才认得出来; 非管理员那条 403 更是
 * 完全跨在过滤器链上. 这些错的共同点是**接口返回 200**, 所以它们不会以异常的形式
 * 出现在任何日志里 —— 只有把答案和具体数据对着看才发现得了.
 *
 * <p><b>数据是自己灌的.</b> dev 只有 admin / test 两个账号, 两个账号验不了分页
 * (第 2 页永远空), 也验不了"筛掉一部分"这类断言. 灌的是 {@code tu} 开头的 51 行,
 * 每条断言都只在 {@code keyword=tu_} 的这个子集里做, 于是库里将来多了什么账号都不影响.
 *
 * <p>灌数据走的是 JDBC 而不是 {@code UserRepository}: 实体上的 {@code @PrePersist}
 * 会把 {@code createdAt} 改写成 {@code now()}, 51 行灌完时间戳几乎全撞在一起, 而
 * 默认排序正是按它排的 —— 那样"第 2 页接在第 1 页后面"这类断言会变成在测 tiebreaker,
 * 而不是在测排序. 显式写死 {@code created_at} 才能让 51 行有一个确定的顺序.
 *
 * <p>为什么用 MockMvc: 要验的就是状态码、响应体与过滤器链, 不涉及连接器行为.
 * 内存库跑 dev profile, 并关掉启动预加载 —— 否则这个 JVM 会去调 api.bgm.tv,
 * 让一组本地用例依赖外网.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-admin-user-list;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AdminUserListIntegrationTest {

    /** 灌进去的基准时间. 取未来是为了和 dev 自己那两个账号(真实注册时间)不撞在一起 */
    private static final LocalDateTime BASE = LocalDateTime.of(2030, 1, 1, 0, 0);

    /** {@code keyword=tu_} 这一个子集的行数 —— 下面几乎所有断言的分母 */
    private static final int SCOPED_TOTAL = 51;

    /**
     * 只有这一个账号的邮箱不是"用户名@example.com", 用来把"关键词也搜邮箱"这一半
     * 单独钉住: 否则所有邮箱都含用户名, 删掉 {@code OR LOWER(u.email) LIKE ...}
     * 也照样绿.
     */
    private static final String ALIAS_EMAIL = "mailbox-alias@example.com";

    /** 用户名里带 {@code %}: 少了 ESCAPE 它会从字面量变回通配符, 搜一个 % 就命中全表 */
    private static final String PERCENT_USER = "tu_pct%name";

    /**
     * 唯一一个**没有邮箱**的账号.
     *
     * <p>V1 建表时 email 是"唯一但不 NOT NULL", 注释里写着"历史数据里存在空邮箱的行"
     * (引导账号创建时不经过注册接口) —— 所以这不是编出来的状态.
     *
     * <p>它存在的唯一理由是给"关键词也搜用户名"这一半一个**没有邮箱兜底**的样本:
     * 其余账号的邮箱都是"用户名@example.com", 于是 {@code LOWER(u.username) LIKE …}
     * 里的 LOWER 被去掉之后, 大小写不匹配的查询会从邮箱那一半命中同一行,
     * 断言照样绿 —— 一个 44 行的种子集里, 漏掉的是整个用户名分支.
     */
    private static final String NO_EMAIL_USER = "tu_003";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 只给"最近登录"那条端到端用例用: 种子里 51 行的密码哈希是占位串, 登录不了,
     * 而那一条必须走一次**真登录**才谈得上"写路径有没有接上".
     *
     * <p>用的是容器里那一个, 不是 {@code new BCryptPasswordEncoder()} —— 登录比对走的是
     * 装配出来的这个 bean, 自己造一个就等于假设两者算法与强度永远一致.
     */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * 管理员 token, **整个类共用一次**.
     *
     * <p>登录是按 IP 限流的(默认 10 次/分钟, 见 {@code AuthRateLimiter}), 而这个类
     * 每个用例都要一个管理员 token —— 逐用例登录会在第 11 个用例上拿到 429, 而失败
     * 信息是"登录失败", 与被测的那条接口毫无关系, 排查时很容易往错的方向找.
     * token 是自包含的 JWT, 跨用例复用没有任何副作用(每轮重新灌的是种子数据, 不是它).
     */
    private static String adminToken;

    /**
     * 一行种子: 用户名 + 角色 + 状态 + 锁定还剩几天(负数=已经过期, null=没锁过).
     *
     * <p>灌进去的顺序**就是** {@code created_at} 的顺序, 所以下面断言里的"最新那个"
     * 一律指这张表里最后一行. 加行只能往后加, 不要往中间插 —— 中间插一行会让后面
     * 所有"第 N 页第一条是谁"全部平移.
     */
    private record Seed(String username, String role, String status, Integer lockDaysFromNow) {}

    private static List<Seed> seeds() {
        List<Seed> seeds = new ArrayList<>();
        for (int i = 1; i <= 45; i++) {
            seeds.add(new Seed(String.format("tu_%03d", i), "USER", "ACTIVE", null));
        }
        seeds.add(new Seed(PERCENT_USER, "USER", "ACTIVE", null));
        // 两个锁定期还在未来: 一个普通锁定, 一个**同时**是 DISABLED —— 后者是
        // "LOCKED 不叠加 status" 那条约定的落点
        seeds.add(new Seed("tu_locked_a", "USER", "ACTIVE", 1));
        seeds.add(new Seed("tu_locked_b", "USER", "DISABLED", 2));
        // 锁定期已过. 它钉的是 lockedUntil > :now 与"lockedUntil 非空"的区别 ——
        // 后者会把早已自动解锁的账号一直显示成「已锁定」, 管理员去点一个没用的解锁按钮
        seeds.add(new Seed("tu_expired", "USER", "ACTIVE", -1));
        seeds.add(new Seed("tu_disabled", "USER", "DISABLED", null));
        seeds.add(new Seed("tu_admin", "ADMIN", "ACTIVE", null));
        return seeds;
    }

    @BeforeEach
    void seedAndLogin() throws Exception {
        // 类里所有用例共用同一个 Spring 上下文, 也就共用同一个库 —— 先清掉上一轮灌的.
        // 不用 `LIKE 'tu_%'`: `_` 在 LIKE 里是单字符通配符, 而各库的默认转义字符
        // 并不统一(H2 的 MySQL 模式下是 `\`). `tu%` 里没有元字符, 到哪都一样.
        jdbc.update("DELETE FROM \"user\" WHERE username LIKE 'tu%'");

        List<Seed> seeds = seeds();
        List<Object[]> args = new ArrayList<>(seeds.size());
        for (int i = 0; i < seeds.size(); i++) {
            Seed s = seeds.get(i);
            args.add(new Object[]{
                    s.username(),
                    "$2a$10$thisIsNotAValidHashButTheColumnOnlyNeedsChars", // 从不拿它登录
                    emailOf(s.username()),
                    s.role(),
                    s.status(),
                    s.lockDaysFromNow() == null ? null : Timestamp.valueOf(LocalDateTime.now().plusDays(s.lockDaysFromNow())),
                    Timestamp.valueOf(BASE.plusMinutes(i)),
            });
        }
        jdbc.batchUpdate("INSERT INTO \"user\" "
                        + "(username, password, email, role, status, locked_until, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                args);

        if (adminToken == null) {
            adminToken = login("admin", "admin123");
        }
    }

    private static String emailOf(String username) {
        if ("tu_001".equals(username)) {
            return ALIAS_EMAIL;
        }
        return NO_EMAIL_USER.equals(username) ? null : username + "@example.com";
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    /** 以管理员身份发一次请求. 参数按 key,value 成对给. */
    private ResultActions getUsers(String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/admin/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    /** 发一次请求并取出 {@code data} 节点(顺带钉住 200 + code 200) */
    private JsonNode dataOf(String... params) throws Exception {
        String body = getUsers(params)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private static List<String> usernamesOf(JsonNode data) {
        List<String> names = new ArrayList<>();
        data.path("list").forEach(row -> names.add(row.path("username").asText()));
        return names;
    }

    private static int totalOf(JsonNode data) {
        return data.path("total").asInt();
    }

    /**
     * 只取一行的那几个用例的公共形状.
     *
     * <p>钉的是 {@code list} 只有一行, **不是** {@code total} 等于 1 —— 排序那组用的是
     * {@code limit=1}, 总匹配数仍是 51, 两者本来就不是一回事; 混在一起会让"排序"
     * 那几条断言其实在验分页.
     */
    private String onlyUsernameOf(String... params) throws Exception {
        JsonNode data = dataOf(params);
        assertThat(data.path("list").size()).isEqualTo(1);
        return data.path("list").get(0).path("username").asText();
    }

    // ========== 形状 ==========

    /**
     * 从裸数组改成 {@code {list,total,page}} 是一次**破坏性**变更, 前后端同轮发布.
     * 这条用例是那份契约在服务端这一侧的落点: 形状回退成数组的话, 前端
     * {@code res.data.data.list} 会拿到 undefined, 而它不会报错 —— 列表静悄悄地空掉.
     */
    @Test
    @DisplayName("data 是 {list,total,page} 信封, 不是裸数组; 每行九把键")
    void responseIsAPagedEnvelope() throws Exception {
        JsonNode data = dataOf("keyword", "tu_");

        assertThat(data.path("list").isArray()).isTrue();
        assertThat(totalOf(data)).isEqualTo(SCOPED_TOTAL);
        assertThat(data.path("page").asInt()).isEqualTo(1);
        // 不传 limit 就是默认的 20 —— 51 条只给第一页
        assertThat(data.path("list").size()).isEqualTo(20);

        JsonNode row = data.path("list").get(0);
        List<String> keys = new ArrayList<>();
        row.fieldNames().forEachRemaining(keys::add);
        // 这一个键集是**对外契约**. 加键必须走到这里来改, 别用 containsAll 把它放松掉
        assertThat(keys).containsExactlyInAnyOrder("id", "username", "email", "role",
                "status", "createdAt", "locked", "lockedUntil", "lastLoginAt");
    }

    // ========== 分页 ==========

    /**
     * 默认排序是注册时间倒序, 于是灌进去的最后一行(也就是 created_at 最大的那个)
     * 必须出现在第 1 页第 1 行.
     *
     * <p>这几条断言一起钉的还有 {@code PageRequest.of(safePage - 1, ...)} 那个 {@code - 1}:
     * 少了它, 第 1 页给的是第 2 页的内容 —— 而 {@code page=2} 又给第 3 页, 每一页都
     * 200、每页都是 20 条、翻页控件上的页码也一个不少, 只是**第 1 页的内容从此没人看得到**.
     */
    @Test
    @DisplayName("第 2 页紧接第 1 页, 不重叠也不跳行")
    void pagesDoNotOverlapAndStayInOrder() throws Exception {
        List<String> first = usernamesOf(dataOf("keyword", "tu_", "page", "1", "limit", "20"));
        List<String> second = usernamesOf(dataOf("keyword", "tu_", "page", "2", "limit", "20"));

        assertThat(first).hasSize(20);
        assertThat(second).hasSize(20);
        // 最新那个在最前(最后灌的), 第 1 页最后一行与第 2 页第一行是相邻的两行
        assertThat(first.get(0)).isEqualTo("tu_admin");
        assertThat(first.get(first.size() - 1)).isEqualTo("tu_032");
        assertThat(second.get(0)).isEqualTo("tu_031");
        assertThat(second.get(second.size() - 1)).isEqualTo("tu_012");

        Set<String> overlap = new HashSet<>(first);
        overlap.retainAll(second);
        assertThat(overlap).isEmpty();
    }

    /**
     * 越界的页: 200 + 空 list + **真实的 total**.
     *
     * <p>total 报 0 是最容易写出来的错(取页返回空, 顺手拿 {@code list.size()} 当总数),
     * 而且它看起来很自洽. 但前端是按 {@code ceil(total/limit)} 画翻页控件的 ——
     * 少报总数就等于**让用户从最后一页往回点不回去**, 页面上没有任何东西提示这件事.
     */
    @Test
    @DisplayName("page=99999 仍是 200, list 空, total 报真实的 51")
    void outOfRangePageStillReportsTheRealTotal() throws Exception {
        JsonNode data = dataOf("keyword", "tu_", "page", "99999", "limit", "20");

        assertThat(data.path("list").isArray()).isTrue();
        assertThat(data.path("list").size()).isZero();
        assertThat(totalOf(data)).isEqualTo(SCOPED_TOTAL);
        assertThat(data.path("page").asInt()).isEqualTo(99999);
    }

    // ========== 关键词 ==========

    @Test
    @DisplayName("不带任何参数能拿到行 —— 可选的筛选条件不能把整条 WHERE 变成假")
    void noParamsAtAllStillReturnsRows() throws Exception {
        JsonNode all = dataOf();

        // 一条恒假的 OR 分支会让这里变成 0 行, 而接口照样 200、界面上就是"这个站没有用户"
        assertThat(totalOf(all)).isGreaterThan(SCOPED_TOTAL);
        assertThat(all.path("list").size()).isEqualTo(20);
    }

    @Test
    @DisplayName("关键词同时搜用户名和邮箱, 且大小写不敏感")
    void keywordMatchesUsernameOrEmailCaseInsensitively() throws Exception {
        // 邮箱那一半: 没有任何用户名含 "mailbox-alias", 所以命中的只能是邮箱分支.
        // 大小写各来一次 —— 折叠交给数据库, 两边用同一套规则, 在 Java 里 toLowerCase
        // 再比会在规则不一致时(土耳其语 İ 那类)静默漏匹配
        assertThat(onlyUsernameOf("keyword", "mailbox-alias")).isEqualTo("tu_001");
        assertThat(onlyUsernameOf("keyword", "MAILBOX-ALIAS")).isEqualTo("tu_001");

        // 用户名那一半, 而且要挑一个**没有邮箱**的账号: 其他账号的邮箱都是
        // "用户名@example.com", 少了 LOWER 的查询会从邮箱那一半命中同一行,
        // 断言照样绿 —— 那样漏掉的是整个用户名分支
        assertThat(onlyUsernameOf("keyword", "TU_003")).isEqualTo(NO_EMAIL_USER);
    }

    /**
     * 关键词前后的空格不进模式串.
     *
     * <p>这条与下面那条是同一个挡板的两半, 但**失效方式不同**, 所以分开写:
     * 空白串(全空格)被 {@code isBlank()} 挡在 {@code trim()} 之前, 去掉 trim
     * 它照样走 null; 只有"包着有效内容的空格"才能验到 trim 本身.
     * 少了 trim 的话 {@code "  tu_045  "} 会拼出 {@code "%  tu_045  %"} ——
     * 一个看着像"没筛对"、实际什么都不匹配的条件, 而用户只是复制粘贴时多带了空格.
     */
    @Test
    @DisplayName("关键词前后的空格被去掉, 不参与匹配")
    void keywordIsTrimmed() throws Exception {
        assertThat(onlyUsernameOf("keyword", "  tu_045  ")).isEqualTo("tu_045");
    }

    /**
     * 关键词里那三个字符都是**字面量**: {@code %} 和 {@code _} 是 LIKE 的通配符,
     * {@code !} 是我们自己选的转义字符.
     *
     * <p>搜一个 {@code %} 若返回整张表, 界面上完全看不出是"通配符漏出来了"还是
     * "这个站的用户名里真的到处是 %" —— 两种解释都说得通, 于是没人会去查.
     */
    @Test
    @DisplayName("关键词里的 % 是字面量, 不是通配符")
    void percentInKeywordIsALiteral() throws Exception {
        assertThat(onlyUsernameOf("keyword", "%")).isEqualTo(PERCENT_USER);
    }

    @Test
    @DisplayName("关键词里的 _ 也是字面量: 搜 tu_ 命中的是带下划线的那些")
    void underscoreInKeywordIsALiteralToo() throws Exception {
        // `tu_045` 作为模式若没有 ESCAPE, `_` 会吃掉一个任意字符 —— 那样 `tuX045`
        // 也会命中; 这里用"多一个字符的名字不命中"来反证它是字面量
        int scoped = totalOf(dataOf("keyword", "tu_"));
        int withTrailing = totalOf(dataOf("keyword", "tu_045"));

        assertThat(scoped).isEqualTo(SCOPED_TOTAL);
        assertThat(withTrailing).isEqualTo(1);
    }

    /**
     * 空格串等于不筛, 而不是"匹配全部"也不是"什么都不匹配"。
     *
     * <p>{@code SearchPatterns.contains("")} 返回 {@code "%%"}(匹配全部), 而
     * {@code "   "} 会拼成 {@code "%   %"}(什么都不匹配). 这两种都错, 且错的方向相反:
     * 前者让一个空搜索框变成"没有条件", 后者让用户清空搜索框之后看到"没有匹配的用户"。
     */
    @Test
    @DisplayName("keyword 是空格串时等同于不筛, 与完全不传关键词拿到同一个 total")
    void blankKeywordMeansNoFilter() throws Exception {
        int blank = totalOf(dataOf("keyword", "   "));
        int none = totalOf(dataOf());

        assertThat(blank).isEqualTo(none);
        // 同时确认它真的**没在筛** —— 否则两边都错成 0 也会相等
        assertThat(blank).isGreaterThan(SCOPED_TOTAL);
    }

    // ========== 筛选 ==========

    @Test
    @DisplayName("role 白名单: 小写能认出来, 认不出来的当作不筛")
    void roleFilter() throws Exception {
        assertThat(totalOf(dataOf("keyword", "tu_", "role", "ADMIN"))).isEqualTo(1);
        // 大小写归一化: 手打的 URL 写小写不该变成"没有这个角色"
        assertThat(totalOf(dataOf("keyword", "tu_", "role", "user"))).isEqualTo(SCOPED_TOTAL - 1);
        // 原样下发的话 `WHERE u.role = 'SUPER'` 匹配零行 —— 界面显示"这个站没有用户"
        assertThat(totalOf(dataOf("keyword", "tu_", "role", "SUPER"))).isEqualTo(SCOPED_TOTAL);
    }

    @Test
    @DisplayName("status 三选一, 认不出来的当作不筛")
    void statusFilter() throws Exception {
        // 51 - (tu_locked_b + tu_disabled) 两个 DISABLED
        assertThat(totalOf(dataOf("keyword", "tu_", "status", "ACTIVE"))).isEqualTo(SCOPED_TOTAL - 2);
        assertThat(totalOf(dataOf("keyword", "tu_", "status", "disabled"))).isEqualTo(2);
        assertThat(totalOf(dataOf("keyword", "tu_", "status", "BOGUS"))).isEqualTo(SCOPED_TOTAL);
    }

    /**
     * {@code status=LOCKED} 是伪值, 落在 {@code locked_until} 那一列上, 且**不叠加**
     * {@code status} —— 这是拍板时接受的取舍: 表达不了「已锁定 且 已禁用」.
     *
     * <p>{@code tu_locked_b} 同时是 DISABLED, 它必须出现在 {@code LOCKED} 的结果里;
     * 叠加的话结果集是空的, 而列表空着的时候没人分得清是"没有锁定的账号"还是"条件写拧了".
     */
    @Test
    @DisplayName("status=LOCKED 落在锁定列上, 且不与 status 叠加")
    void lockedIsAPseudoValueThatDoesNotStack() throws Exception {
        JsonNode locked = dataOf("keyword", "tu_", "status", "LOCKED");

        assertThat(totalOf(locked)).isEqualTo(2);
        assertThat(usernamesOf(locked)).containsExactlyInAnyOrder("tu_locked_a", "tu_locked_b");
        locked.path("list").forEach(row ->
                assertThat(row.path("locked").asBoolean()).isTrue());
    }

    /**
     * 锁定期已过的账号**不是**已锁定.
     *
     * <p>只判 {@code locked_until IS NOT NULL} 也能让上面那条绿 —— 过期的锁定时间戳
     * 仍然留在字段里. 那一版的症状是管理员对着一个早就自动解锁的账号点「解锁」,
     * 点完什么也没变, 而列表上它一直挂着「已锁定」的徽章.
     */
    @Test
    @DisplayName("锁定期已过的账号不算已锁定, 也不出现在 LOCKED 的筛选结果里")
    void expiredLockIsNotLocked() throws Exception {
        JsonNode row = dataOf("keyword", "tu_expired").path("list").get(0);

        // 列表（含 LOCKED 筛选）里没有它, 且它自己那一行也说自己没锁
        assertThat(usernamesOf(dataOf("keyword", "tu_", "status", "LOCKED")))
                .doesNotContain("tu_expired");
        // 响应里的 lockedUntil 只在仍锁定时才给值 —— 前端拿它显示"锁到什么时候"
        assertThat(row.path("locked").asBoolean()).isFalse();
        assertThat(row.path("lockedUntil").isNull()).isTrue();
    }

    // ========== 排序 ==========

    /**
     * 四个方向 + 未知值的兜底, 每条只看第一行是谁.
     *
     * <p>{@code ?sort=username} 不带 order 必须是**正序**, 这条单独值一条: 前端把
     * {@code sort=username&order=asc} 当作默认组合、不写进 URL, 于是分享出去的链接
     * 就是光秃秃的 {@code ?sort=username}. 若这里按"不是 asc 就是 desc"处理, 那条链接
     * 会翻成倒序, 而点表头点出来的是正序 —— 同一个地址在两个人屏幕上显示两种顺序.
     */
    @Test
    @DisplayName("排序: 默认注册时间倒序; username 的自然首向是正序; 未知 sort 落回默认")
    void sorting() throws Exception {
        assertThat(onlyUsernameOf("keyword", "tu_", "limit", "1"))
                .isEqualTo("tu_admin");
        assertThat(onlyUsernameOf("keyword", "tu_", "limit", "1", "sort", "createdAt", "order", "asc"))
                .isEqualTo("tu_001");
        // 不带 order: 用该列的自然首向
        assertThat(onlyUsernameOf("keyword", "tu_", "limit", "1", "sort", "username"))
                .isEqualTo("tu_001");
        assertThat(onlyUsernameOf("keyword", "tu_", "limit", "1", "sort", "username", "order", "desc"))
                .isEqualTo(PERCENT_USER);
        // 认不出来就当没给, 不抛异常 —— 手改过的 URL 不该把页面变成错误屏
        assertThat(onlyUsernameOf("keyword", "tu_", "limit", "1", "sort", "bogus"))
                .isEqualTo("tu_admin");
        // 最近登录: 种子里这 51 行**全是 NULL**(灌数据时没写这一列), 所以两种方向下都按
        // u.id 升序 —— 三段式的第一键"有没有值"与兜底的 u.id 都是 ASC, 中间那键组内全相等.
        // 值本身不说明什么, 但它证明**这一支真的接上了**: 落回注册时间那支的话这里会是
        // tu_admin(created_at 最大的那个). 有值的行如何排在最前, 见下面那条端到端用例.
        assertThat(onlyUsernameOf("keyword", "tu_", "limit", "1", "sort", "lastLoginAt"))
                .isEqualTo("tu_001");
        assertThat(onlyUsernameOf("keyword", "tu_", "limit", "1", "sort", "lastLoginAt", "order", "asc"))
                .isEqualTo("tu_001");
    }

    /**
     * 端到端: 一次**真登录**之后, 那个账号在按最近登录排序的列表里排在最前, 且这一列非空.
     *
     * <p>这一条同时钉住两件失效方式完全不同的性质:
     *
     * <ul>
     *   <li><b>写路径真的接上了。</b> {@code UserService.markLoginSuccess} 若还留着"账号没失败
     *       记录就直接 return"的早退, 下面那句非空断言会红 —— 而**只有这一条会红**:
     *       全站绝大多数账号都是"干净"的, 其余任何用例都不问这一列, 后台那一列会静默地
     *       整片是「-」。</li>
     *   <li><b>从未登录过的行不会排在登录过的行前面。</b> 那 51 个 {@code tu_} 账号这一列
     *       全是 NULL, 一个都不能越过刚登录的这一个。⚠️ 这半句在 H2 上其实由
     *       {@code QueryCountIntegrationTest} 的 SQL 文本断言守着(H2 的 NULL 排序恰好与
     *       CASE 分组一致, 语义用例在这里证明不了那段代码存在), 本用例只是顺带看一眼。</li>
     * </ul>
     */
    @Test
    @DisplayName("真登录一次之后: 这一列有值, 且按最近登录排序时它压过所有从未登录的账号")
    void loginIsVisibleThroughTheLastLoginColumn() throws Exception {
        // 自己造一个能登录的账号: 种子里那 51 行的密码哈希是占位串, 从不拿它登录
        jdbc.update("INSERT INTO \"user\" (username, password, email, role, status, created_at) "
                        + "VALUES ('tu_never', ?, 'tu_never@example.com', 'USER', 'ACTIVE', ?)",
                passwordEncoder.encode("tu-login-pw"), Timestamp.valueOf(BASE.plusMinutes(999)));

        assertThat(rowOf(dataOf("keyword", "tu_never"), "tu_never").path("lastLoginAt").isNull())
                .as("灌进去时这一列是空的 —— 这是「从未登录过」的基准")
                .isTrue();

        login("tu_never", "tu-login-pw");

        assertThat(rowOf(dataOf("keyword", "tu_never"), "tu_never").path("lastLoginAt").isNull())
                .as("登录成功必须写这一列; 早退还在的话干净账号永远不会被记上")
                .isFalse();
        assertThat(onlyUsernameOf("keyword", "tu_", "limit", "1", "sort", "lastLoginAt"))
                .as("刚登录过的那个必须排在 51 个从未登录的账号前面")
                .isEqualTo("tu_never");
    }

    /** 从一页结果里挑出某个用户名的行 */
    private static JsonNode rowOf(JsonNode data, String username) {
        for (JsonNode row : data.path("list")) {
            if (username.equals(row.path("username").asText())) {
                return row;
            }
        }
        throw new AssertionError("列表里没有 " + username);
    }

    // ========== total 与 list 同源 ==========

    /**
     * {@code total} 必须跟着关键词走.
     *
     * <p>计数那条查询与前缀拼得一模一样、只差排序, 所以"count 忘了带条件"这件事
     * 只有把带条件与不带条件的 total 放在一起才看得见: 前者会等于后者(全表行数),
     * 于是前端按 total 画出来的翻页控件比实际结果集长得多, 用户翻到后面全是空页.
     */
    @Test
    @DisplayName("带关键词的 total 是真实匹配数, 而不是全表行数")
    void totalFollowsTheKeyword() throws Exception {
        int none = totalOf(dataOf());
        int scoped = totalOf(dataOf("keyword", "tu_"));
        int narrow = totalOf(dataOf("keyword", "tu_045"));

        assertThat(scoped).isEqualTo(SCOPED_TOTAL);
        assertThat(narrow).isEqualTo(1);
        assertThat(none).isGreaterThan(scoped);
    }

    // ========== 分页参数的边界 ==========

    /**
     * 类上的 {@code @Validated} 就是这个用例存在的全部理由.
     *
     * <p>没有它, 参数上的 {@code @Min}/{@code @Max} 被**静默忽略**, 下面三条全部变成
     * 200: {@code limit=100000} 会真的去拉十万行, {@code page=0} 会被 service 悄悄
     * 夹成第 1 页 —— 后者还算友善, 前者是一个不需要登录就能触发的资源放大.
     */
    @Test
    @DisplayName("page / limit 越界是 400, 不是被静默夹取")
    void pageAndLimitBoundsAreRejected() throws Exception {
        getUsers("page", "0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("页码从 1 开始"));

        getUsers("limit", "0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("每页至少 1 条"));

        getUsers("limit", "101").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("每页最多 100 条"));

        // 边界本身是合法的 —— 上下两条都 400, 一个"永远 400"的实现也能过
        getUsers("limit", "100").andExpect(status().isOk());
        getUsers("page", "1").andExpect(status().isOk());
    }

    // ========== 403 ==========

    /**
     * 普通用户拿到的是 403(已登录但权限不够), 不是 401 —— 401 会让前端清掉登录态、
     * 把这个人踢到登录页, 而他其实好好地登着; 也不是 500, 更不是一份用户名单.
     *
     * <p>用 {@code test}/{@code test123}(dev 种子里的演示账号)而不是造一个: 这条要
     * 钉的是"真的登进来了、但角色不对", 造一个不存在的账号会先被 401 拦掉, 验不到
     * 想验的那一层.
     *
     * <p>这条拦在 {@code SecurityConfig}({@code /api/admin/**} → hasRole ADMIN)上,
     * 而不是 {@code AdminService.checkAdmin} —— 那句"无管理员权限"是服务层自己的第二道
     * 防线, 只在安全配置被改松时才会显形. 所以这里断言的是安全层那句话.
     */
    @Test
    @DisplayName("普通用户访问用户列表: 403 而不是 401, 且不返回任何用户数据")
    void nonAdminIsForbidden() throws Exception {
        String userToken = login("test", "test123");

        String body = mockMvc.perform(get("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("没有权限执行该操作"))
                .andReturn().getResponse().getContentAsString();

        // 403 的响应体里不能夹带一份用户名单 —— 权限拦在 service 上, 而不是"查完再丢掉"
        assertThat(body).doesNotContain("username").doesNotContain("tu_");
    }
}
