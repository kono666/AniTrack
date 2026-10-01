package com.animetracker.controller;

import com.animetracker.config.GlobalExceptionHandler;
import com.animetracker.service.AvatarService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 三个头像端点的对外契约: {@code POST /api/user/avatar}、{@code GET /api/user/{id}/avatar}、
 * {@code DELETE /api/user/avatar}。
 *
 * <p>service 层那个测试盖不住的是这一层独有的两件事, 本类就是为它们写的:
 *
 * <ol>
 *   <li><b>「我」是谁。</b> 上传与删除的路径里**没有**用户 id, 改的永远是登录的那个人。
 *       这不是省一个参数, 是这个接口的安全性所在 —— 有 id 参数就有一个必须靠校验兜住的
 *       越权面, 没有它就不存在。</li>
 *   <li><b>读图必须免登录。</b> 评论区要给未登录的访客显示头像, 所以
 *       {@code GET /api/user/{id}/avatar} 在 {@code SecurityConfig} 的免登录名单里。
 *       漏掉的症状很难自己发现: 未登录访客看得见评论、看不见头像, 而访客自己不会报这个
 *       bug。这里那一条 {@code anonymousCanRead} 就是它唯一的哨兵 —— 少了它, 那条
 *       {@code requestMatchers} 被人删掉之后**没有任何测试会红**。</li>
 * </ol>
 *
 * <p>限流: 这个类要两个账号, 注册与登录各来一次就够 —— token 是静态的, 整个类共用
 * (理由见 {@code NotificationIntegrationTest})。属性把窗口放宽到 1000 是为了防止将来
 * 有人加用例时撞上 429, 那个报错会伪装成"注册接口返回 429", 与头像毫无关系。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-avatar-http;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false",
        "app.security.rate-limit.register-per-minute=1000",
        "app.security.rate-limit.login-per-minute=1000"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AvatarIntegrationTest {

    private static final String ME = "avatarowner";
    private static final String OTHER = "avatarother";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** 用它直接调那两条处理器 —— MockMvc 不经过真的 multipart 解析器, 见 {@link #oversizeIs400Not500()} */
    @Autowired
    private GlobalExceptionHandler exceptionHandler;

    /** 读实际生效的配置, 见 {@link #theMultipartLimitMatchesTheServiceConstant()} */
    @Autowired
    private org.springframework.core.env.Environment environment;

    /** 两个账号整个类共用一次 —— 登录按 IP 限流(10 次/分钟) */
    private static String meToken;
    private static String otherToken;
    private static Long meId;
    private static Long otherId;

    @BeforeEach
    void seedAndLogin() throws Exception {
        if (meToken == null) {
            JsonNode me = registerAndLogin(ME);
            meToken = me.path("token").asText();
            meId = me.path("id").asLong();

            JsonNode other = registerAndLogin(OTHER);
            otherToken = other.path("token").asText();
            otherId = other.path("id").asLong();
        }
        // 每个用例从一个"没传过头像"的状态开始. 两个账号都清, 免得上一轮留下的
        // 头像把"没有头像是 404"这条染成 200
        jdbc.update("DELETE FROM user_avatar WHERE user_id IN (?, ?)", meId, otherId);
        jdbc.update("UPDATE \"user\" SET avatar = NULL WHERE id IN (?, ?)", meId, otherId);
    }

    // ========== 帮手 ==========

    private JsonNode registerAndLogin(String username) throws Exception {
        String password = "passw0rd123";
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@example.com\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
        String body = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    /** 一张真的 PNG */
    private static byte[] png(int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static MockMultipartFile part(String contentType, byte[] bytes) {
        return new MockMultipartFile("file", "a.png", contentType, bytes);
    }

    /** 上传一张头像, 断言 200, 返回服务端给的新 URL */
    private String upload(String token, byte[] bytes) throws Exception {
        String body = mockMvc.perform(multipart("/api/user/avatar")
                        .file(part("image/png", bytes))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("avatar").asText();
    }

    private JsonNode me(String token) throws Exception {
        String body = mockMvc.perform(get("/api/user/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    // ========== 上传 → 读回来 ==========

    /**
     * 端到端最要紧的一条: 传上去的字节与读回来的字节**逐个相同**, Content-Type 也对。
     *
     * <p>断字节相等而不是断长度: 长度对而内容被改过是最典型的一种坏法(二进制列中途
     * 经过了一次字符串转换), 而它在下游的表现只是"图花了", 没有任何报错。
     */
    @Test
    @DisplayName("上传之后读回来: 字节逐个相同, Content-Type 与 ETag 都在")
    void uploadThenReadReturnsTheSameBytes() throws Exception {
        byte[] original = png(64, 48);

        String url = upload(meToken, original);
        assertThat(url).startsWith("/api/user/" + meId + "/avatar");

        byte[] readBack = mockMvc.perform(get("/api/user/" + meId + "/avatar"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE))
                // ETag 是缓存重新验证的抓手: 有它, 浏览器问一句就能省掉重新下整张图
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(readBack).isEqualTo(original);
    }

    /**
     * <b>未登录也要能读 —— 这是 {@code SecurityConfig} 那一条免登录登记的哨兵。</b>
     *
     * <p>不带 Authorization 头, 期望的是 <b>404(这个用户没传过头像)</b> 而不是 401。
     * 用 404 当判据是有意的: 它证明请求穿过了 Spring Security 走到了 controller ——
     * 要是那条 {@code requestMatchers} 被删掉, 这里会变成 401, 断言当场红。
     *
     * <p>漏掉那条登记的线上症状很难自己发现: 未登录访客看得见评论、看不见评论者的头像,
     * 而访客不会为此报一个 bug。
     */
    @Test
    @DisplayName("匿名可以读头像(拿到 404 而不是 401) —— 免登录名单那条登记的哨兵")
    void anonymousCanRead() throws Exception {
        mockMvc.perform(get("/api/user/" + meId + "/avatar"))
                .andExpect(status().isNotFound());
    }

    /** 反过来: 匿名**不能**写。上传与删除都要 401 */
    @Test
    @DisplayName("匿名上传/删除: 401")
    void anonymousCannotWrite() throws Exception {
        mockMvc.perform(multipart("/api/user/avatar").file(part("image/png", png(32, 32))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/user/avatar"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 传完立刻能看到 —— {@code user.avatar} 那一列真的被写成了 URL。
     *
     * <p>这一条守的是"图存了、URL 没写"那种自相矛盾的状态: 它不报错, 用户看到的是
     * "上传成功"但头像没变, 而且再传一次还是老样子(读的时候查的是 user.avatar)。
     */
    @Test
    @DisplayName("上传后 /api/user/me 的 avatar 是一个 URL; 没传过时是 null")
    void theUrlLandsOnTheUserRow() throws Exception {
        assertThat(me(meToken).path("avatar").isNull())
                .as("从没传过时是 null, 前端据此退回首字母/图标")
                .isTrue();

        String url = upload(meToken, png(32, 32));

        assertThat(me(meToken).path("avatar").asText())
                .as("写回 user 的是地址, 不是图片本身")
                .isEqualTo(url);
    }

    /**
     * {@code ?v=} 让缓存跟着字节一起失效 —— 同一张图传两次得到两个地址。
     *
     * <p>没有它, 换头像之后那个 URL 不变, 而读图端点带 {@code Cache-Control: max-age}:
     * 用户刚上传完, 页面上还是旧头像, 而"强刷一下就好了"意味着这种问题只出现在一部分人
     * 身上, 极难排查。版本在 URL 上, 6 处读路径一处都不用知道"缓存"这回事。
     */
    @Test
    @DisplayName("重新上传: 新地址与旧地址不同(?v= 变了), 旧字节被覆盖")
    void reuploadChangesTheUrl() throws Exception {
        byte[] first = png(32, 32);
        byte[] second = png(48, 48);

        String url1 = upload(meToken, first);
        String url2 = upload(meToken, second);

        assertThat(url2).isNotEqualTo(url1);

        assertThat(mockMvc.perform(get("/api/user/" + meId + "/avatar"))
                .andReturn().getResponse().getContentAsByteArray())
                .as("覆盖之后读到的是新图")
                .isEqualTo(second);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_avatar WHERE user_id = ?", Integer.class, meId))
                .as("一人一张: 重新上传是覆盖, 不是插第二行")
                .isEqualTo(1);
    }

    // ========== 删除 ==========

    @Test
    @DisplayName("删除之后: 读图 404, /api/user/me 的 avatar 回到 null")
    void deleteFallsBackToNothing() throws Exception {
        upload(meToken, png(32, 32));

        mockMvc.perform(delete("/api/user/avatar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + meToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/user/" + meId + "/avatar"))
                .andExpect(status().isNotFound());
        assertThat(me(meToken).path("avatar").isNull())
                .as("只删行不清地址会留下一条死链 —— 每一处都只是显示不出图, 没有任何报错")
                .isTrue();
    }

    /** 「把我变回默认」是幂等的: 本来就没有头像的人也调它一次, 结果与调用前一致 */
    @Test
    @DisplayName("本来就没有头像时再删一次: 仍然 200(幂等)")
    void deleteIsIdempotent() throws Exception {
        mockMvc.perform(delete("/api/user/avatar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + meToken))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/user/avatar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + meToken))
                .andExpect(status().isOk());
    }

    // ========== 只能改自己的 ==========

    /**
     * <b>改的永远是登录的那个人 —— 路径里没有用户 id, 所以不存在越权面。</b>
     *
     * <p>这一条用例不是为了验"我们做了校验", 而是为了验"接口的形状本身就不需要那个
     * 校验": 拿 my token 传完之后, {@code other} 那一行必须一个字都没变。
     */
    @Test
    @DisplayName("上传改的是自己: 别人的 user_avatar 与 user.avatar 都不受影响")
    void uploadingOnlyTouchesYourself() throws Exception {
        upload(meToken, png(32, 32));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_avatar WHERE user_id = ?", Integer.class, otherId))
                .as("别的用户不该凭空多出一行")
                .isZero();
        assertThat(me(otherToken).path("avatar").isNull())
                .as("别人的 URL 也不该被写上")
                .isTrue();
    }

    // ========== 校验的对外形状 ==========

    /**
     * 假图片(改了 Content-Type 的文本)走真接口也是 400 带人话, 不是 500。
     *
     * <p>service 层那条用例验的是"这一关在", 这条验的是"它的失败在 HTTP 上长什么样" ——
     * 两件事都要, 因为 {@code BusinessException} 到响应之间还隔着一层
     * {@code GlobalExceptionHandler}。
     */
    @Test
    @DisplayName("假图片: 400 带人话(不是 500)")
    void aFakeImageIs400() throws Exception {
        byte[] notAnImage = "这不是图片".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        mockMvc.perform(multipart("/api/user/avatar")
                        .file(part("image/png", notAnImage))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + meToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("这不是一张图片")));
    }

    /**
     * <b>超限必须是 400, 不能是 500。</b>
     *
     * <p>⚠️ 这条用例直接调 {@link GlobalExceptionHandler} 上那个方法, 而不是发一个真的
     * 超大请求 —— <b>MockMvc 不经过真的 multipart 解析器</b>(它直接构造
     * {@code MockMultipartHttpServletRequest}), 所以 {@code MaxUploadSizeExceededException}
     * 在 MockMvc 里根本不会被抛出来。写成一个"发个大请求断言 400"的用例会是**假绿**:
     * 它其实走的是 service 里那条 {@code getSize()} 的兜底, 删掉这个处理器照样通过。
     *
     * <p>它守的恰恰是这一批最容易发生的那种错误(「图传大了一点」): 少了这个处理器, 超限会
     * 落到兜底的 {@code handleOther} 变成 500, 而用户看到的是一句"服务器错误"。
     */
    @Test
    @DisplayName("超限: 处理器回 400 而不是落到兜底的 500")
    void oversizeIs400Not500() {
        var response = exceptionHandler.handleUploadTooLarge(
                new MaxUploadSizeExceededException(1024));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("512KB");
    }

    /**
     * 上限的<b>配置</b>与 service 里那个常量必须同值。
     *
     * <p>两者不一致时两种方向都是坏症状, 而且都不报错: 配置更小 → 上传永远先被容器拒掉,
     * service 里那句人话提示一次都看不到; 配置更大 → 一行 {@code 512KB} 的图被放进来,
     * 用户收到一句莫名其妙的大小错误。
     *
     * <p>读的是**环境里实际生效的值**(不是 yml 文件): profile 覆盖、占位符表达式都会
     * 体现在这里。这也意味着谁把配置改成 {@code ${...}} 之类的表达式之后, 下面那个
     * 解析会失败 —— 那正是该红的时候。
     */
    @Test
    @DisplayName("multipart 上限与 AvatarService.MAX_BYTES 同值")
    void theMultipartLimitMatchesTheServiceConstant() {
        String configured = environment.getProperty("spring.servlet.multipart.max-file-size");

        assertThat(configured).as("这一条配置本身不能缺").isNotBlank();
        assertThat(parseBytes(configured))
                .as("两处不同值时: 配置更小则提示语永远看不到, 更大则用户收到看不懂的 400")
                .isEqualTo(AvatarService.MAX_BYTES);
    }

    /** 把 Spring 那套 {@code 512KB} / {@code 1MB} / 纯数字的字面量读成字节数 */
    private static long parseBytes(String value) {
        String v = value.trim().toUpperCase(java.util.Locale.ROOT);
        long multiplier = 1;
        if (v.endsWith("KB")) {
            multiplier = 1024;
            v = v.substring(0, v.length() - 2);
        } else if (v.endsWith("MB")) {
            multiplier = 1024 * 1024;
            v = v.substring(0, v.length() - 2);
        } else if (v.endsWith("B")) {
            v = v.substring(0, v.length() - 1);
        }
        return Long.parseLong(v.trim()) * multiplier;
    }
}
