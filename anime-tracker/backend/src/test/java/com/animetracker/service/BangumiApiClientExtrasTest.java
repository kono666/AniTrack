package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 附属数据那三个接口的"取回"语义 —— 具体说, 是
 * <b>「这里确实没有东西」与「这次没取到」必须分开</b>.
 *
 * <p>这两件事在响应体上长得一模一样(都是空列表), 而它们的后果完全不同:
 * 前者是<b>确定的答案</b>, 值得记下来(marker 一落, 之后就不再问上游); 后者是<b>暂时的</b>,
 * 记下来就把一次瞬时故障固化成了产品状态 —— 那个条目会一直显示"没有角色", 直到 TTL
 * 到期, 而日志里只有一条 warn。
 *
 * <p>所以这个类的重点在 404 与 5xx 那一对: 删掉 404 那个分支, 前者会退化成
 * {@code complete=false}(从此一个不存在的条目每次都重问上游); 把两个 catch 合并成一个,
 * 后者会退化成 {@code complete=true}(从此一次抖动被当成结论)。
 */
class BangumiApiClientExtrasTest {

    private static final String BASE = "https://api.bgm.tv";
    private static final Integer SUBJECT = 8;

    private MockRestServiceServer server;
    private BangumiApiClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new BangumiApiClient(restTemplate, new BangumiApiProperties());
    }

    // ══════════ 正常取回 ══════════

    /**
     * 三个方法各自打自己的路径, 并且把元素解析出来。
     *
     * <p>三个路径写混(比如 persons 与 characters 对调)不会有任何编译期信号 ——
     * "制作人员"那一块会安静地显示成角色。所以这里逐个钉住 URL。
     */
    @Test
    @DisplayName("三个方法各打自己的路径, 元素解析得出来")
    void eachMethodHitsItsOwnPath() {
        server.expect(requestTo(BASE + "/v0/subjects/8/characters"))
                .andRespond(withSuccess("[{\"id\":1,\"name\":\"角色\",\"relation\":\"主角\","
                        + "\"images\":{\"grid\":\"https://lain.bgm.tv/pic/crt/g/1.jpg\"},"
                        + "\"actors\":[{\"id\":100,\"name\":\"声优\"}]}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v0/subjects/8/persons"))
                .andRespond(withSuccess("[{\"id\":2,\"name\":\"监督\",\"relation\":\"监督\"}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v0/subjects/8/subjects"))
                .andRespond(withSuccess("[{\"id\":3,\"name\":\"原名\",\"name_cn\":\"中文名\","
                        + "\"relation\":\"续集\"}]", MediaType.APPLICATION_JSON));

        BangumiApiClient.SubjectCollectionFetch<com.animetracker.dto.BangumiDTO.CharacterDTO> chars =
                client.getCharacters(SUBJECT);
        BangumiApiClient.SubjectCollectionFetch<com.animetracker.dto.BangumiDTO.PersonDTO> staff =
                client.getPersons(SUBJECT);
        BangumiApiClient.SubjectCollectionFetch<com.animetracker.dto.BangumiDTO.RelatedSubjectDTO> rels =
                client.getRelatedSubjects(SUBJECT);

        assertThat(chars.complete()).isTrue();
        assertThat(chars.items()).singleElement().satisfies(c -> {
            assertThat(c.getId()).isEqualTo(1);
            assertThat(c.getRelation()).isEqualTo("主角");
            assertThat(c.getImages().getGrid()).as("grid 是 V17 才补上的那一档")
                    .isEqualTo("https://lain.bgm.tv/pic/crt/g/1.jpg");
            assertThat(c.getActors()).singleElement()
                    .extracting(com.animetracker.dto.BangumiDTO.ActorDTO::getName)
                    .isEqualTo("声优");
        });
        assertThat(staff.items()).singleElement()
                .extracting(com.animetracker.dto.BangumiDTO.PersonDTO::getId).isEqualTo(2);
        // name_cn 靠 @JsonProperty 映射 —— 掉了这一处, 关联条目就只剩日文原名
        assertThat(rels.items()).singleElement()
                .extracting(com.animetracker.dto.BangumiDTO.RelatedSubjectDTO::getNameCn)
                .isEqualTo("中文名");
        server.verify();
    }

    /**
     * 上游明确回了一个空数组: <b>这是答案, 不是故障</b>。
     *
     * <p>实测 subject 21 的 /characters 就是 {@code []} —— 它确实没有角色。
     * {@code complete=true} 让调用方把 marker 写上, 从此不再问。
     */
    @Test
    @DisplayName("上游回 200 + []: complete=true(这是答案, 不是故障)")
    void emptyArrayIsACompleteAnswer() {
        server.expect(requestTo(BASE + "/v0/subjects/21/characters"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        BangumiApiClient.SubjectCollectionFetch<?> fetch = client.getCharacters(21);

        assertThat(fetch.complete()).isTrue();
        assertThat(fetch.items()).isEmpty();
        server.verify();
    }

    // ══════════ 「这里没有」与「这次没取到」 ══════════

    /**
     * <b>404 是确定答案。</b>
     *
     * <p>实测不存在的 subject 回 HTTP 404。它是"这个条目不在上游"(或者它确实没有这一块),
     * 而不是"这次没问到" —— 所以 {@code complete=true}, 于是 marker 会被写上,
     * 不会每次打开详情页都重问一次。
     *
     * <p>删掉 {@code HttpClientErrorException.NotFound} 那个分支, 这条会退化成
     * {@code complete=false}(掉进下面的 catch-all), 而其它用例一条都不红。
     */
    @Test
    @DisplayName("上游回 404: complete=true, 列表为空 —— 这是答案本身")
    void notFoundIsACompleteEmptyAnswer() {
        server.expect(requestTo(BASE + "/v0/subjects/99999999/characters"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        BangumiApiClient.SubjectCollectionFetch<?> fetch = client.getCharacters(99999999);

        assertThat(fetch.complete())
                .as("404 读成「这次没取到」的话, 一个不存在的条目会永远重问上游")
                .isTrue();
        assertThat(fetch.items()).isEmpty();
        server.verify();
    }

    /**
     * <b>5xx 是"这次没成"。</b>
     *
     * <p>{@code complete=false} 让调用方既不落库也不写 marker, 于是下次还会重试 ——
     * 这是"把一次瞬时故障固化成一个产品状态"的唯一一道闸。
     */
    @Test
    @DisplayName("上游回 500: complete=false(下次还要重试)")
    void serverErrorIsIncomplete() {
        server.expect(requestTo(BASE + "/v0/subjects/8/persons"))
                .andRespond(withServerError());

        BangumiApiClient.SubjectCollectionFetch<?> fetch = client.getPersons(SUBJECT);

        assertThat(fetch.complete()).isFalse();
        assertThat(fetch.items()).isEmpty();
        server.verify();
    }

    /** 连不上(超时/网络故障)走的是同一档: 这次没成 */
    @Test
    @DisplayName("连不上: complete=false")
    void networkFailureIsIncomplete() {
        server.expect(requestTo(BASE + "/v0/subjects/8/subjects"))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });

        BangumiApiClient.SubjectCollectionFetch<?> fetch = client.getRelatedSubjects(SUBJECT);

        assertThat(fetch.complete()).isFalse();
        assertThat(fetch.items()).isEmpty();
    }

    /**
     * 200 但响应体不是能解析的数组: 也算"这次没成"。
     *
     * <p>这类响应在本仓出过事(上游一次性回 HTML 错误页), 而它比 5xx 更危险 ——
     * 状态码是 2xx, 粗看一切正常。{@code complete=false} 让它落进"下次重试"那一档,
     * 而不是被当成"这个条目没有角色"永久记下来。
     */
    @Test
    @DisplayName("200 但响应体不是数组: complete=false, 不是「确实没有」")
    void unparsableBodyIsIncomplete() {
        server.expect(requestTo(BASE + "/v0/subjects/8/characters"))
                .andRespond(withSuccess("<html>502 Bad Gateway</html>", MediaType.TEXT_HTML));

        BangumiApiClient.SubjectCollectionFetch<?> fetch = client.getCharacters(SUBJECT);

        assertThat(fetch.complete()).isFalse();
        assertThat(fetch.items()).isEmpty();
        server.verify();
    }

    /**
     * 返回的列表<b>保证非 null</b> —— 调用方直接 for-each 或者交给 mapper。
     *
     * <p>上游回一个字面量 {@code null} 是能构造出来的(而不是不可能发生):
     * 那时 {@code get()} 回 null, 少了那道兜底, 调用方就会拿到 null 并在
     * {@code src.size()} 上抛 NPE —— 而那个异常发生在落库之前, 表现是"这块永远取不到"。
     */
    @Test
    @DisplayName("上游回字面量 null 时, 给的是空列表而不是 null")
    void literalNullBodyYieldsEmptyList() {
        server.expect(requestTo(BASE + "/v0/subjects/8/persons"))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        BangumiApiClient.SubjectCollectionFetch<?> fetch = client.getPersons(SUBJECT);

        assertThat(fetch.items()).isNotNull().isEmpty();
    }
}
