package com.animetracker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连接器对查询串里「原样出现的特殊字符」到底是接受还是拒绝.
 *
 * <p><b>为什么非得用原始 socket</b>: {@code relaxedQueryChars} 是 Tomcat **连接器**
 * 解析请求行时用的设置 —— 请求还没变成 HttpServletRequest, 更没进 Spring 的
 * DispatcherServlet. MockMvc 压根不经过连接器(它直接把请求交给过滤器链), 所以
 * 用 MockMvc 写这个用例, 无论连接器怎么配都会绿 —— 那样等于没测.
 * 只能起真的容器(RANDOM_PORT), 然后自己往那个端口写一个 HTTP 请求行.
 *
 * <p>这一组四个用例是配对来的, 少一个都会让结论不成立:
 *
 * <ul>
 *   <li><b>方括号原样出现要能过</b> —— 这是收紧之后**刻意保留**的能力. 番剧名里
 *       带方括号的很多(Fate/stay night [Unlimited Blade Works] 之类), 手输或
 *       粘贴 URL 的人不会自己去百分号编码. 这条要是没钉住, 后来有人把
 *       relaxedQueryChars 整个删掉, 表现是这些人莫名其妙收到 400;</li>
 *   <li><b>百分号编码的方括号也要能过</b> —— 这条是**对照**, 说明前面那条不是
 *       「只有放宽才行」. 百分号编码的字符与这一项设置无关, 一直都能过.
 *       也正因为如此: 前端 axios 发出去的方括号长这样(%5B/%5D), 从来没受过影响 ——
 *       这份清单不是为现有调用方留的, 别把它当成「删了会崩」的东西;</li>
 *   <li><b>尖括号/引号原样出现要被拒</b> —— 被砍掉的那些字符. 它们原本也在
 *       放宽清单里, 所以这两条钉的是「收窄真的生效了」. 方向是安全的:
 *       宁可拒绝一个没人预期的畸形 URI.</li>
 * </ul>
 *
 * <p>内存库 + dev profile + 关掉预加载, 理由同其它集成用例(不让用例依赖外网).
 * 探活端点选 /actuator/health: 它免登录且必定存在, 让用例只关心连接器这一层.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:anitrack-tomcat-query-chars;DB_CLOSE_DELAY=-1;MODE=MySQL",
                "anitrack.preload.enabled=false"
        })
@ActiveProfiles("dev")
class TomcatQueryCharsIntegrationTest {

    private static final String TARGET = "/actuator/health";

    @LocalServerPort
    private int port;

    /**
     * 探针里刻意**不带空格**: 空格是请求行里分隔三段的分隔符本身, 原样出现时
     * 连接器根本解析不出请求行(实测日志是 "Invalid character found in the HTTP
     * protocol [Blade ]" —— 它把空格后面的部分当成了协议名), 与 relaxedQueryChars
     * 无关, 也与方括号无关. 真实的粘贴场景不会出现裸空格: 浏览器在地址栏里会先把它
     * 编码成 %20. 这条用例只想钉住方括号本身.
     */
    @Test
    @DisplayName("方括号原样出现在查询串里 -> 200(这是刻意保留的能力, 给手输/粘贴的 URL 用)")
    void rawBracketsAreAccepted() throws Exception {
        assertThat(statusOf(rawGet(TARGET + "?probe=[UnlimitedBladeWorks]"))).isEqualTo(200);
    }

    @Test
    @DisplayName("百分号编码的方括号 -> 200(对照: 前端 axios 发的就是这个形状, 与放宽与否无关)")
    void percentEncodedBracketsAreAcceptedToo() throws Exception {
        assertThat(statusOf(rawGet(TARGET + "?probe=%5B1%5D"))).isEqualTo(200);
    }

    @Test
    @DisplayName("尖括号原样出现 -> 400(收窄之后不再接受)")
    void rawAngleBracketsAreRejected() throws Exception {
        assertThat(statusOf(rawGet(TARGET + "?probe=<1>"))).isEqualTo(400);
    }

    @Test
    @DisplayName("双引号原样出现 -> 400(收窄之后不再接受)")
    void rawDoubleQuoteIsRejected() throws Exception {
        assertThat(statusOf(rawGet(TARGET + "?probe=\"1\""))).isEqualTo(400);
    }

    /**
     * 往真实容器写一个 HTTP 请求行, 返回原始响应文本.
     *
     * <p>用 ISO-8859-1 逐字节写和读: 要的就是「原样出现」这类字符, 中间任何一层
     * 编码转换都可能把它们变掉, 那样测的就不是连接器了.
     */
    private String rawGet(String rawTarget) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(15_000);

            String request = "GET " + rawTarget + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";

            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.ISO_8859_1);
        }
    }

    /** 从响应首行取出状态码: {@code HTTP/1.1 400 Bad Request} -> 400. */
    private static int statusOf(String rawResponse) {
        assertThat(rawResponse)
                .as("连接器应当对 %s 回一个 HTTP 响应, 而不是直接断开", TARGET)
                .startsWith("HTTP/1.1 ");
        return Integer.parseInt(rawResponse.substring(9, 12));
    }
}
