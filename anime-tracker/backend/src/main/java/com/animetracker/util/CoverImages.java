package com.animetracker.util;

import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 封面图的上游白名单与代理地址拼装 —— <b>纯函数, 不碰 IO</b>.
 *
 * <p>这是 {@code /api/img} 这条代理的**唯一**准入判断, 所以它写得比"够用"更死:
 * 每一条规则都对应一种绕过方式, 而不是"看起来更整洁". 这个类的所有方法都只做字符串
 * 与 {@link URI} 的检查, 因此可以对着边界值一条条钉死(见 {@code CoverImagesTest}),
 * 不必起 Spring、也不必真发请求.
 *
 * <h2>为什么是白名单而不是黑名单</h2>
 *
 * <p>这条接口拿的是**用户给的 URL**, 服务端替他去取 —— 这就是一个 SSRF 面. 黑名单
 * ("不许 127.0.0.1、不许 169.254…")永远列不全: IPv6 写法、十进制 IP、DNS 重绑定、
 * 302 跳转, 每一个都是一类新绕过. 所以这里是白名单: 只有 {@value #UPSTREAM_HOST}
 * 这一个主机、只有 {@code /pic/} 这一个前缀、而且校验完之后<b>重新拼一个 URI 再发出去</b>,
 * 让"校验的那一份"与"请求的那一份"在代码上就是同一个值.
 *
 * <h2>为什么上游只有 lain.bgm.tv</h2>
 *
 * <p>2026-10-02 抽样 1026 条真实封面(6 页 /filter + 一页排行榜): <b>主机 100% 是
 * {@code lain.bgm.tv}, 路径 100% 以 {@code /pic/cover} 开头</b>; 其中 6 条是
 * {@code http://}. 也就是说"只信一个主机"不是设想 —— 这个站今天取回来的图全在那里.
 *
 * <p>那 6 条 {@code http://} 会被下面拒掉, 于是 {@link #proxied} 原样返回它们, 前端
 * 仍旧直连 —— 与改动前完全一致. 刻意不为了"覆盖得更全"把 http 也放进白名单: 那会
 * 让代理变成一个任意站点的明文取回器, 而它换来的只是几条本来就受混合内容限制的图.
 *
 * <h2>内容类型白名单为什么在这里</h2>
 *
 * <p>代理与站点**同源**, 所以透传一个 {@code text/html} 等于把上游变成了本站的一个
 * XSS 出口. 允许的只有四种图片类型, 且回应里带 {@code X-Content-Type-Options: nosniff}
 * (见 {@code ImageProxyController}).
 */
public final class CoverImages {

    /** 唯一允许的上游主机. 与 {@link #canonical} 里的比较是**精确相等**, 不是 endsWith */
    public static final String UPSTREAM_HOST = "lain.bgm.tv";

    /** 路径前缀. Bangumi 的封面全在 /pic/cover/l/… 下 */
    private static final String PIC_PREFIX = "/pic/";

    /**
     * 路径里允许出现的**全部**字符.
     *
     * <p>这是一个白名单而不是几条黑名单, 因为它要同时挡住两件不同的事, 而黑名单一次只
     * 挡得住一件:
     * <ol>
     *   <li><b>编码绕过</b> —— {@code %2e%2e} 是 {@code ..}, {@code %2f} 是 {@code /}。
     *       校验时解析一次、发请求时再解析一次, 是这类白名单最经典的洞: 两处对同一串的
     *       解读只要差一点, 放行的就可能是另一个地址。{@code %} 不在下面这张表里,
     *       所以整类问题一次消失。{@code \} 同理(某些栈上它被当成分隔符, 而上游当它是
     *       普通字符, 两份解读不一致本身就是洞)。</li>
     *   <li><b>把地址嵌进 query 时改变语义</b> —— {@link #proxied} 会把这个地址原样放进
     *       {@code ?url=…}。而 {@code &} 在 query 里是参数分隔符, {@code =} 是键值分隔符,
     *       {@code +} 在有些解码器里是空格, {@code #} 直接截断。它们**都是合法的路径字符**,
     *       所以"路径合法"根本挡不住它们 —— 一条
     *       {@code /pic/cover/l/a&b.jpg} 会被拼成 {@code ?url=…a&b.jpg},
     *       服务端读到的 {@code url} 是 {@code …a}。
     *       ({@code ?} 与 {@code #} 在上面的 query/fragment 检查里已经挡掉了,
     *       这一层是补齐 {@code & = +}。)</li>
     * </ol>
     *
     * <p>真实封面文件名是 {@code 545813_BQKrn.jpg} 这种(2026-10-02 对 1026 条实测),
     * 只有字母、数字、下划线、点与斜杠 —— 这条规则一条真数据都不误伤。
     */
    private static final String PATH_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~/";

    /** 代理入口的路径. 与 {@code ImageProxyController} 的 {@code @GetMapping} 是同一个值 */
    public static final String PROXY_PATH = "/api/img";

    /** 允许透传的图片类型 */
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private CoverImages() {
    }

    /**
     * 校验并<b>重建</b>这个地址; 不合法时返回空.
     *
     * <p>调用方拿到的一定是重新拼出来的那个 URI, 而不是"把原始串再传一遍" ——
     * 后者是这类白名单最容易出的洞: 校验时解析一遍, 发请求时再解析一遍, 两次解析
     * 只要有任何一处不一致(百分号编码、大小写、多余的点), 被放行的就可能是另一个地址.
     *
     * <p>逐条说明每个条件挡的是什么:
     * <ul>
     *   <li>{@code scheme} 必须 https —— 明文取回等于给中间人递内容;</li>
     *   <li>{@code userInfo} 必须为空 —— {@code https://lain.bgm.tv@evil.com/} 里
     *       host 是 evil.com, 但 {@code https://evil.com@lain.bgm.tv/} 的 host 是
     *       lain.bgm.tv 而请求会被发给 evil.com 的写法在别的客户端上存在, 一律不留;</li>
     *   <li>{@code host} <b>精确相等</b> —— {@code endsWith("bgm.tv")} 会放行
     *       {@code lain.bgm.tv.evil.com}, 那是这个类里最典型的一个错;</li>
     *   <li>端口只允许缺省或 443 —— 上游换端口不是我们能替他决定的事;</li>
     *   <li>不许 query 与 fragment —— 它们对取一张图没有意义, 却能把参数带进上游;</li>
     *   <li>路径必须以 {@code /pic/} 开头;</li>
     *   <li>路径里每一个字符都必须在 {@link #PATH_CHARS} 里 —— 这一条同时覆盖了
     *       {@code %} 编码绕过、反斜杠、以及 {@code & = +} 这些"放进 query 会改变语义"
     *       的字符, 理由见那个常量的注释;</li>
     *   <li>路径里不许有 {@code ..} 或 {@code //} —— 点和斜杠本身是允许的字符, 所以
     *       这两个组合要单独判。前者是路径穿越, 后者是协议相对地址的原料,
     *       也常被用来绕基于前缀的判断。</li>
     * </ul>
     */
    public static Optional<String> canonical(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }

        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return Optional.empty();
        }
        if (uri.getRawUserInfo() != null) {
            return Optional.empty();
        }
        // 精确相等: 见类注释里 endsWith 那条
        if (!UPSTREAM_HOST.equalsIgnoreCase(uri.getHost())) {
            return Optional.empty();
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return Optional.empty();
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return Optional.empty();
        }

        String path = uri.getRawPath();
        if (path == null || !path.startsWith(PIC_PREFIX)) {
            return Optional.empty();
        }
        for (int i = 0; i < path.length(); i++) {
            if (PATH_CHARS.indexOf(path.charAt(i)) < 0) {
                return Optional.empty();
            }
        }
        if (path.contains("..") || path.contains("//")) {
            return Optional.empty();
        }

        try {
            // 用**常量** UPSTREAM_HOST 而不是 uri.getHost(): 规范形式的主机名只可能来自
            // 代码里那一个常量, 不是用户输入的任何一种写法(大小写、结尾的点、IPv6 括号).
            URI rebuilt = new URI("https", null, UPSTREAM_HOST, -1, path, null, null);
            return Optional.of(rebuilt.toASCIIString());
        } catch (URISyntaxException e) {
            // 走到这里说明 path 里有这个构造器拒绝的字符 —— 一并按不合法处理
            return Optional.empty();
        }
    }

    /** 这个地址能不能由我们代取 */
    public static boolean isAllowed(String raw) {
        return canonical(raw).isPresent();
    }

    /**
     * 交给前端的地址: 白名单内的换成代理地址, 其余**原样返回**.
     *
     * <p>"其余原样返回"是刻意的, 不是偷懒: 库里存量的非白名单封面(抽样里那 6 条
     * {@code http://})如果被换成一个必然 400 的代理地址, 它们就会从"还能显示"变成
     * "一定不显示". 直连的行为与改动前一模一样.
     *
     * <p>null 与空串也原样返回 —— {@code AnimeMapper} 会把它变成空串填进三个尺寸字段,
     * 前端据此显示兜底图.
     */
    public static String proxied(String raw) {
        return canonical(raw)
                .map(c -> PROXY_PATH + "?url=" + UriUtils.encodeQueryParam(c, StandardCharsets.UTF_8))
                .orElse(raw);
    }

    /** 白名单内返回规范地址, 否则 null */
    public static String upstream(String raw) {
        return canonical(raw).orElse(null);
    }

    /** 上游响应的 Content-Type 能不能透传给浏览器 */
    public static boolean isAllowedContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String value = contentType.trim().toLowerCase(Locale.ROOT);
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon).trim();
        }
        return ALLOWED_CONTENT_TYPES.contains(value);
    }
}
