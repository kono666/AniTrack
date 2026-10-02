package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import com.animetracker.config.ImageProxyProperties;
import com.animetracker.exception.BusinessException;
import com.animetracker.util.CoverImages;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * 代取封面图: 校验地址 → 命中缓存就回 → 否则去上游取一张有界的图.
 *
 * <p>类注释只讲三件在别处看不出来的事, 其余都在方法上。
 *
 * <h2>一、为什么要有它</h2>
 *
 * <p>封面图一直是浏览器直连 {@code lain.bgm.tv} 的。这样有两个问题:
 * <ul>
 *   <li><b>上游一慢, 页面就慢在别人手里。</b> 一屏几十张图, 每一张都是一次跨域请求,
 *       失败与否、等多久, 全由上游决定 —— 而且它对每个访客各发生一次;</li>
 *   <li><b>用户看到的是第三方的地址。</b> 打开页面就等于把自己的 IP、UA、Referer
 *       交给了另一个站点。</li>
 * </ul>
 * 走自己的域之后, 上游只需要被取一次, 之后从内存回。
 *
 * <h2>二、这是一条 SSRF 面, 所以校验是白名单</h2>
 *
 * <p>它的入参是**用户给的 URL**, 服务端替他去取 —— 这正是 SSRF 的教科书形状。
 * 准入判断只有一处, 在 {@link CoverImages} 里, 而且写得比"够用"更死(精确主机、
 * 禁百分号、重建 URI)。这里只做两件事: 先校验、**再生缓存键**。
 *
 * <p>顺序不能反: 拿原始串当键的话, 两个只差一次 URL 编码的地址会是两个键, 但它们
 * 重建之后指向同一张图 —— 于是"命中缓存"这条路径<b>绕过了校验</b>。
 *
 * <h2>三、重定向必须当失败</h2>
 *
 * <p>白名单校验的是**第一跳**, 而 HTTP 客户端默认会跟着 302 走。上游只要回一个
 * 302 到内网地址(云主机元数据端点是最经典的那个), "只信任 lain.bgm.tv"就作废了。
 * 所以取图用的那个 {@code RestTemplate} 关掉了自动跟随(见 {@code WebConfig}),
 * 而这里把任何 3xx 当失败。这两处是**一对**, 改一处必须改另一处。
 */
@Service
public class CoverImageService {

    private static final Logger log = LoggerFactory.getLogger(CoverImageService.class);

    /** {@code WebConfig.imageRestTemplate} 那个 bean 的名字 */
    private static final String IMAGE_REST_TEMPLATE = "imageRestTemplate";

    private static final int BUFFER_SIZE = 8192;

    /** 一张取回来的封面图: 字节 + 回给浏览器的类型 */
    public record CoverImage(byte[] bytes, String contentType) {
    }

    private final RestTemplate imageRestTemplate;
    private final String userAgent;
    private final long maxBytes;

    /**
     * 图片缓存. 直接在构造函数里建, 而不是挂到 {@code CacheConfig} 那个
     * {@code CacheManager} 上 —— 理由见 {@link ImageProxyProperties} 的类注释
     * (那份名单的语义是"四个接口级缓存", 且有一条断言钉着名单内容).
     *
     * <p>键是**校验后重建的**规范地址, 值是字节本身。用 {@code maximumWeight}
     * 而不是 {@code maximumSize}: 图片大小差两个数量级, 按条目数封顶不构成内存上界。
     *
     * <p>{@code get(key, fn)} 对同一个键是单飞的(building 期间别的线程等它), 于是
     * 首屏几十张图同时对同一个地址发起请求时只会真取一次 —— 防击穿是它顺带给的,
     * 不用另写一套。**注意这只在成功路径上成立**: 映射函数抛异常时 Caffeine 不写缓存,
     * 等待的线程会各自再试 —— 那正是"失败要能重试"想要的行为。
     */
    private final Cache<String, CoverImage> cache;

