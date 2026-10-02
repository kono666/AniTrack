package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import com.animetracker.config.ImageProxyProperties;
import com.animetracker.config.WebConfig;
import com.animetracker.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link CoverImageService} 的取图与缓存行为.
 *
 * <p>用 {@code MockRestServiceServer} 而不是起一个真服务: 这里要验的是"上游回了什么,
 * 我们怎么反应", 状态码与响应头都得由测试说了算。**唯一不在这里验的是重定向** ——
 * mock 服务器不会真的跟着 302 走, 所以"关掉自动跟随"那条守卫在这里测不出来(会假绿),
 * 它由 {@code ImageClientRedirectTest} 用一个真的本地 HTTP 服务钉住。
 *
 * <p>那个 {@code RestTemplate} 来自 {@link WebConfig#imageRestTemplate()} 而不是
 * {@code new RestTemplate()}: 于是这个类同时也在验生产那个 bean 上的策略 ——
 * 尤其是"错误处理器不读响应体"那一条。{@code MockRestServiceServer} 只替换请求工厂,
 * 不动错误处理器, 所以这里的行为与线上一致。
 *
 * <p>上限调成 4KB(生产是 5MB)只是为了造"太大"这个场景时不用真的准备 5MB 的数组。
 */
class CoverImageServiceTest {

    private static final String OK = "https://lain.bgm.tv/pic/cover/l/54/58/545813_BQKrn.jpg";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};

    private MockRestServiceServer server;
    private CoverImageService service;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new WebConfig(null).imageRestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        ImageProxyProperties properties = new ImageProxyProperties();
        properties.setMaxBytes(DataSize.ofKilobytes(4));
        properties.setMaximumWeightBytes(DataSize.ofMegabytes(1));
        properties.setTtl(Duration.ofMinutes(5));

        BangumiApiProperties bangumi = new BangumiApiProperties();
        bangumi.setUserAgent("AniTrack/Test");

        service = new CoverImageService(restTemplate, properties, bangumi);
    }

    // ========== 取到图 ==========

    @Test
    @DisplayName("取到一张图: 字节逐个相同, Content-Type 只留主类型")
    void fetchesTheImage() {
        server.expect(requestTo(OK))
                .andExpect(header("Accept", "image/*"))
                .andExpect(header("User-Agent", "AniTrack/Test"))
                .andRespond(withSuccess(PNG, MediaType.IMAGE_PNG));

        CoverImageService.CoverImage image = service.load(OK);

        assertThat(image.bytes()).isEqualTo(PNG);
        assertThat(image.contentType())
                .as("带参数的类型原样回给浏览器没有好处, 只留 image/png")
                .isEqualTo("image/png");
        server.verify();
    }

    /**
     * <b>非白名单地址一个请求都不发。</b>
     *
     * <p>这条断的是"400"而不是只看它抛没抛异常 —— 后者在网络异常下也能成立,
     * 是这类用例最容易写成假绿的地方。真正的判据是: 上游**一次都没被调到**。
     * 这里不给 {@code MockRestServiceServer} 任何期望, 所以只要真发了请求,
     * 它自己会当场抛出来, 用例必红。
     */
    @Test
    @DisplayName("非白名单地址: 400, 且一个请求都不发")
    void rejectsNonWhitelistedWithoutTouchingTheUpstream() {
        for (String bad : new String[]{
                "https://evil.com/pic/cover/l/x.jpg",
                "http://lain.bgm.tv/pic/cover/l/x.jpg",
                "https://lain.bgm.tv.evil.com/pic/cover/l/x.jpg",
                "file:///etc/passwd"}) {
            assertThatThrownBy(() -> service.load(bad))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getCode()).isEqualTo(400));
        }
        server.verify();
    }

    // ========== 缓存 ==========

    @Test
    @DisplayName("同一个地址取两次: 上游只被调一次")
    void theCacheHitsOnTheSecondCall() {
        server.expect(requestTo(OK)).andRespond(withSuccess(PNG, MediaType.IMAGE_PNG));

        service.load(OK);
        service.load(OK);

        // 只登记了一次期望: 第二次真发请求的话 MockRestServiceServer 会抛
        // "Unexpected request", 用例必红 —— 去掉缓存就是这个结果.
        server.verify();
    }

    /**
     * <b>缓存键是校验后重建的规范地址, 不是原始串。</b>
     *
     * <p>这两种写法只差主机名大小写, 规范形式完全相同 —— 于是它们该共用一次上游请求。
     * 拿原始串当键的话会取两次, 而更严重的是另一头: 命中缓存那条路径<b>不经过校验</b>,
     * 键与"将要请求的地址"不一致就意味着校验可以被绕开。
     */
    @Test
    @DisplayName("只差大小写的两个写法共用一个缓存条目")
    void theCacheKeyIsTheCanonicalUrl() {
        server.expect(requestTo(OK)).andRespond(withSuccess(PNG, MediaType.IMAGE_PNG));

        service.load(OK);
        service.load("https://LAIN.BGM.TV/pic/cover/l/54/58/545813_BQKrn.jpg");

        server.verify();
    }

    /**
     * <b>失败不进缓存, 下一个请求还能再试。</b>
     *
     * <p>这条守的是"上游抖一下, 页面要等很久才好"。把失败也缓存起来的话, 一次瞬时故障
     * 会被固化成一段时间的稳定故障 —— 而日志上只有最初那一条。
     * {@code Caffeine.get(key, fn)} 在映射函数抛异常时不写缓存, 正是这个行为。
     *
     * <p>两条期望都是**先登记好**的({@code MockRestServiceServer} 一旦收到过请求就不让
     * 再加期望): 第一条对应"上游挂了那一次", 第二条对应"重试那一次"。
     * 失败若被缓存, 第二次 {@code load} 不会发请求, 于是 {@code verify()} 会报
     * "还有期望没被满足" —— 用例必红。
     */
    @Test
    @DisplayName("上游失败之后重试成功 —— 失败结果不入缓存")
    void failuresAreNotCached() {
        server.expect(requestTo(OK)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(OK)).andRespond(withSuccess(PNG, MediaType.IMAGE_PNG));

        assertThatThrownBy(() -> service.load(OK))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(502));

        assertThat(service.load(OK).bytes())
                .as("第二次必须真的再去取一次, 而不是拿上次那个失败结果")
                .isEqualTo(PNG);
        server.verify();
    }

    // ========== 上游的各种坏响应 ==========

    /**
     * <b>3xx 一律当失败 —— 这一条是"关掉自动跟随"在业务侧的对应物。</b>
     *
     * <p>白名单只校验了第一跳。真要跟随跳转的话, 上游一个 302 到内网地址就把整个白名单
     * 作废了。所以这里把 3xx 当 502, 让"取不到"变成一个明确可见的结果。
     *
     * <p>⚠️ 本条验的是**我们拿到 3xx 之后的反应**, 验不了客户端有没有真的跟过去 ——
     * 那件事由 {@code ImageClientRedirectTest} 用真 socket 钉住。两条合起来才完整:
     * 只留这一条的话, 去掉"不跟随重定向"仍然是绿的。
     *
     * <p><b>为什么断言的是消息里的「跳转」而不是只看 502.</b> 只看 502 的话这条用例是
     * 假绿的: 去掉 {@code readImage} 里那个 {@code is3xxRedirection} 分支, 302 会掉进
     * 下面的 {@code !is2xxSuccessful()} 里, <b>照样是 502</b> —— 用例不会红, 而"上游要求
     * 跳转"这条最该被看见的诊断信息没了。放一个 302 和一个 500 在日志里长得一样,
     * 排查的人就分不出"上游在把我们往别处引"(可能是一次 SSRF 尝试)与"上游今天不好使"。
     */
    @Test
    @DisplayName("上游回 302: 502 且明说「跳转」—— 不是笼统的上游错误")
    void redirectsAreTreatedAsFailure() {
        server.expect(requestTo(OK))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .location(URI.create("http://169.254.169.254/latest/meta-data/")));

        assertThatThrownBy(() -> service.load(OK))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(502);
                    assertThat(e.getMessage())
                            .as("掉进'非 2xx'那一支也会是 502, 只有消息能区分这两条路")
                            .contains("跳转");
                });
    }

    @Test
    @DisplayName("上游回 4xx/5xx: 502")
    void upstreamErrorsBecome502() {
        server.expect(requestTo(OK)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.load(OK))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(502));
    }

    /**
     * 上游说自己是 html 就拒 —— 代理与站点**同源**, 透传它等于开了个 XSS 出口。
     *
     * <p>缺 Content-Type 也一样拒: 一个不说自己是什么的响应不值得透传。
     * {@code application/octet-stream} 是第三种: 一个"我知道类型但不说"的响应,
     * 透传它浏览器只能靠嗅探, 而嗅探正是 XSS 的入口。
     *
     * <p>期望先登记两条(理由同 {@link #failuresAreNotCached()})。
     */
    @Test
    @DisplayName("上游回 text/html、octet-stream: 502")
    void nonImageContentTypesAreRejected() {
        server.expect(requestTo(OK))
                .andRespond(withSuccess("<script>alert(1)</script>", MediaType.TEXT_HTML));
        server.expect(requestTo(OK))
                .andRespond(withSuccess(PNG, MediaType.APPLICATION_OCTET_STREAM));

        assertThatThrownBy(() -> service.load(OK))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.load(OK))
                .isInstanceOf(BusinessException.class);
        server.verify();
    }

    @Test
    @DisplayName("上游回空响应: 502(不是一张能显示的图)")
    void anEmptyBodyIsRejected() {
        server.expect(requestTo(OK)).andRespond(withSuccess(new byte[0], MediaType.IMAGE_PNG));
        assertThatThrownBy(() -> service.load(OK))
                .isInstanceOf(BusinessException.class);
    }

    // ========== 有界读取 ==========

    @Test
    @DisplayName("声明的 Content-Length 超限: 直接拒(不打开流)")
    void declaredLengthOverTheLimitIsRejected() {
        server.expect(requestTo(OK))
                .andRespond(withSuccess(PNG, MediaType.IMAGE_PNG)
                        .header("Content-Length", String.valueOf(1024 * 1024)));

        assertThatThrownBy(() -> service.load(OK))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("过大"));
    }

    /**
     * <b>声明得小、实际发得大 —— 这条才是有界读取的哨兵。</b>
     *
     * <p>{@code Content-Length} 是上游自己写的一个数, 分块传输时干脆没有。只靠它,
     * 一个说谎的上游(或者一个畸形的响应)就能让我们把任意大小的东西读进内存。
     * 这里让声明值远小于 body, 只有"边读边数"那一层拦得住。
     *
     * <p>去掉计数读取、改用 {@code getForObject(url, byte[].class)} 之类的话, 这条会绿
     * (它确实读完了整个 body) —— 而那种写法正是这条接口最需要避免的 OOM 面。
     * 所以断言的不只是"抛异常", 还有"因为超限而抛"。
     */
    @Test
    @DisplayName("Content-Length 说谎(声明 10 字节, 实际 8KB): 仍然被计数读取拦下")
    void aLyingContentLengthIsStillStoppedByTheBoundedRead() {
        byte[] tooBig = new byte[8 * 1024];
        java.util.Arrays.fill(tooBig, (byte) 7);

        server.expect(requestTo(OK))
                .andRespond(withSuccess(tooBig, MediaType.IMAGE_PNG).header("Content-Length", "10"));

        assertThatThrownBy(() -> service.load(OK))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(502);
                    assertThat(e.getMessage()).contains("超过");
                });
    }
}
