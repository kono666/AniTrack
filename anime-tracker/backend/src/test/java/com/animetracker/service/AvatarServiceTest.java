package com.animetracker.service;

import com.animetracker.entity.User;
import com.animetracker.entity.UserAvatar;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.UserAvatarRepository;
import com.animetracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 头像写入侧的四道关卡, 以及「存图 + 改 user.avatar」确实在同一个事务里。
 *
 * <p>与另外两个测试的分工: 本类管**校验与写路径**(不起 Spring、不碰库)、
 * {@link com.animetracker.migration.UserAvatarMigrationTest} 管脚本与列类型、
 * {@code controller/AvatarIntegrationTest} 管端到端(真库、真 HTTP、真鉴权)。
 *
 * <p><b>这些用例真正想钉住的是一条容易退化的性质: 四道关卡里第 3、4 关看的是字节,
 * 前两关看的是调用方自己写的字符串。</b> 只看字符串的实现足够通过前两关的用例, 于是
 * "把 {@code ImageIO} 那两关删掉"这种改动会让一类用例全绿 —— 所以这里必须有
 * {@link #aTextFileDeclaredAsPngIsRejected()} 与 {@link #aGifDeclaredAsPngIsRejected()}
 * 两条, 它们的存在就是那两关的哨兵。
 */
class AvatarServiceTest {

    private static final Long USER_ID = 42L;

    private UserAvatarRepository avatarRepository;
    private UserRepository userRepository;
    /** 真的 {@link IsolatedInsert}(没有事务管理器时它就是一次直通调用), 但记下嵌套深度 */
    private CountingIsolatedInsert isolatedInsert;
    private AvatarService service;

    /**
     * 替身只做一件事: 记下「回调跑的时候在第几层」。
     *
     * <p>用它是因为"两步在同一个事务里"这件事在没有事务管理器时**量不到** ——
     * 但它的结构性前提可以: 两个仓储调用都发生在 {@code attempt} 的回调**内部**。
     * 挪出去(两次直通调用)时深度会变成 0, 用例立刻红。
     */
    private static class CountingIsolatedInsert extends IsolatedInsert {
        final AtomicInteger depth = new AtomicInteger();
        int maxDepth;

        @Override
        public <T> T attempt(java.util.function.Supplier<T> insert) {
            int d = depth.incrementAndGet();
            maxDepth = Math.max(maxDepth, d);
            try {
                return insert.get();
            } finally {
                depth.decrementAndGet();
            }
        }
    }

    @BeforeEach
    void setUp() {
        avatarRepository = mock(UserAvatarRepository.class);
        userRepository = mock(UserRepository.class);
        isolatedInsert = new CountingIsolatedInsert();
        service = new AvatarService(avatarRepository, userRepository, isolatedInsert);
    }

    // ========== 造图 ==========

    /** 一张真的、能用 ImageIO 解出来的图 */
    private static byte[] imageBytes(String format, int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static MockMultipartFile file(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    // ========== 第 1 关: 大小 ==========

    @Test
    @DisplayName("空文件: 400, 而且理由不是「太大了」—— 判空排在大小之前")
    void anEmptyFileIsRejected() {
        assertThatThrownBy(() -> service.inspect(file("a.png", "image/png", new byte[0])))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请选择一张图片");
    }

    /**
     * 超过 512KB 是**这一批最常发生的那种错误**(「图传大了一点」), 所以它的形状很要紧:
     * 必须是 400 带上人话, 不能是 500。
     *
     * <p>这里量的是 service 这一层。容器那一层({@code MaxUploadSizeExceededException})
     * 由 {@code GlobalExceptionHandler} 接住并同样回 400 —— 那条在
     * {@code controller/AvatarIntegrationTest} 里单独有一条, 因为 MockMvc 不走真的
     * multipart 解析器, 这里造不出来。
     */
    @Test
    @DisplayName("超过 512KB: 400, 文案里带上限")
    void anOversizeFileIsRejected() {
        byte[] tooBig = new byte[AvatarService.MAX_BYTES + 1];

        assertThatThrownBy(() -> service.inspect(file("a.png", "image/png", tooBig)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("512KB");
    }

    /** 刚好等于上限要放行 —— 边界写成 {@code >} 而不是 {@code >=} 是有意的 */
    @Test
    @DisplayName("刚好 512KB 的图放行(边界是 > 而不是 >=)")
    void aFileExactlyAtTheLimitGetsThrough() throws Exception {
        byte[] png = imageBytes("png", 32, 32);
        // 补零到刚好 MAX_BYTES: PNG 解码器读到 IEND 就停, 后面的零不影响它
        byte[] padded = new byte[AvatarService.MAX_BYTES];
        System.arraycopy(png, 0, padded, 0, png.length);

        assertThat(service.inspect(file("a.png", "image/png", padded)).contentType())
                .isEqualTo(AvatarService.PNG);
    }

    // ========== 第 2 关: 声明的类型 ==========

    /**
     * <b>webp 被拒是刻意的, 不是漏配。</b> {@code ImageIO} 默认没有 WebP 解码器, 把
     * webp 写进白名单只会让第 4 关把所有 webp 一律拒掉 —— 那时这条白名单就成了假话。
     * 前端 {@code <input accept>} 与这里逐字对应, 也是同一个理由。
     */
    @Test
    @DisplayName("声明 webp: 400 —— 白名单里只有 png/jpeg, 加了 webp 才是假话")
    void webpIsNotInTheWhitelist() {
        assertThatThrownBy(() -> service.inspect(file("a.webp", "image/webp", new byte[16])))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只支持 PNG 或 JPEG");
    }

    @Test
    @DisplayName("Content-Type 缺失: 400(而不是 500)")
    void aMissingContentTypeIsRejected() {
        assertThatThrownBy(() -> service.inspect(file("a.png", null, new byte[16])))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只支持 PNG 或 JPEG");
    }

    // ========== 第 3 关: 只有真图才有解码器 ==========

    /**
     * <b>这一条是第 3 关的哨兵。</b> 一个改了扩展名与 Content-Type 的文本文件能过前两关
     * (那两关只看调用方写的字符串), 只有走到 {@code ImageIO.getImageReaders} 才发现
     * 没有任何解码器认领它。
     *
     * <p>把第 3、4 关删掉、只留 Content-Type 白名单, 这个用例是**唯一**会红的那条。
     */
    @Test
    @DisplayName("文本文件改名叫 .png 并声明 image/png: 400 —— 前两关拦不住它")
    void aTextFileDeclaredAsPngIsRejected() {
        byte[] notAnImage = "这其实是一段文字, 只是被改了个名字".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.inspect(file("a.png", "image/png", notAnImage)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("这不是一张图片");
    }

    /**
     * GIF 声明成 PNG: <b>第 3 关放它过去(ImageIO 有 GIF 解码器), 拦住它的是
     * {@code mimeOf}。</b>
     *
     * <p>与上一条是两个不同的落点, 所以两条都要有: 上一条证明"字节必须真的可解码",
     * 这一条证明"解出来的格式还得是我们收的那两种"。少了这一条, 一个把 {@code mimeOf}
     * 换成"直接把声明的类型存进库"的改动不会被任何用例抓住 —— 而那一列最终会变成
     * 响应头, 存错了不是"显示不出来", 是让浏览器按错误的声明去解析一段字节。
     */
    @Test
    @DisplayName("GIF 声明成 PNG: 400 —— 能解码, 但解出来的不是 png/jpeg")
    void aGifDeclaredAsPngIsRejected() throws Exception {
        byte[] gif = imageBytes("gif", 32, 32);

        assertThatThrownBy(() -> service.inspect(file("a.png", "image/png", gif)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只支持 PNG 或 JPEG");
    }

    // ========== 第 3 关里的尺寸 ==========

    /**
     * <b>尺寸这一关必须跑在解码之前。</b> 一张纯色 PNG 可以只有几 KB, 却在文件头里声明
     * 30000×30000 —— 先 {@code ImageIO.read} 就是一个内存放大攻击面, 而大小上限拦不住它。
     *
     * <p>用例里的图是**真**的 2100×2100(不是伪头部), 因为要验的正是"读了头就知道它太大、
     * 于是根本没往下解码": 真解一张 2100×2100 也要不了命, 但用例若用伪头部就只能证明
     * "解析器会拒绝坏数据", 证明不了那一句"先读头"。
     */
    @Test
    @DisplayName("2100×2100: 400 —— 尺寸关在解码之前")
    void anOversizeImageIsRejectedByItsHeader() throws Exception {
        byte[] png = imageBytes("png", AvatarService.MAX_DIMENSION + 52, 64);

        assertThat(png.length)
                .as("纯色大图压完仍然远小于 512KB —— 大小上限对它是无效的, 这正是要单独一关的理由")
                .isLessThan(AvatarService.MAX_BYTES);
        assertThatThrownBy(() -> service.inspect(file("a.png", "image/png", png)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2048");
    }

    /** 太小的图放到评论区那个圆里必然是糊的 —— 与其让用户传一张自己都嫌糊的图, 不如当场说 */
    @Test
    @DisplayName("8×8: 400 —— 比最小边长还小")
    void aTinyImageIsRejected() throws Exception {
        byte[] png = imageBytes("png", 8, 8);

        assertThatThrownBy(() -> service.inspect(file("a.png", "image/png", png)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("16");
    }

    // ========== 正常路径 ==========

    @Test
    @DisplayName("PNG 通过: 类型与宽高都对, 字节原样带出来")
    void aPngPasses() throws Exception {
        byte[] png = imageBytes("png", 64, 48);

        AvatarService.Detected d = service.inspect(file("a.png", "image/png", png));

        assertThat(d.contentType()).isEqualTo(AvatarService.PNG);
        assertThat(d.width()).isEqualTo(64);
        assertThat(d.height()).isEqualTo(48);
        assertThat(d.bytes()).isEqualTo(png);
    }

    /**
     * JPEG 那一侧的格式名是 {@code "JPEG"}(全大写), 而 PNG 那个是 {@code "png"} ——
     * {@code ImageIO} 给的大小写不统一, 所以 {@code mimeOf} 要折成小写再比。
     * 这条用例是那个 {@code toLowerCase} 的哨兵。
     */
    @Test
    @DisplayName("JPEG 通过: 存进库的类型是 image/jpeg(格式名原本是全大写的 JPEG)")
    void aJpegPasses() throws Exception {
        byte[] jpeg = imageBytes("jpeg", 64, 64);

        assertThat(service.inspect(file("a.jpg", "image/jpeg", jpeg)).contentType())
                .isEqualTo(AvatarService.JPEG);
    }

    // ========== 写路径: 两步必须同生共死 ==========

    /**
     * 「存图」与「改 {@code user.avatar}」必须在同一个 {@code attempt} 里。
     *
     * <p>拆开会留下一个自相矛盾的状态(有图没地址 / 有地址没图), 而两种都不报错: 用户看到
     * 的是"上传成功"但头像没变, 再传一次还是老样子 —— 因为读的时候查的是
     * {@code user.avatar}。
     */
    @Test
    @DisplayName("上传: 存图与改 user.avatar 都在 attempt 的回调里, 且返回新地址")
    void uploadWritesBothRowsInsideOneTransaction() throws Exception {
        byte[] png = imageBytes("png", 64, 64);
        User user = User.builder().id(USER_ID).username("alice").build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        Map<String, Object> out = service.upload(user, file("a.png", "image/png", png));

        assertThat(isolatedInsert.maxDepth)
                .as("两次写都在 attempt 里面: 挪出去就是两个事务, 而拆开是不报错的坏法")
                .isEqualTo(1);

        ArgumentCaptor<UserAvatar> saved = ArgumentCaptor.forClass(UserAvatar.class);
        verify(avatarRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getValue().getContentType()).isEqualTo(AvatarService.PNG);
        assertThat(saved.getValue().getBytes()).isEqualTo(png);

        verify(userRepository).save(user);
        assertThat(user.getAvatar())
                .as("写回 user 的是地址, 不是图片本身")
                .isEqualTo(out.get("avatar"))
                .startsWith("/api/user/" + USER_ID + "/avatar");
    }

    @Test
    @DisplayName("删除: 删行与清地址都在 attempt 里, 本来就没有头像时也不报错(幂等)")
    void deleteClearsBothRows() {
        User user = User.builder().id(USER_ID).username("alice").avatar("/api/user/42/avatar?v=1").build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(avatarRepository.findById(USER_ID)).thenReturn(Optional.empty());

        service.delete(user);

        assertThat(isolatedInsert.maxDepth).isEqualTo(1);
        assertThat(user.getAvatar())
                .as("只删行不清地址会留下一条死链, 而每一处都只是显示不出图, 没有任何报错")
                .isNull();
        verify(userRepository).save(user);
        // 「本来就没有」不该调 delete —— 不是错, 只是不必
        verify(avatarRepository, never()).delete(any());
    }

    // ========== URL 与 ETag ==========

    /**
     * {@code ?v=} 是缓存失效的全部机制, 不是装饰。
     *
     * <p>没有它, 换头像之后那个 URL 不变, 而读图端点是带 {@code Cache-Control: max-age}
     * 的 —— 用户刚上传完, 页面上还是旧头像。把版本写进 URL, 缓存键就跟着字节一起变。
     * 这条用例断的是"同一张头像、两次上传 ⇒ 两个不同的地址"。
     */
    @Test
    @DisplayName("urlOf: 同一用户两次上传得到不同的地址(?v= 跟着上传时刻走)")
    void theUrlCarriesAVersionThatChangesWithTheUpload() {
        LocalDateTime first = LocalDateTime.of(2030, 1, 1, 12, 0, 0, 0);
        LocalDateTime second = first.plusSeconds(1);

        assertThat(AvatarService.urlOf(USER_ID, first))
                .isEqualTo("/api/user/42/avatar?v=" + (first.toEpochSecond(java.time.ZoneOffset.UTC) * 1000L))
                .isNotEqualTo(AvatarService.urlOf(USER_ID, second));
        // 同一次上传问两次必须一样 —— 否则每一次渲染都是一个新地址, 缓存等于没有
        assertThat(AvatarService.urlOf(USER_ID, first)).isEqualTo(AvatarService.urlOf(USER_ID, first));
    }

    @Test
    @DisplayName("etagOf: 内容变了它就变, 同一次上传问两次一样")
    void theEtagChangesWithTheUpload() {
        LocalDateTime first = LocalDateTime.of(2030, 1, 1, 12, 0, 0, 0);

        assertThat(AvatarService.etagOf(USER_ID, first))
                .isEqualTo(AvatarService.etagOf(USER_ID, first))
                .isNotEqualTo(AvatarService.etagOf(USER_ID, first.plusSeconds(1)))
                .as("按 HTTP 语法 opaque-tag 要包在双引号里")
                .startsWith("\"")
                .endsWith("\"");
    }

    @Test
    @DisplayName("find: 没有头像时回空, 而不是抛")
    void findReturnsEmptyWhenThereIsNoAvatar() {
        when(avatarRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThat(service.find(USER_ID)).isEmpty();
    }
}
