package com.animetracker.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>「不跟随重定向」这件事只有真起一个 HTTP 服务才验得了。</b>
 *
 * <p>这条路的其它所有用例都跑在 {@code MockRestServiceServer} 上, 而那个假的服务器
 * <b>从不跟随重定向</b> —— 它拿到 302 就把 302 交回去, 与客户端配成什么样无关。
 * 也就是说: 把 {@code WebConfig.imageRestTemplate()} 里那行
 * {@code connection.setInstanceFollowRedirects(false)} 整行删掉, mock 那套用例
 * <b>一条都不会红</b>, 而线上的行为是"跟着上游给的任何地址走" —— 白名单只校验了第一跳,
 * 一个 302 到 {@code http://169.254.169.254/}(云主机元数据端点)就能把它整个绕开。
 *
 * <p>所以这里用 JDK 自带的 {@link HttpServer} 在 127.0.0.1 上真起两个服务: 一个发 302,
 * 另一个当"跳过去就会被打到"的目标。断言的形状是:
 *
 * <ol>
 *   <li>拿回来的状态码是 <b>302 本身</b>(不是 200) —— 说明响应原样回到了我们手里;</li>
 *   <li>目标服务上的计数器<b>是 0</b> —— 说明它一次都没被访问过。</li>
 * </ol>
 *
 * <p>第 1 条不只是"顺带看看": 少了它, 整个文件会假绿 —— 万一那个 302 因为别的原因
 * 没被真的发出来(路由写错、服务没起来), 计数器同样是 0, 而 0 正是要断言的值。
 * 先钉住"确实发生了一次重定向", 第 2 条的 0 才有意义。
 *
 * <p>第三个用例是<b>对照</b>: 同一个客户端直取目标地址必须拿到 200 且计数器加一。
 * 没有它的话, 一个"什么都不请求"的坏客户端(比如超时被设成 1 毫秒、或者工厂整个坏掉)
 * 也能让上面两条绿。
 *
 * <p><b>为什么这里不经过 {@code CoverImageService}</b>: 它的白名单只放行
 * {@code lain.bgm.tv}, 而 {@code 127.0.0.1} 一定不在里面 —— 走那条路的话请求根本发不出去,
 * 用例就变成在验白名单而不是在验重定向。"302 被当成失败(502)"那一半由
 * {@code CoverImageServiceTest.redirectsAreTreatedAsFailure} 用 mock 兜着, 两半分着验,
 * 各自验的都是自己那一层真正的东西。
 */
class ImageClientRedirectTest {

    /** 发 302 的"上游" */
    private HttpServer upstream;
    /** "跳过去就会被打到"的目标 —— 不同端口, 即不同的 origin */
    private HttpServer elsewhere;

    private final AtomicInteger elsewhereHits = new AtomicInteger();
    private final AtomicInteger upstreamTargetHits = new AtomicInteger();

    private RestTemplate restTemplate;

    @BeforeEach
    void startServers() throws IOException {
        elsewhere = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        elsewhere.createContext("/landing", exchange -> {
            elsewhereHits.incrementAndGet();
            respond(exchange, 200, "landed");
        });
        elsewhere.start();

        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/target", exchange -> {
            upstreamTargetHits.incrementAndGet();
            respond(exchange, 200, "image-bytes");
        });
        // 同源重定向: 目标就在这个服务自己的另一个路径上
        upstream.createContext("/redirect-same-origin", exchange ->
                redirect(exchange, "/target"));
        // 相对路径形式的 Location —— JDK 会自己把它解析成绝对地址, 一样不能跟
        upstream.createContext("/redirect-relative", exchange ->
                redirect(exchange, "target"));
        // 跨源重定向: 指向另一个端口上的服务. 用真实存在的地址而不是 169.254.169.254,
        // 因为后者即使被请求也观察不到 —— 而这里要断言的是"它一次都没被打到"
        upstream.createContext("/redirect-cross-origin", exchange ->
                redirect(exchange, "http://127.0.0.1:" + elsewhere.getAddress().getPort() + "/landing"));
        upstream.start();

        restTemplate = new WebConfig(null).imageRestTemplate();
    }

    @AfterEach
    void stopServers() {
        if (upstream != null) upstream.stop(0);
        if (elsewhere != null) elsewhere.stop(0);
    }

    @Test
    @DisplayName("同源 302: 状态码原样回来, 目标一次都没被打到")
    void sameOriginRedirectIsNotFollowed() {
        Probe probe = get("/redirect-same-origin");

        assertThat(probe.status())
                .as("跟过去了就会是 200 —— 302 本身回来才说明这一跳停住了")
                .isEqualTo(HttpStatus.FOUND);
        assertThat(probe.location()).as("确实是一次重定向").isEqualTo("/target");
        assertThat(upstreamTargetHits).as("目标被打到过, 就是跟过去了").hasValue(0);
    }

    @Test
    @DisplayName("相对路径的 Location: 同样不跟")
    void relativeRedirectIsNotFollowed() {
        Probe probe = get("/redirect-relative");

        assertThat(probe.status()).isEqualTo(HttpStatus.FOUND);
        assertThat(probe.location()).isEqualTo("target");
        assertThat(upstreamTargetHits).hasValue(0);
    }

    @Test
    @DisplayName("跨源 302(另一个端口): 不跟 —— 白名单只挡第一跳, 这一跳不挡就是 SSRF")
    void crossOriginRedirectIsNotFollowed() {
        Probe probe = get("/redirect-cross-origin");

        assertThat(probe.status()).isEqualTo(HttpStatus.FOUND);
        assertThat(elsewhereHits)
                .as("跨源那一跳真的发出去过, 白名单就被一个 302 绕开了")
                .hasValue(0);
    }

    /**
     * 对照: 同一个客户端直取目标, 必须 200 且计数器加一。
     *
     * <p>没有这一条, 上面三条都可以被一个"根本不发请求"的坏客户端蒙过去 ——
     * 计数器为零的原因会是"没人请求"而不是"没跟过去"。
     */
    @Test
    @DisplayName("对照: 直取目标拿到 200 且计数器加一(证明上面那些 0 是'没跟', 不是'没请求')")
    void aDirectRequestStillWorks() {
        Probe probe = get("/target");

        assertThat(probe.status()).isEqualTo(HttpStatus.OK);
        assertThat(upstreamTargetHits).hasValue(1);
    }

    /** 状态码与 Location 头 —— 两个都要, 见类注释里那段"少了第 1 条会假绿" */
    private record Probe(HttpStatus status, String location) {
    }

    private Probe get(String path) {
        URI uri = URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + path);
        return restTemplate.execute(uri, HttpMethod.GET, null, response ->
                new Probe(HttpStatus.valueOf(response.getStatusCode().value()),
                        response.getHeaders().getFirst("Location")));
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