    public CoverImageService(@Qualifier(IMAGE_REST_TEMPLATE) RestTemplate imageRestTemplate,
                             ImageProxyProperties properties,
                             BangumiApiProperties bangumiApiProperties) {
        this.imageRestTemplate = imageRestTemplate;
        this.userAgent = bangumiApiProperties.getUserAgent();
        this.maxBytes = properties.getMaxBytes().toBytes();
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.getTtl())
                .maximumWeight(properties.getMaximumWeightBytes().toBytes())
                .weigher((String key, CoverImage image) -> image.bytes().length)
                .build();
    }

    /**
     * 取一张图.
     *
     * @param rawUrl 用户给的地址, 未经任何处理
     * @throws BusinessException 400 地址不在白名单内; 502 上游取不到、回了跳转、
     *         回了非图片、或者图太大
     */
    public CoverImage load(String rawUrl) {
        String url = CoverImages.upstream(rawUrl);
        if (url == null) {
            // 400 而不是 404: 这个地址是我们**拒绝代取**, 不是"取回来发现没有".
            // 前端不会展示这条消息(它是 <img> 的地址), 它是给排查的人看的.
            throw BusinessException.badRequest("不支持的图片地址");
        }
        try {
            return cache.get(url, this::fetch);
        } catch (CompletionException e) {
            RuntimeException cause = unwrap(e);
            log.warn("封面代理失败: {} —— {}", url, cause.getMessage());
            throw cause;
        }
    }

    /**
     * Caffeine 会把映射函数抛出的任何东西包成 {@link CompletionException}, 这里剥回来。
     *
     * <p>不剥的话 {@code GlobalExceptionHandler} 认不出 {@link BusinessException},
     * 一条 502 会被兜底处理器接走变成 500「服务器内部错误」——
     * 而这类错误在响应码上必须能区分: 502 是"上游的事, 等会儿再来",
     * 500 是"我们的 bug, 该去看日志"。
     */
    private static RuntimeException unwrap(CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        // 理论上到不了(映射函数只抛 RuntimeException), 兜底也不让它继续冒泡成 500
        return new BusinessException(502, "封面代理失败");
    }

    /** 真去上游取一张. 只在缓存未命中时被调到 */
    private CoverImage fetch(String url) {
        // 传 URI 而不是 String: String 那个重载会把这串再当成**URI 模板**过一遍
        // DefaultUriBuilderFactory(编码、变量替换), 于是"校验的那个串"与"请求的那个
        // URL"之间又多出一次转换 —— 正是 CoverImages 重建 URI 要消灭的那件事。
        // 这里的串就是 rebuild 出来的规范形式, URI.create 不会失败。
        URI uri = URI.create(url);
        try {
            return imageRestTemplate.execute(uri, HttpMethod.GET, this::prepareRequest, this::readImage);
        } catch (RestClientException e) {
            // 连接不上、读超时、读流出 IOException —— 全是"上游这一刻取不到".
            // 502 Bad Gateway 正是这个意思, 而且它是**可重试**的信号: 这次失败不进缓存,
            // 下一个请求会再去取一次. 上游恢复了, 页面自己就好了.
            throw new BusinessException(502, "封面代理失败");
        }
    }

    /**
     * 请求头只设两个, 其余一律不带。
     *
     * <p><b>绝不转发客户端的 {Cookie, Authorization, Referer}.</b> 这是这类代理最容易
     * 出的事故: 图省事把进来的请求头照抄给上游, 于是每个访客的登录凭证被送到第三方。
     * 这里连"哪些头要转发"的清单都不建 —— 只写我们**主动要发**的两个,
     * 比维护一份"不许转发"的黑名单可靠。
     *
     * <p>{@code Accept: image/*} 用通配而不是把白名单那四种列出来: 白名单才是
     * "我们接受什么"的唯一出处, 抄一份到这里只会在它变化时悄悄分叉。
     *
     * <p>UA 与抓 Bangumi 接口时用的是同一个值(都来自 {@code bangumi.api.user-agent}):
     * 上游是同一家, 没有理由报两个身份。
     */
    private void prepareRequest(ClientHttpRequest request) {
        request.getHeaders().setAccept(List.of(MediaType.parseMediaType("image/*")));
        request.getHeaders().set("User-Agent", userAgent);
    }

    /**
     * 判断状态与类型, 然后**有界地**把图读出来。
     *
     * <p>为什么不用 {@code getForObject(url, byte[].class)}: 那个转换器会在读之前
     * <b>按 {@code Content-Length} 一次性分配</b>, 而且对长度未知的响应是无界地
     * {@code readAllBytes} —— 上游回一个"分块传输的大文件"就是一个 OOM。
     * 走 {@code execute} 自己读流, 才能在读到超限的那一刻就停手。
     *
     * <p>{@code Content-Length} 那一步只是**快速拒绝**: 声明得太大的立刻断掉,
     * 省掉把流打开。它不能替代下面的计数读取 —— 那个头是上游自己写的, 分块传输时
     * 干脆没有(此时它是 -1, 不触发)。
     */
    private CoverImage readImage(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.is3xxRedirection()) {
            // 见类注释第三节: 白名单只保证第一跳, 跟随跳转等于把它作废。
            throw new BusinessException(502, "上游要求跳转");
        }
        if (!status.is2xxSuccessful()) {
            throw new BusinessException(502, "上游返回 " + status.value());
        }

        MediaType contentType = response.getHeaders().getContentType();
        if (contentType == null || !CoverImages.isAllowedContentType(contentType.toString())) {
            // 代理与站点同源, 透传 text/html 等于把上游变成本站的一个 XSS 出口。
            // 缺 Content-Type 也一并拒: 一个不说自己是什么的响应不值得透传。
            throw new BusinessException(502, "上游返回的不是图片");
        }

        long declared = response.getHeaders().getContentLength();
        if (declared > maxBytes) {
            throw new BusinessException(502, "上游图片过大");
        }

        byte[] bytes = readBounded(response.getBody());
        // 只留主类型, 丢掉 charset 之类的参数 —— 白名单认的是 image/jpeg 本身,
        // 而带着参数的那一串原样回给浏览器没有任何好处。
        return new CoverImage(bytes, contentType.getType() + "/" + contentType.getSubtype());
    }

    /**
     * 边读边数, 超过上限立刻抛.
     *
     * <p>峰值内存是 {@code maxBytes + BUFFER_SIZE}, 与上游声明的长度无关 ——
     * 这是这条接口唯一需要保证的内存上界。
     */
    private byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(BUFFER_SIZE * 2);
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new BusinessException(502, "上游图片超过 " + maxBytes + " 字节");
            }
            out.write(buffer, 0, read);
        }
        if (total == 0) {
            // 200 加零字节不是一张能显示的图。当失败处理, 好让它别进缓存。
            throw new BusinessException(502, "上游返回了空响应");
        }
        return out.toByteArray();
    }
}
