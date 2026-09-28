package com.animetracker.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TomcatConfig {

    /**
     * 放宽查询串里允许原样出现的字符.
     *
     * <p>Tomcat 默认按 RFC 3986 严格解析请求行, 查询串里出现 {@code { } | \ ^ [ ] ` " < >}
     * 这些「不安全」字符时直接回 400. 这里只留 {@code [} {@code ]} 这一对.
     *
     * <p><b>为什么不是原来那一长串</b>: 原来写的是 {@code []|{}^\`"<>} —— 那是从网上
     * 抄来的一份「全都放开」清单(常见于有人被 Tomcat 的 400 卡住之后). 逐个对过之后,
     * 这些字符在业务里一个都不需要:
     *
     * <ul>
     *   <li>前端(axios)发出的请求**一个都用不上**它们. axios 的编码器只把
     *       encodeURIComponent 的结果还原 {@code : $ ,} 与 {@code ! ' ( ) ~} 和空格,
     *       方括号仍然是 %5B %5D —— 而百分号编码的字符**无论是否放宽都会被接受**,
     *       也就是说这份清单不是为了现有调用方留的;</li>
     *   <li>{@code "} {@code <} {@code >} 在 HTML/脚本里有特殊含义, {@code \} 与 {@code ^}
     *       属于转义与控制类字符, {@code {} } {@code |} 在业务里没有用处. 留着它们,
     *       等于让连接器接受一批本来应当被拒绝的 URI, 而没有任何调用方因此受益.</li>
     * </ul>
     *
     * <p><b>为什么偏偏留下方括号</b>: 番剧名里带方括号的很常见
     * (Fate/stay night [Unlimited Blade Works] 之类). 前端走 axios 不受影响, 但**手输
     * 或粘贴的 URL** 不会自己去百分号编码 —— 而这是唯一有一点点现实可能性的用法.
     * 这一对字符的危害面也最小: 它们是普通的分隔符, 在 HTML 里没有特殊含义.
     *
     * <p>收窄带来的行为变化要说清楚: 原样出现的 {@code "} {@code <} {@code >} 等字符,
     * 现在会得到 400(与 Tomcat 默认、以及 RFC 3986 一致), 而不是被默默接受.
     * 这个方向是安全的 —— 宁可拒绝一个畸形的 URI, 也不要让一个没人预期的字符进来.
     *
     * <p>落地验证见 {@code TomcatQueryCharsIntegrationTest}: 用原始 socket 直接写请求行
     * 才验得到这一层, MockMvc 不经过连接器.
     */
    private static final String RELAXED_QUERY_CHARS = "[]";

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            connector.setProperty("relaxedQueryChars", RELAXED_QUERY_CHARS);
            connector.setURIEncoding("UTF-8");
        });
    }
}
