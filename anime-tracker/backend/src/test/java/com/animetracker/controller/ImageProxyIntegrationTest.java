package com.animetracker.controller;

import com.animetracker.util.CoverImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/img} 的对外契约 —— 尤其是"它必须免登录"这一条。
 *
 * <p>这个类里最要紧的是 {@link #badAddressIs400Not401()}: 它就是
 * {@code SecurityConfig} 里那条 {@code "/api/img"} 登记的**唯一哨兵**。删掉那条登记,
 * 它会从 400 变成 401, 当场红; 而线上出这个问题时没有任何其他迹象 ——
 * 首屏全是兜底图, 看起来像"上游挂了"或"代理写错了"。
 *
 * <p>那条用例刻意用一个**非白名单**的地址: 它在到达上游之前就被拒, 所以整条链不需要
 * 真的联网。这正是拿它当哨兵的另一个好处 —— 一条要靠 mock 上游才能跑的用例,
 * 在"忘了配 mock"时会因为别的原因失败, 哨兵就不灵了。
 *
 * <p>{@link #fetchesTheImage()} 与 {@link #upstreamHeadersAreNotForwarded()} 才用 mock
 * 上游, 它们验的是代理**回**给浏览器的那些头。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-imgproxy-http;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ImageProxyIntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 9, 8, 7, 6};

    /**
     * <b>每个用例一个不同的地址 —— 这不是凑数, 是隔离。</b>
     *
     * <p>{@code CoverImageService} 是容器里的单例, 它那个缓存<b>跨用例活着</b>。
     * 两个用例用同一个地址时, 后一个会命中前一个留下的缓存, 于是它登记的期望一次都不会
     * 被消费 —— 症状是"期望没被满足"(有 {@code verify()} 时)或者更坏的:
     * <b>拿到上一个用例的字节却断言通过</b>。实测踩到过: {@code upstreamFailureIs502}
     * 因为地址与另一个用例相同, 拿到缓存里那张 200 的图, 断言 502 → 实测 200。
     *
     * <p>用不同的地址比"每个用例清一次缓存"更好: 不用为测试在生产代码上开一个
     * 清缓存的入口, 而那种入口本身也没什么别的用处。
     */
    private static final String FETCH_URL = "https://lain.bgm.tv/pic/cover/l/aa/01/fetch.png";
    private static final String HEADERS_URL = "https://lain.bgm.tv/pic/cover/l/bb/02/headers.png";
    private static final String FAIL_URL = "https://lain.bgm.tv/pic/cover/l/cc/03/fail.png";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RestTemplate imageRestTemplate;

    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        // 绑定在**容器里那个** bean 上: service 拿的就是同一个实例, 于是这一路真的走通了
        server = MockRestServiceServer.bindTo(imageRestTemplate).build();
    }

    /**
     * <b>免登录名单的哨兵: 匿名访问必须走到 controller, 拿到 400 而不是 401。</b>
     *
     * <p>不带 Authorization 头, 地址故意用一个不在白名单里的(于是不用联网就能判)。
     * 400 证明请求穿过了 Spring Security; 401 说明它被挡在门外了。
     */
    @Test
    @DisplayName("匿名访问: 400(走到了 controller)而不是 401 —— 免登录名单那条登记的哨兵")
    void badAddressIs400Not401() throws Exception {
        mockMvc.perform(get(CoverImages.PROXY_PATH).param("url", "https://evil.com/pic/x.jpg"))
                .andExpect(status().isBadRequest());
    }

    /** 少了 url 参数: 400 带人话, 不是 500 */
    @Test
    @DisplayName("没带 url: 400 且说得出缺了哪个参数")
    void missingParameterIs400() throws Exception {
        mockMvc.perform(get(CoverImages.PROXY_PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("url")));
    }

    /**
     * 端到端取一张图: 字节相同、类型是图片、带长缓存与 {@code nosniff}。
     *
     * <p>{@code nosniff} 那一条断言不是走过场: 代理与站点同源, 浏览器一旦对响应做
     * 类型嗅探, 一张"看起来像 html"的图片就可能被当页面执行。这条头与
     * {@code CoverImageService} 里的 Content-Type 白名单是同一件事的两半。
     */
    @Test
    @DisplayName("取到图: 200 + 字节相同 + 图片类型 + 长缓存 + nosniff")
    void fetchesTheImage() throws Exception {
        server.expect(requestTo(FETCH_URL)).andRespond(withSuccess(PNG, MediaType.IMAGE_PNG));

        byte[] body = mockMvc.perform(get(CoverImages.PROXY_PATH).param("url", FETCH_URL))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=604800")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEqualTo(PNG);
        server.verify();
    }

    /**
     * <b>只回我们自己设的头, 不照抄上游的。</b>
     *
     * <p>把上游的响应头原样转出去是这类代理的经典错法, 而其中最坏的一个是
     * {@code Set-Cookie}: 上游一旦能往我们的域里写 cookie, 它就能影响本站的会话
     * (cookie 不区分端口, 也不区分"这张图是谁给的")。这类错误在功能上完全看不出来,
     * 页面上一切正常。
     */
    @Test
    @DisplayName("上游的 Set-Cookie 不会被透传给浏览器")
    void upstreamHeadersAreNotForwarded() throws Exception {
        server.expect(requestTo(HEADERS_URL))
                .andRespond(withSuccess(PNG, MediaType.IMAGE_PNG)
                        .header(HttpHeaders.SET_COOKIE, "evil=1; Path=/")
                        .header("X-Upstream-Secret", "internal"));

        var response = mockMvc.perform(get(CoverImages.PROXY_PATH).param("url", HEADERS_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(response.getHeader("X-Upstream-Secret"))
                .as("上游的自定义头同样不该出现 —— 这里不是它的传声筒")
                .isNull();
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .as("我们自己的缓存头要在")
                .isNotNull();
    }

    /** 上游挂了: 502 —— 不是 500(我们的 bug)也不是 200(空图) */
    @Test
    @DisplayName("上游取不到: 502")
    void upstreamFailureIs502() throws Exception {
        server.expect(requestTo(FAIL_URL))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY));

        mockMvc.perform(get(CoverImages.PROXY_PATH).param("url", FAIL_URL))
                .andExpect(status().isBadGateway());
    }

    /**
     * 非白名单的地址**原样**留在响应里, 前端因此继续直连 (与改动前一致)。
     *
     * <p>这一条在这里是为了把"白名单"这件事的另一半钉住: 不是"所有封面都改走代理",
     * 而是"能代取的走代理, 代取不了的一个字都不动"。库里那批 {@code http://} 老数据
     * 靠的就是这个 —— 否则它们会从一个必然 400 的代理地址上取图, 从"还能显示"变成
     * "一定不显示"。
     */
    @Test
    @DisplayName("白名单外的地址: proxied 原样返回(这条是纯函数的, 在这里只是就近留个记录)")
    void nonWhitelistedStaysUntouched() {
        List<String> untouched = List.of(
                "http://lain.bgm.tv/pic/cover/l/old.jpg",
                "https://example.com/a.jpg");

        for (String url : untouched) {
            assertThat(CoverImages.proxied(url)).isEqualTo(url);
        }
        server.verify();
    }
}
