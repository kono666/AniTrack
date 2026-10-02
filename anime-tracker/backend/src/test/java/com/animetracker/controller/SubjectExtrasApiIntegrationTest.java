package com.animetracker.controller;

import com.animetracker.config.SubjectExtrasRateLimiter;
import com.animetracker.dto.BangumiDTO.ActorDTO;
import com.animetracker.dto.BangumiDTO.CharacterDTO;
import com.animetracker.dto.BangumiDTO.ImagesDTO;
import com.animetracker.dto.BangumiDTO.PersonDTO;
import com.animetracker.dto.BangumiDTO.RelatedSubjectDTO;
import com.animetracker.service.BangumiApiClient;
import com.animetracker.service.BangumiApiClient.SubjectCollectionFetch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 附属数据那三个公开端点的端到端表现 —— 走完整过滤器链、参数绑定、异常处理, 真库真事务.
 *
 * <p>上游用替身, 其余(service / writer / 四张表 / JPA / Flyway)全是真的。这样这一层
 * 顺带证明了三件事: 实体注解与 V17 的 DDL 对得上({@code ddl-auto=validate} 起不来就会红)、
 * 三个端点在 {@code SecurityConfig} 的公开路径下真的不需要登录、以及响应体是项目统一的
 * {@code {code,message,data}} 结构。
 *
 * <p><b>这个类守的核心是"空"的两种含义在协议层就分开了:</b>
 * <ul>
 *   <li>上游明确说没有(404, 或一个真的空数组) → <b>200 + {@code []}</b>, 前端据此
 *       整段静默隐藏;</li>
 *   <li>这次没取到、库里也没有旧数据 → <b>502</b>, 前端据此显示"加载失败 · 重试"。</li>
 * </ul>
 * 两者都渲染成 200 + 空数组的话, 上游抖一下, 用户看到的就是那一块内容<b>无声无息地
 * 消失</b> —— 页面其余部分完好、没有报错、也没有任何可以点的东西, 他只会以为这部番
 * 没收录角色。
 *
 * <p>⚠️ 这里只断言 HTTP 状态码与响应体, 不断言"前端会怎么渲染" —— 那是
 * {@code AnimeDetail} 那几个用例的事。刻意不让这一层去替前端做决定。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-subject-extras-api;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 启动预加载会去 api.bgm.tv 拉数据, 与这条用例无关
        "anitrack.preload.enabled=false",
        // 默认 60 在用例里撞不到; 压到 3 才可能验到 429 而不用发几十个请求
        "anitrack.subject-extras.rate-limit-per-minute=3"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SubjectExtrasApiIntegrationTest {

    private static final Integer SUBJECT = 8001;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SubjectExtrasRateLimiter rateLimiter;
    @Autowired
    private JdbcTemplate jdbc;

    /** 上游整个换成替身: 这一层要量的是"协议上怎么回", 不是"上游怎么解析" */
    @MockBean
    private BangumiApiClient apiClient;

    @BeforeEach
    void reset() {
        // 限流计数是单例里的状态, 而上下文被本类多个用例共用 —— 不清的话第二个用例
        // 会拿着第一个用例剩下的配额开始
        rateLimiter.reset();
        jdbc.execute("DELETE FROM subject_character");
        jdbc.execute("DELETE FROM subject_character_actor");
        jdbc.execute("DELETE FROM subject_staff");
        jdbc.execute("DELETE FROM subject_relation");
        jdbc.execute("DELETE FROM subject_extras");
    }

    /** MockMvc 默认来源是 127.0.0.1, 所有用例共用一个桶 —— 所以要能指定来源 IP */
    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    // ── 造上游数据 ──────────────────────────────────────

    private static ImagesDTO images(String grid) {
        ImagesDTO dto = new ImagesDTO();
        dto.setGrid(grid);
        return dto;
    }

    private static CharacterDTO character(Integer id, String name) {
        CharacterDTO dto = new CharacterDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setRelation("主角");
        // 白名单内的地址 —— 响应里它必须变成 /api/img?url=…
        dto.setImages(images("https://lain.bgm.tv/pic/crt/g/" + id + ".jpg"));
        ActorDTO actor = new ActorDTO();
        actor.setId(100 + id);
        actor.setName("声优" + id);
        dto.setActors(List.of(actor));
        return dto;
    }

    private static PersonDTO person(Integer id, String name) {
        PersonDTO dto = new PersonDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setRelation("监督");
        return dto;
    }

    private static RelatedSubjectDTO related(Integer id, String name) {
        RelatedSubjectDTO dto = new RelatedSubjectDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setNameCn("中文名");
        dto.setRelation("续集");
        return dto;
    }

    private static <T> SubjectCollectionFetch<T> ok(List<T> items) {
        return new SubjectCollectionFetch<>(items, true);
    }

    private static <T> SubjectCollectionFetch<T> incomplete() {
        return new SubjectCollectionFetch<>(List.of(), false);
    }

    private long count(String table) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE subject_id = ?", Long.class, SUBJECT);
        return n == null ? 0 : n;
    }

    private Long markerRows() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM subject_extras WHERE subject_id = ?", Long.class, SUBJECT);
    }

    // ══════════ 正常路径 ══════════

    /**
     * 冷启动那一次: 200 + 数据, 而且真的落了库。
     *
     * <p>图片那条断言必须是"等于 {@code /api/img?url=…}"而不是"非空" ——
     * {@code CoverImages.proxied()} 对白名单外的地址<b>原样返回</b>, 所以选错档、
     * 或者 DTO 忘了过代理, 断言非空照样绿。
     */
    @Test
    @DisplayName("角色: 冷启动回源一次, 200 + 数据, 并且落了库")
    void charactersAreFetchedStoredAndServed() throws Exception {
        when(apiClient.getCharacters(SUBJECT))
                .thenReturn(ok(List.of(character(1, "角色甲"), character(2, "角色乙"))));

        mockMvc.perform(get("/api/bangumi/subject/8001/characters").with(from("198.51.100.11")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("角色甲"))
                .andExpect(jsonPath("$.data[0].relation").value("主角"))
                .andExpect(jsonPath("$.data[0].actors[0].name").value("声优1"))
                .andExpect(jsonPath("$.data[0].image")
                        .value(org.hamcrest.Matchers.startsWith("/api/img?url=")));

        verify(apiClient, times(1)).getCharacters(SUBJECT);
        org.assertj.core.api.Assertions.assertThat(count("subject_character")).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(count("subject_character_actor")).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(markerRows())
                .as("完整取回要写 marker, 否则每次打开详情页都重问一遍上游")
                .isEqualTo(1);
    }

    /** 第二次请求不该再打上游 —— 这条是"marker 真的生效了"在 HTTP 这一层的证明 */
    @Test
    @DisplayName("紧接着再请求一次: 一次上游都不打, 服务端还是 200 + 同一份数据")
    void theSecondRequestDoesNotHitTheUpstream() throws Exception {
        when(apiClient.getCharacters(SUBJECT)).thenReturn(ok(List.of(character(1, "角色甲"))));

        mockMvc.perform(get("/api/bangumi/subject/8001/characters").with(from("198.51.100.12")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/bangumi/subject/8001/characters").with(from("198.51.100.12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("角色甲"));

        verify(apiClient, times(1)).getCharacters(SUBJECT);
    }

    /** 制作人员与关联条目这两条也各通一次 —— 三个端点接错上游方法不会有编译期信号 */
    @Test
    @DisplayName("制作人员与关联条目各自 200")
    void staffAndRelationsAreServed() throws Exception {
        when(apiClient.getPersons(SUBJECT)).thenReturn(ok(List.of(person(2, "监督甲"))));
        when(apiClient.getRelatedSubjects(SUBJECT)).thenReturn(ok(List.of(related(3, "前作"))));

        mockMvc.perform(get("/api/bangumi/subject/8001/staff").with(from("198.51.100.13")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("监督甲"))
                .andExpect(jsonPath("$.data[0].relation").value("监督"));

        mockMvc.perform(get("/api/bangumi/subject/8001/relations").with(from("198.51.100.13")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("前作"))
                .andExpect(jsonPath("$.data[0].nameCn").value("中文名"))
                .andExpect(jsonPath("$.data[0].relation").value("续集"));

        verify(apiClient, times(1)).getPersons(SUBJECT);
        verify(apiClient, times(1)).getRelatedSubjects(SUBJECT);
    }

    /**
     * 上游明确说"这里没有" → <b>200 + 空数组</b>。
     *
     * <p>这不是错误: 前端拿到空数组就把那一整块静默隐藏 —— 一部没有角色数据的番,
     * 底下挂一行"没有角色"只是在提示用户这里本该有东西。
     */
    @Test
    @DisplayName("上游完整取回但为空: 200 + [], 而不是 502")
    void emptyButCompleteIsASuccess() throws Exception {
        when(apiClient.getCharacters(SUBJECT)).thenReturn(ok(List.of()));

        mockMvc.perform(get("/api/bangumi/subject/8001/characters").with(from("198.51.100.14")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(0));

        org.assertj.core.api.Assertions.assertThat(markerRows())
                .as("空也是一个确定的答案, 要记下来")
                .isEqualTo(1);
    }

    // ══════════ 取不到 ══════════

    /**
     * <b>这次没取到、库里也没有旧数据 → 502。</b>
     *
     * <p>回 200 + 空数组的话, 这一块会静默消失 —— 用户看到一个残缺的页面,
     * 却没有任何可以点的地方去重试。502 让前端能画出"加载失败 · 重试"。
     *
     * <p>HTTP 状态与响应体里的 {@code code} 都是 502: 前端靠 axios 的 reject 判断
     * (它不看 {@code code}), 那个字段是给读日志的人看的。
     */
    @Test
    @DisplayName("没取到且库里没东西: 502, 且不写 marker")
    void upstreamFailureWithNothingLocalIsABadGateway() throws Exception {
        when(apiClient.getCharacters(SUBJECT)).thenReturn(incomplete());

        mockMvc.perform(get("/api/bangumi/subject/8001/characters").with(from("198.51.100.15")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502))
                .andExpect(jsonPath("$.message").value("上游暂时取不到, 请稍后重试"))
                .andExpect(jsonPath("$.data").doesNotExist());

        org.assertj.core.api.Assertions.assertThat(markerRows())
                .as("marker 落了的话这次故障就被固化成一整天的产品状态")
                .isZero();
        org.assertj.core.api.Assertions.assertThat(count("subject_character")).isZero();
    }

    /**
     * 取不到但库里有上一版 → <b>照常 200</b>。
     *
     * <p>陈旧的角色表比一个错误提示有用, 而且"上游挂了"不该让用户连已经看到过的
     * 那几个角色都看不见。
     */
    @Test
    @DisplayName("没取到但库里有旧数据: 200 + 旧数据, 不是 502")
    void staleDataIsServedWhenTheUpstreamFails() throws Exception {
        jdbc.update("INSERT INTO subject_extras (subject_id, characters_fetched_at) VALUES (?, ?)",
                SUBJECT, LocalDateTime.now().minusHours(25));
        jdbc.update("INSERT INTO subject_character "
                        + "(subject_id, character_id, name, relation, image, sort_order) "
                        + "VALUES (?, 1, '上次取到的角色', '主角', NULL, 0)",
                SUBJECT);
        when(apiClient.getCharacters(SUBJECT)).thenReturn(incomplete());

        mockMvc.perform(get("/api/bangumi/subject/8001/characters").with(from("198.51.100.16")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("上次取到的角色"));

        org.assertj.core.api.Assertions.assertThat(count("subject_character"))
                .as("没取全时不许动旧行 —— 删了又插不进去, 这一块就空了")
                .isEqualTo(1);
    }

    // ══════════ 限流与参数校验 ══════════

    /**
     * 这三个端点在 {@code SecurityConfig.publicPaths} 里免登录, 而缓存过期时会直接
     * 驱动一次上游调用 —— 所以限流不是装饰。
     */
    @Test
    @DisplayName("同一个 IP 超过配额后 429, 响应体是统一结构")
    void theQuotaIsEnforced() throws Exception {
        when(apiClient.getCharacters(any())).thenReturn(ok(List.of()));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/bangumi/subject/8001/characters").with(from("198.51.100.17")))
                    .andExpect(status().isOk());
        }

        // 第 4 个请求换一个从没碰过的条目: 这样"限流发生在业务之前"有一个可观测的后果 ——
        // 用同一个条目的话, 前三次已经把 marker 写上了, 不打上游是缓存的效果, 不是限流的
        mockMvc.perform(get("/api/bangumi/subject/8002/characters").with(from("198.51.100.17")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message")
                        .value("加载附属信息太频繁了, 请稍等一分钟再试 (当前上限 3 次/分钟)"));

        verify(apiClient, never()).getCharacters(8002);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM subject_extras WHERE subject_id = 8002", Long.class))
                .as("被限流的请求不该在库里留下任何痕迹")
                .isZero();
    }

    /** 换一个 IP 就是另一份配额: 别人撞墙不该连累我 */
    @Test
    @DisplayName("另一个 IP 的配额不受影响")
    void anotherIpKeepsItsOwnQuota() throws Exception {
        when(apiClient.getPersons(SUBJECT)).thenReturn(ok(List.of()));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/bangumi/subject/8001/staff").with(from("198.51.100.18")));
        }
        mockMvc.perform(get("/api/bangumi/subject/8001/staff").with(from("198.51.100.18")))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/api/bangumi/subject/8001/staff").with(from("203.0.113.99")))
                .andExpect(status().isOk());
    }

    /**
     * {@code subjectId} 的下界。
     *
     * <p>与 search 的 page 同一个理由: 0 或者负数一路走到上游就是一次必然失败的请求
     * (而且在 {@code /v0/subjects/0/characters} 上得到 404 —— 它会被读成"这个条目确实
     * 没有角色"并写进 marker, 于是库里留下一条关于一个不存在的条目的结论)。
     */
    @Test
    @DisplayName("subjectId 是 0: 400, 不打上游")
    void subjectIdMustBePositive() throws Exception {
        mockMvc.perform(get("/api/bangumi/subject/0/characters").with(from("198.51.100.19")))
                .andExpect(status().isBadRequest());

        verify(apiClient, never()).getCharacters(any());
    }
}
