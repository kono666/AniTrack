package com.animetracker.service;

import com.animetracker.entity.User;
import com.animetracker.entity.UserAvatar;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.UserAvatarRepository;
import com.animetracker.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 头像的上传 / 读取 / 删除。
 *
 * <p><b>这是本仓第一个、也是目前唯一一个接受文件上传的地方</b>, 所以下面几件事在这里
 * 定下来, 后来的上传功能(如果有)照这个形状走。
 *
 * <h2>四道关卡, 顺序不能换(先便宜后贵)</h2>
 *
 * <ol>
 *   <li><b>大小</b> ≤ {@value #MAX_BYTES} 字节。{@code spring.servlet.multipart.max-file-size}
 *       是第一道(它连请求体都不收完就拒了), 这里再判一次是**防线**而非重复:
 *       走 multipart 之外的路径(或者将来有人把那个配置调大)时, 只有这里还在。</li>
 *   <li><b>声明的 Content-Type</b> 在白名单里。白名单**只有 png 与 jpeg, 不含 webp** ——
 *       这不是偷懒: {@code ImageIO} 默认根本没有 WebP 解码器, 把它放进白名单会让
 *       第 4 关把所有 webp 一律拒掉, 那条白名单就成了假话。要收 webp 得先加依赖,
 *       那件事没做之前别把它写进这里。</li>
 *   <li><b>先读文件头拿宽高, 再解码。</b> 一张纯色 PNG 可以只有几 KB, 却在 IHDR 里
 *       声明 30000×30000 —— 先解码就是一个内存放大攻击面, 而大小上限拦不住它
 *       (压得好的话几百 KB 能声明出几 GB 的位图)。所以尺寸这一关必须跑在
 *       {@code ImageIO.read} 之前, 靠 {@code ImageReader.getWidth/getHeight} 只读头部。</li>
 *   <li><b>真的能解码, 且用解出来的类型。</b> 前两关看的都是调用方自己写的字符串,
 *       一个改掉扩展名与 Content-Type 的任意二进制能全部通过。这一关才第一次碰到字节。
 *       存库的 {@code content_type} 用的是**从字节判出来的那个**(见 {@link #mimeOf}),
 *       不是请求里那个 —— 这一列最终会变成响应头, 存错了就不是"显示不出来",
 *       而是让浏览器按错误的声明去解析一段字节。</li>
 * </ol>
 *
 * <h2>为什么这个类不带类级 {@code @Transactional}</h2>
 *
 * <p>与 {@link AdminService} 同一条理由(见那个类的注释): 读路径本来就不需要事务,
 * 而写路径里只有「存图 + 改 {@code user.avatar}」这两步需要同生共死, 用
 * {@link IsolatedInsert#attempt} 显式圈出来比给整个类加注解更准确。
 * 这两步拆开会出现「图存了、URL 没写」—— 用户看到的是「上传成功」但头像没变,
 * 而且再传一次也还是老样子, 因为它查的是 {@code user.avatar}。
 */
@Service
public class AvatarService {

    /**
     * 单张图片的字节上限。
     *
     * <p><b>必须与 {@code application.yml} 的 {@code spring.servlet.multipart.max-file-size}
     * 同值</b>(那里写成 512KB)。这一侧更大就等于上传永远先被容器拒掉、这里的提示语
     * 一次都看不到; 更小就等于容器放行了、用户却收到一句莫名其妙的 400。
     * 两边同值时, 第 1 关只是「容器那道没拦住时」的兜底。
     */
    public static final int MAX_BYTES = 512 * 1024;

    /** 宽高的上限。2048×2048 的位图约 16MB, 是解码这一步能承受的量级 */
    public static final int MAX_DIMENSION = 2048;

    /**
     * 宽高的下限。比这更小的图放到评论区那个圆形里必然是糊的 ——
     * 与其让用户传一张自己看着都难受的图, 不如当场说清楚。
     */
    public static final int MIN_DIMENSION = 16;

    public static final String PNG = "image/png";
    public static final String JPEG = "image/jpeg";

    /** 白名单。见类注释第 2 关: **不含 webp**, 加它之前先确认解码器在 */
    private static final Set<String> ALLOWED_TYPES = Set.of(PNG, JPEG);

    private final UserAvatarRepository avatarRepository;
    private final UserRepository userRepository;
    private final IsolatedInsert isolatedInsert;

    public AvatarService(UserAvatarRepository avatarRepository, UserRepository userRepository,
                         IsolatedInsert isolatedInsert) {
        this.avatarRepository = avatarRepository;
        this.userRepository = userRepository;
        this.isolatedInsert = isolatedInsert;
    }

    /**
     * 头像的地址 —— <b>全仓唯一一个拼这个 URL 的地方</b>。
     *
     * <p>它会被写进 {@code user.avatar}, 而那一列有 6 处读(评论、回复、通知、个人页…)。
     * 那些读路径一个字都不用改, 代价是「改路由要配一条数据迁移」—— 所以这个字符串
     * 只能从这里出来, 别在别处手拼。
     *
     * <p><b>{@code ?v=} 是刻意加的, 不是装饰。</b> 没有它, 换头像之后那个 URL 不变,
     * 而读图端点是带 {@code Cache-Control: max-age} 的 —— 用户刚上传完, 页面上还是
     * 旧头像, 而任何一处「用户以为没生效、其实是缓存」的问题都极难排查(强刷一下就好了,
     * 所以它只会出现在一部分人身上)。把**版本写进 URL**, 缓存键就跟着字节一起变:
     * 上传覆盖后, 6 处读路径拿到的都是新地址, 不需要任何一处知道"缓存"这回事。
     *
     * <p>版本取的是上传时刻的毫秒。用 {@code ZoneOffset.UTC} 是因为它只是个不透明的
     * 版本号, 与"那个瞬间在哪个时区"无关 —— 而写死一个基准反而让它**跨机器可复现**,
     * 用例才钉得住它。
     */
    public static String urlOf(Long userId, LocalDateTime updatedAt) {
        long version = updatedAt.toEpochSecond(ZoneOffset.UTC) * 1000L + updatedAt.getNano() / 1_000_000L;
        return "/api/user/" + userId + "/avatar?v=" + version;
    }

    /**
     * 读图那个端点的 ETag。
     *
     * <p>取值是「谁的头像 + 哪一次上传」——**内容变了它就变**, 这正是 ETag 要求的语义
     * (强校验器: 值相同即字节相同)。用 {@code updatedAt} 而不是对字节做哈希: 后者要在
     * 每次请求上算一遍几百 KB 的摘要, 而上传时刻本来就是"这一版"的标识。
     *
     * <p>按 HTTP 的语法, {@code opaque-tag} 里允许 {@code :} 与 {@code .}, 所以直接用
     * {@link LocalDateTime#toString()} 是合法的(包在双引号里)。不换成时间戳数字是为了
     * 让抓包时一眼看出这是哪一次上传。
     *
     * <p>{@code updatedAt} 为 null 时退化成 {@code "id-null"}: 那只可能是历史行,
     * 而退化的结果是"所有人共用同一个 ETag"—— 在只有历史行的情况下等价于不变, 无害。
     */
    public static String etagOf(Long userId, LocalDateTime updatedAt) {
        return "\"" + userId + "-" + updatedAt + "\"";
    }

    /** 一张已经存下来的头像 */
    public record Image(String contentType, byte[] bytes, LocalDateTime updatedAt) {
    }

    /** 四道关卡都过完之后, 从字节里读出来的事实 */
    record Detected(String contentType, byte[] bytes, int width, int height) {
    }

    /**
     * 收下一张图, 覆盖这个用户原来的头像。
     *
     * <p>返回给前端的新 URL —— 它同时也是写进 {@code user.avatar} 的那一个。
     *
     * <p>「存图」与「改 {@code user.avatar}」在同一个事务里: 少任何一步都会留下
     * 一个自相矛盾的状态(有图没地址 / 有地址没图), 而两种都不报错。
     */
    public Map<String, Object> upload(User user, MultipartFile file) {
        Detected image = inspect(file);
        LocalDateTime now = LocalDateTime.now();
        String url = urlOf(user.getId(), now);

        isolatedInsert.attempt(() -> {
            avatarRepository.save(UserAvatar.builder()
                    .userId(user.getId())
                    .contentType(image.contentType())
                    .bytes(image.bytes())
                    .updatedAt(now)
                    .build());
            User target = userRepository.findById(user.getId())
                    .orElseThrow(() -> BusinessException.notFound("用户不存在"));
            target.setAvatar(url);
            userRepository.save(target);
            return null;
        });

        return Map.of("avatar", url);
    }

    /**
     * 取一张头像。为空表示这个人没传过头像 —— 调用方回 404, 前端本来就有首字母兜底,
     * 所以"没有"是一种正常状态, 不是错误。
     */
    public Optional<Image> find(Long userId) {
        return avatarRepository.findById(userId)
                .map(a -> new Image(a.getContentType(), a.getBytes(), a.getUpdatedAt()));
    }

    /**
     * 删掉头像, 退回默认。
     *
     * <p>两步(删行、清 {@code user.avatar})同样必须同生共死 —— 只清 URL 会留下一条
     * 谁也读不到的行(占着库, 且下次上传要覆盖它), 只删行会让 URL 变成死链
     * (每一处都显示不出图, 而且没有任何报错)。
     *
     * <p>「本来就没有头像」不是错误: 这个端点的心智是"把我变回默认", 而已经默认的人
     * 调它一次, 结果与调用前完全一致, 属于幂等。
     */
    public void delete(User user) {
        isolatedInsert.attempt(() -> {
            avatarRepository.findById(user.getId()).ifPresent(avatarRepository::delete);
            User target = userRepository.findById(user.getId())
                    .orElseThrow(() -> BusinessException.notFound("用户不存在"));
            target.setAvatar(null);
            userRepository.save(target);
            return null;
        });
    }

    /**
     * 四道关卡。写成一个可以单独调用的方法是为了让用例能只测校验、不碰库。
     *
     * <p>顺序见类注释, 这里只说一处实现上的讲究: 第 3 关用的是
     * {@code ImageIO.getImageReaders} + {@code ImageReader.getWidth}, 它**只读头部**,
     * 不分配整张位图; 而第 4 关的 {@code ImageIO.read} 才是那个会真的解出像素、
     * 因此必须放在最后的步骤。
     */
    Detected inspect(MultipartFile file) {
        // —— 第 1 关: 大小 ——
        // 判空放在大小之前: 空文件的失败理由不该是"太大了", 那对用户毫无信息量。
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("请选择一张图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw BusinessException.badRequest("图片不能超过 " + (MAX_BYTES / 1024) + "KB");
        }

        // —— 第 2 关: 声明的类型 ——
        String declared = file.getContentType();
        if (declared == null || !ALLOWED_TYPES.contains(declared.toLowerCase(Locale.ROOT))) {
            throw BusinessException.badRequest("只支持 PNG 或 JPEG 格式的图片");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            // 从内存里读 MultipartFile 一般不会失败, 但真失败了要说人话, 别掉进 500
            throw BusinessException.badRequest("图片读取失败, 请重试");
        }

        // —— 第 3 关: 只读文件头拿尺寸 ——
        String formatName;
        int width;
        int height;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                throw BusinessException.badRequest("这不是一张图片");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                // 这一条是"假图片"最主要的落点: 改了扩展名与 Content-Type 的任意二进制
                // 走不到这里就没有任何解码器认领它。
                throw BusinessException.badRequest("这不是一张图片");
            }
            ImageReader reader = readers.next();
            try {
                // seekForwardOnly=true 让它能用在只能往前读的流上; ignoreMetadata=true
                // 是因为这一关只要宽高, 元数据解析是纯浪费。
                reader.setInput(in, true, true);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
                // getFormatName() 是 reader 自身的属性, 与读没读过、读到哪无关,
                // 所以顺手在这里问出来, 不必为了它再开一次流。
                formatName = reader.getFormatName();
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            // 头部读坏了(截断、伪造)。同样是调用方的问题, 不该是 500。
            throw BusinessException.badRequest("图片已损坏或不完整");
        }

        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw BusinessException.badRequest(
                    "图片尺寸不能超过 " + MAX_DIMENSION + "×" + MAX_DIMENSION + " 像素");
        }
        if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
            throw BusinessException.badRequest(
                    "图片太小了, 至少 " + MIN_DIMENSION + "×" + MIN_DIMENSION + " 像素");
        }

        // —— 第 4 关: 真的解一次, 类型以字节为准 ——
        try {
            if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) {
                throw BusinessException.badRequest("这不是一张图片");
            }
        } catch (IOException e) {
            throw BusinessException.badRequest("图片已损坏或不完整");
        }

        return new Detected(mimeOf(formatName), bytes, width, height);
    }

    /**
     * 格式名 → MIME 类型, <b>同时是"字节真的是不是我们收的那两种"这一道闸</b>。
     *
     * <p>第 2 关查的是调用方**声明**的类型, 一个 GIF 文件声明成 {@code image/png} 能过;
     * 这里查的是**实际**的类型, 它过不去。两关查的不是同一件事, 所以都要有。
     *
     * <p>注意 {@code ImageIO} 给的格式名大小写不统一: PNG 解码器回 {@code "png"},
     * JPEG 那个回 {@code "JPEG"}。所以统一折成小写再比。
     */
    private static String mimeOf(String formatName) {
        String f = formatName == null ? "" : formatName.toLowerCase(Locale.ROOT);
        if (f.contains("png")) {
            return PNG;
        }
        if (f.contains("jpeg") || f.contains("jpg")) {
            return JPEG;
        }
        throw BusinessException.badRequest("只支持 PNG 或 JPEG 格式的图片");
    }
}
