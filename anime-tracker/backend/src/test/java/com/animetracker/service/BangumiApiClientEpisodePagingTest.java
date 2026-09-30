package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 剧集回源的分页行为.
 *
 * <p>改动前这里只有一页 (URL 上写死 limit=100, 没有 offset), 所以一个"翻页"的测试都不存在
 * —— 这些用例是跟着修复一起长出来的. 它们守的是翻页的几件容易做错的事:
 * 收齐没有、什么时候算停、每页失败算不算完整、以及 offset 该往前挪多少.
 */
class BangumiApiClientEpisodePagingTest {

    private static final String BASE = "https://api.bgm.tv";
    private static final String EPISODES = BASE + "/v0/episodes";

    private MockRestServiceServer server;
    private BangumiApiClient client;

    /** 每次请求的 query, 按顺序记下来 —— 断言"请求了几次、offset 是多少"靠它. */
    private final List<String> queries = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new BangumiApiClient(restTemplate, new BangumiApiProperties());
        queries.clear();
    }

    @Test
    @DisplayName("500 集的番要翻 5 页, 每页 offset 依次是 0/100/200/300/400, 收齐才算完整")
    void pullsEveryPageUntilTotalReached() {
        stubPages(ExpectedCount.times(5), offset -> page(offset + 1, 100, 500));

        BangumiApiClient.EpisodeFetch fetch = client.getEpisodes(2782);

        assertThat(fetch.complete()).isTrue();
        assertThat(fetch.items()).hasSize(500);
        // 集号必须连续 —— 分页最容易出的错不是"少一页", 是"某两页重叠/中间跳过一截",
        // 而那种列表看上去是完整的. 顺带守住 sort -> ep 的映射没在翻页里错位.
        assertThat(fetch.items()).extracting(d -> d.getEp().intValue())
                .containsExactlyElementsOf(
                        IntStream.rangeClosed(1, 500).boxed().collect(Collectors.toList()));
        assertThat(queries).containsExactly(
                "subject_id=2782&type=0&limit=100&offset=0",
                "subject_id=2782&type=0&limit=100&offset=100",
                "subject_id=2782&type=0&limit=100&offset=200",
                "subject_id=2782&type=0&limit=100&offset=300",
                "subject_id=2782&type=0&limit=100&offset=400");
        server.verify();
    }

    @Test
    @DisplayName("一页就够的番只请求一次")
    void singlePageSubjectMakesOneRequest() {
        stubPages(ExpectedCount.times(1), offset -> page(1, 30, 30));

        BangumiApiClient.EpisodeFetch fetch = client.getEpisodes(3425);

        assertThat(fetch.complete()).isTrue();
        assertThat(fetch.items()).hasSize(30);
        assertThat(queries).hasSize(1);
        server.verify();
    }

    @Test
    @DisplayName("服务端把每页压小时, offset 按实际拿到的条数走 —— 不能跳集")
    void advancesByActualPageSizeNotRequestedSize() {
        // 第一页只给 60 条(total 仍说 500). 若按"请求的 100"推进, 第二页会从 100 开始,
        // 第 61..100 集就这么没了 —— 而且收回来的是个有洞但看着完整的列表.
        stubPages(ExpectedCount.times(2), offset -> offset == 0 ? page(1, 60, 500) : page(0, 0, 500));

        BangumiApiClient.EpisodeFetch fetch = client.getEpisodes(2782);

        assertThat(queries.get(1)).contains("offset=60");
        assertThat(fetch.items()).hasSize(60);
        assertThat(fetch.items()).extracting(d -> d.getEp().intValue()).startsWith(1, 2, 3);
        // 空页 = 走到底了, 所以算完整 —— 服务端自己说只有这么多, 那是它的实话
        assertThat(fetch.complete()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("中途某页失败: 已拿到的照常返回, 但 complete=false —— 这批不能当完整缓存")
    void pageFailureLeavesFetchIncomplete() {
        server.expect(ExpectedCount.times(2), requestTo(startsWith(EPISODES)))
                .andRespond(request -> {
                    queries.add(request.getURI().getQuery());
                    if (offsetOf(request.getURI().getQuery()) == 0) {
                        return withSuccess(page(221, 100, 500), MediaType.APPLICATION_JSON)
                                .createResponse(request);
                    }
                    return withServerError().createResponse(request);
                });

        BangumiApiClient.EpisodeFetch fetch = client.getEpisodes(2782);

        assertThat(fetch.items()).hasSize(100);     // 第一页没白拿
        assertThat(fetch.complete()).isFalse();     // 但也没收齐
        server.verify();
    }

    @Test
    @DisplayName("total 报错(或虚高)时撞上翻页上限就停, 不会一直请求下去")
    void stopsAtPageCap() {
        // 每页都给满 100 条, total 却报 999999 —— 没人拦着的话这里会一直转到天荒地老.
        stubPages(ExpectedCount.times(50), offset -> page(offset + 1, 100, 999_999));

        BangumiApiClient.EpisodeFetch fetch = client.getEpisodes(1);

        assertThat(queries).hasSize(50);
        assertThat(fetch.items()).hasSize(5000);
        assertThat(fetch.complete()).isFalse();     // 撞上限 = 没翻完, 一样不许落库
        server.verify();
    }

    // ==================== 辅助 ====================

    /** 按 URL 里的 offset 应答, 并记下每次的 query. */
    private void stubPages(ExpectedCount count, IntFunction<String> handler) {
        server.expect(count, requestTo(startsWith(EPISODES)))
                .andRespond(request -> {
                    String query = request.getURI().getQuery();
                    queries.add(query);
                    return withSuccess(handler.apply(offsetOf(query)), MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });
    }

    private static int offsetOf(String query) {
        for (String kv : query.split("&")) {
            if (kv.startsWith("offset=")) {
                return Integer.parseInt(kv.substring("offset=".length()));
            }
        }
        throw new AssertionError("URL 上没有 offset: " + query);
    }

    /** 造一页剧集: 集号从 fromInclusive 起连续 count 条, total 报 total. */
    private static String page(int fromInclusive, int count, int total) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            int no = fromInclusive + i;
            sb.append("{\"id\":").append(10_000 + no)
                    .append(",\"type\":0,\"sort\":").append(no)
                    .append(",\"name\":\"ep-").append(no).append("\"}");
        }
        return sb.append("],\"total\":").append(total).append('}').toString();
    }
}
