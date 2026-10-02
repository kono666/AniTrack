package com.animetracker.controller;

import com.animetracker.service.CoverImageService;
import com.animetracker.service.CoverImageService.CoverImage;
import com.animetracker.util.CoverImages;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 封面图代理: {@code GET /api/img?url=<上游地址>}.
 *
 * <p>存在的理由、白名单规则、以及"为什么重定向一律当失败"都在
 * {@link CoverImages} 与 {@link CoverImageService} 的类注释里, 这里只说接口本身的三个选择。
 *
 * <h2>为什么地址走 query 而不是路径</h2>
 *
 * <p>更常见的形状是 {@code /api/img/lain.bgm.tv/pic/cover/l/xx.jpg}, 但那要求 URL 里的
 * 每一段都能安全地拼回一个地址, 而中间隔着**容器与 nginx 两层路径归一化** ——
 * 它们会解百分号编码、合并 {@code //}、处理 {@code ..}。等这串字符到达白名单时,
 * 它已经被改过一遍, 而我们无从知道它原来是什么样子。
 *
 * <p>query 参数从请求行里<b>原样</b>到达 controller, 中间没有归一化。于是
 * "在 {@link CoverImages} 里解析的那个串"与"浏览器当初发的那个串"是同一个,
 * 校验才真正发生在唯一一处。
 *
 * <h2>为什么免登录</h2>
 *
 * <p>与头像同一条理由: 未登录访客看得见首页和列表页的封面, 这是本站最主要的内容,
 * 它不该需要登录才能显示。对应的白名单在 {@code SecurityConfig} 里那组带
 * {@code HttpMethod.GET} 的规则中 —— 漏配的症状是首屏全是兜底图,
 * 而那看起来像"上游挂了", 不像"权限配错了"。
 *
 * <h2>回字节, 不做重定向</h2>
 *
 * <p>不做 302 到上游: 那等于把地址又交回浏览器, 代理白做了 —— 用户的 IP 还是会到
 * 第三方, 而且我们白取了一遍。
 *
 * <p><b>没有 ETag, 也没有 If-None-Match/304 处理, 这是有意的。</b> 这里的 URL
 * 就是内容身份: 封面换了, 库里的 URL 必然跟着换(它是上游生成的地址)。
 * 所以 {@code max-age} 给得很长就够了 —— 在它有效期内浏览器压根不会再发这个请求,
 * 一条只会被发出、永远不会被兑现的 ETag 是纯装饰。真要做条件请求, 就得再写一套
 * 304 分支, 而那段分支在 {@code max-age} 存在的前提下永远走不到 ——
 * 这个项目刚因为同样的理由删掉过一个不可证伪的函数(见 {@code Tags.vue} 里那段注释)。
 */
@RestController
public class ImageProxyController {

    /**
     * 浏览器侧缓存时长.
     *
     * <p>7 天, 比服务端内存缓存的 24 小时长 —— 两者不冲突: 内存缓存过期后又去上游取
     * 一次, 而浏览器这几天里根本不会再问。它的意义是把这个请求的总次数压到
     * "每访客每张图一次", 而不是每次进首页一次。
     *
     * <p>{@code public} 而不是 {@code private}: 这是无差别的公开图片, 中间的缓存
     * (nginx、CDN)可以留着。页面上没有任何按用户变化的封面。
     */
    private static final Duration BROWSER_TTL = Duration.ofDays(7);

    private final CoverImageService coverImageService;

    public ImageProxyController(CoverImageService coverImageService) {
        this.coverImageService = coverImageService;
    }

    @GetMapping(CoverImages.PROXY_PATH)
    public ResponseEntity<byte[]> image(@RequestParam("url") String url) {
        CoverImage image = coverImageService.load(url);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                // Spring Security 的默认响应头里本来就有这一条. 这里显式再写一次,
                // 是因为它是这条接口能安全透传上游内容的**前提**, 而不是可有可无的加固 ——
                // 写在透传发生的地方, 以后有人为别的原因关掉全局头配置时, 不会连带
                // 把这张图的类型嗅探防护一起关掉(而那种关闭不会有任何测试变红)。
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(BROWSER_TTL).cachePublic())
                .body(image.bytes());
    }
}
