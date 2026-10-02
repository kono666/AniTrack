package com.animetracker.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CoverImages} 的准入判断.
 *
 * <p>这个类是整个 {@code /api/img} 代理**唯一**的一道门, 而它的输入是完全由调用方
 * 控制的一个字符串 —— 所以这里不测"正常地址能不能过", 那是附带; 真正要钉住的是
 * <b>每一种绕过都过不去</b>。每一条拒绝用例都对应类注释里的一条规则, 删掉那条规则
 * 就会红。
 *
 * <p>两处刻意写得不那么"整齐"的地方, 都是因为整齐的写法会假绿:
 * <ul>
 *   <li><b>断言 {@code isAllowed} 而不只断言 {@code proxied} 原样返回。</b>
 *       {@code proxied} 对不合法的地址就是原样返回, 于是"非法地址返回原串"与
 *       "非法地址返回另一个非代理串"是同一个断言 —— 一条把 host 校验写成
 *       {@code endsWith("bgm.tv")} 的实现也能让 {@code https://lain.bgm.tv.evil.com/…}
 *       通过这条断言(它会被代理, 返回的不再是原串, 所以还是会红 —— 但不能只靠这个)。</li>
 *   <li><b>userinfo 那一对正反两个方向都写。</b> 只写 {@code https://lain.bgm.tv@evil.com/}
 *       的话, 挡住它的是 host 校验; 只有反过来那个
 *       ({@code https://evil.com@lain.bgm.tv/}, host 恰好是 lain.bgm.tv)**单独**
 *       由 userinfo 那一条规则挡住 —— 少了它这条就是唯一会漏的。</li>
 * </ul>
 */
class CoverImagesTest {

    private static final String OK = "https://lain.bgm.tv/pic/cover/l/54/58/545813_BQKrn.jpg";

    // ========== 放行的那一类 ==========

    @Test
    @DisplayName("真实封面地址: 放行, 并且 proxied 出来是一个能用的 /api/img 链接")
    void aRealCoverIsAllowed() {
        assertThat(CoverImages.isAllowed(OK)).isTrue();
        assertThat(CoverImages.upstream(OK)).isEqualTo(OK);
        assertThat(CoverImages.proxied(OK)).startsWith("/api/img?url=");
    }

    /**
     * <b>往返自洽: 拼出来的 url 参数解码之后必须等于规范地址。</b>
     *
     * <p>这条守的是"用错了编码器"。用 {@code URLEncoder.encode} 而不是
     * {@code UriUtils.encodeQueryParam} 时, 地址里的 {@code /} 会变成 {@code %2F} ——
     * 这在多数场景下其实也能解开, 但 {@code URLEncoder} 是
     * {@code application/x-www-form-urlencoded} 的编码器: 它会把空格编成 {@code +},
     * 而 query 里 {@code +} 的解码规则是"空格", 于是某类地址会静默变成另一个地址。
     * 断言解码回来一字不差, 是唯一能同时盖住这两种错法的写法。
     */
    @Test
    @DisplayName("往返: proxied 里那个 url 参数解码回来 == 规范地址")
    void theQueryParameterRoundTrips() {
        String proxied = CoverImages.proxied(OK);
        String raw = proxied.substring(proxied.indexOf("url=") + "url=".length());

        assertThat(UriUtils.decode(raw, StandardCharsets.UTF_8))
                .as("解码后必须一字不差 —— 差一个字符就意味着代取的是另一个地址")
                .isEqualTo(CoverImages.upstream(OK));
    }

    @Test
    @DisplayName("主机名大小写不影响放行, 但规范形式一律小写")
    void hostIsCaseInsensitive() {
        String upper = "https://LAIN.BGM.TV/pic/cover/l/x.jpg";
        assertThat(CoverImages.isAllowed(upper)).isTrue();
        assertThat(CoverImages.upstream(upper))
                .as("规范形式是重建出来的, 不是原串")
                .isEqualTo("https://lain.bgm.tv/pic/cover/l/x.jpg");
    }

    @Test
    @DisplayName("显式写 443 与不写端口等价")
    void explicitDefaultPortIsFine() {
        assertThat(CoverImages.isAllowed("https://lain.bgm.tv:443/pic/cover/l/x.jpg")).isTrue();
    }

    // ========== 拒绝的那一类: 主机 ==========

    @Nested
    @DisplayName("主机")
    class Host {

        /**
         * <b>这条是 {@code endsWith("bgm.tv")} 那种写法的哨兵。</b>
         *
         * <p>用后缀匹配时它会被放行 —— 而它解析出来的主机是 {@code lain.bgm.tv.evil.com},
         * 攻击者只要注册一个子域名就能让服务端替他取任意内容。规则写成"精确相等"
         * 就是为了它, 所以这条用例必须存在。
         */
        @Test
        @DisplayName("lain.bgm.tv.evil.com 不放行 —— endsWith 写法的哨兵")
        void aSuffixMatchIsNotTheSameHost() {
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv.evil.com/pic/cover/l/x.jpg"))
                    .isFalse();
        }

        @Test
        @DisplayName("别的域名不放行")
        void anotherHostIsRejected() {
            assertThat(CoverImages.isAllowed("https://evil.com/pic/cover/l/x.jpg")).isFalse();
            assertThat(CoverImages.isAllowed("https://bgm.tv/pic/cover/l/x.jpg")).isFalse();
            // 内网地址: 这正是代理最该挡住的那一类
            assertThat(CoverImages.isAllowed("https://127.0.0.1/pic/cover/l/x.jpg")).isFalse();
            assertThat(CoverImages.isAllowed("https://169.254.169.254/pic/x.jpg")).isFalse();
        }

        /**
         * userinfo 反写: {@code https://evil.com@lain.bgm.tv/…} 的 host **恰好就是**
         * lain.bgm.tv, 所以挡住它的只能是"不许带 userinfo"这一条。
         *
         * <p>有的客户端会把 {@code @} 前面那一段当主机去连 —— 各家实现不一致,
         * 一律不留。
         */
        @Test
        @DisplayName("带 userinfo 不放行(哪怕 host 看着是对的)")
        void userInfoIsRejected() {
            assertThat(CoverImages.isAllowed("https://evil.com@lain.bgm.tv/pic/cover/l/x.jpg"))
                    .as("host 是 lain.bgm.tv, 只有 userinfo 那一条规则拦得住它")
                    .isFalse();
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv@evil.com/pic/cover/l/x.jpg"))
                    .isFalse();
        }

        @Test
        @DisplayName("非 443 端口不放行")
        void otherPortsAreRejected() {
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv:8443/pic/cover/l/x.jpg")).isFalse();
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv:80/pic/cover/l/x.jpg")).isFalse();
        }
    }

    // ========== 拒绝的那一类: 协议与路径 ==========

    @Nested
    @DisplayName("协议与路径")
    class SchemeAndPath {

        @Test
        @DisplayName("http 不放行 —— 库里那 6 条老数据就是这一类")
        void httpIsRejected() {
            assertThat(CoverImages.isAllowed("http://lain.bgm.tv/pic/cover/l/x.jpg")).isFalse();
        }

        @Test
        @DisplayName("file / data / 相对地址不放行")
        void otherSchemesAreRejected() {
            assertThat(CoverImages.isAllowed("file:///etc/passwd")).isFalse();
            assertThat(CoverImages.isAllowed("data:text/html,<script>alert(1)</script>")).isFalse();
            assertThat(CoverImages.isAllowed("/pic/cover/l/x.jpg")).isFalse();
            assertThat(CoverImages.isAllowed("pic/cover/l/x.jpg")).isFalse();
        }

        @Test
        @DisplayName("不在 /pic/ 下的路径不放行")
        void otherPathsAreRejected() {
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv/")).isFalse();
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv/index.html")).isFalse();
        }

        @Test
        @DisplayName("路径穿越不放行")
        void traversalIsRejected() {
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv/pic/../etc/passwd")).isFalse();
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv/pic/a/../../x")).isFalse();
            // 双斜杠是协议相对地址的原料, 也常被用来绕基于前缀的判断
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv/pic//x.jpg")).isFalse();
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv/pic/a\\..\\b.jpg")).isFalse();
        }

        /**
         * 路径里出现百分号就拒 —— 这一条一次消灭整个"编码绕过"类.
         *
         * <p>{@code %2e%2e} 解出来是 {@code ..}, {@code %2f} 是 {@code /}。而"校验时
         * 解析一次、发请求时再解析一次"是这类白名单最经典的洞 —— 两处对同一串的解读
         * 只要有一点不同, 被放行的就可能是另一个地址。真实封面文件名是
         * {@code 545813_BQKrn.jpg} 这种, 不含百分号, 所以这条规则不误伤任何真数据。
         */
        @Test
        @DisplayName("路径里带百分号不放行 —— 消灭编码绕过")
        void percentEncodingIsRejected() {
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv/pic/%2e%2e/etc/passwd")).isFalse();
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv/pic/a%2Fb.jpg")).isFalse();
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv/pic/%41.jpg")).isFalse();
        }

        /**
         * <b>{@code & = +} 单独有一条用例, 因为它们挡的不是 SSRF 而是另一件事。</b>
         *
         * <p>它们是**合法的路径字符**, 所以"路径看着没问题"完全挡不住它们。而
         * {@link CoverImages#proxied} 会把这个地址原样拼进 {@code ?url=…} ——
         * 到了那里 {@code &} 是参数分隔符: 一条 {@code /pic/cover/l/a&b.jpg} 会被
         * 服务端读成 {@code ?url=…a} 加一个叫 {@code b.jpg} 的额外参数。
         * 也就是说这个地址**取到的不是用户要的那张图**, 而这种失败没有任何报错 ——
         * 只是显示了一张(或没有)别的图。
         *
         * <p>假绿的风险在于: 只断言"非法地址返回 400"的话, 用 {@code URLEncoder} 编码
         * 也能让它通过(它会把 {@code &} 编成 {@code %26})—— 但 {@code URLEncoder}
         * 会把空格编成 {@code +}, 而 {@code +} 在 query 里的解码是空格, 于是换成另一类
         * 静默错位。这一条钉死"根本不让这些字符进来", 编码器选哪个都不再是安全问题。
         */
        @Test
        @DisplayName("路径里的 & = + 不放行 —— 它们会改写 ?url= 这个 query")
        void queryBreakingCharactersAreRejected() {
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv/pic/cover/l/a&b.jpg")).isFalse();
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv/pic/cover/l/a=b.jpg")).isFalse();
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv/pic/cover/l/a+b.jpg")).isFalse();
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv/pic/cover/l/a b.jpg")).isFalse();
        }

        @Test
        @DisplayName("带 query 或 fragment 不放行")
        void queryAndFragmentAreRejected() {
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv/pic/cover/l/x.jpg?a=b")).isFalse();
            assertThat(CoverImages.isAllowed("https://lain.bgm.tv/pic/cover/l/x.jpg#f")).isFalse();
        }
    }

    // ========== 空值与非白名单的原样返回 ==========

    @Test
    @DisplayName("null / 空白 / 不是地址: 一律不放行, proxied 原样返回")
    void emptyInputsPassThrough() {
        assertThat(CoverImages.isAllowed(null)).isFalse();
        assertThat(CoverImages.isAllowed("")).isFalse();
        assertThat(CoverImages.isAllowed("   ")).isFalse();
        assertThat(CoverImages.isAllowed("随便一个字符串")).isFalse();
        assertThat(CoverImages.isAllowed("https://")).isFalse();

        assertThat(CoverImages.proxied(null)).isNull();
        assertThat(CoverImages.proxied("")).isEmpty();
        assertThat(CoverImages.upstream(null)).isNull();
    }

    /**
     * <b>非白名单的地址原样返回, 不是换成代理地址。</b>
     *
     * <p>这一条看着像"没做转换", 其实是个刻意的决定: 库里存量的老地址(实测 1026 条里
     * 有 6 条是 {@code http://})如果被换成一个必然 400 的代理地址, 它们就从"还能显示"
     * 变成"一定不显示"。原样返回则与改动前的行为完全一致 —— 仍然直连, 仍然能显示。
     */
    @Test
    @DisplayName("非白名单地址原样返回 —— 老数据不会从能显示变成 400")
    void nonWhitelistedUrlsPassThroughUnchanged() {
        String legacy = "http://lain.bgm.tv/pic/cover/l/old.jpg";
        assertThat(CoverImages.proxied(legacy)).isEqualTo(legacy);

        String other = "https://example.com/a.jpg";
        assertThat(CoverImages.proxied(other)).isEqualTo(other);
    }

    // ========== Content-Type 白名单 ==========

    @Nested
    @DisplayName("Content-Type")
    class ContentTypes {

        @Test
        @DisplayName("四种图片类型放行, 带参数也行")
        void imageTypesAreAllowed() {
            assertThat(CoverImages.isAllowedContentType("image/jpeg")).isTrue();
            assertThat(CoverImages.isAllowedContentType("image/png")).isTrue();
            assertThat(CoverImages.isAllowedContentType("image/gif")).isTrue();
            assertThat(CoverImages.isAllowedContentType("image/webp")).isTrue();
            assertThat(CoverImages.isAllowedContentType("image/jpeg; charset=binary")).isTrue();
            assertThat(CoverImages.isAllowedContentType("IMAGE/PNG")).isTrue();
        }

        /**
         * <b>svg 不在白名单里, 这是有意的。</b> 它是 {@code image/*}, 但能内嵌 script ——
         * 代理与站点同源, 透传一个 svg 等于给了它一个 XSS 出口。同理还有 text/html。
         */
        @Test
        @DisplayName("svg / html / 未知类型不放行")
        void riskyTypesAreRejected() {
            assertThat(CoverImages.isAllowedContentType("image/svg+xml")).isFalse();
            assertThat(CoverImages.isAllowedContentType("text/html")).isFalse();
            assertThat(CoverImages.isAllowedContentType("application/octet-stream")).isFalse();
            assertThat(CoverImages.isAllowedContentType(null)).isFalse();
            assertThat(CoverImages.isAllowedContentType("")).isFalse();
        }
    }
}
