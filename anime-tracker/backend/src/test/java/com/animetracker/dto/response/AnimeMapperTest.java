package com.animetracker.dto.response;

import com.animetracker.entity.Anime;
import com.animetracker.util.CoverImages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code images} 这个字段原来**一处用例都没有** —— 它是列表页、详情页、搜索、
 * Agent 工具取的同一份封面, 而在此之前没有任何东西钉住它的形状。这次封面开始走代理,
 * 顺手把这张网补上: 改这一处就该有东西红。
 *
 * <p>要钉住的是三件事, 每件都对应一种"看着正常但坏了"的错法:
 *
 * <ol>
 *   <li><b>三个尺寸键都在且同值。</b>前端读的是 {@code images.large}(列表页有兜底,
 *       详情页没有)。少一个键不会报错, 只是那一处悄悄变空白。</li>
 *   <li><b>白名单外的地址原样返回。</b>库里存量有 {@code http://} 的老行, 一旦被
 *       一律改写成代理地址, 它们会从一个必然 400 的代理上取图 —— 从"还能显示"变成
 *       "一定不显示"。这条在 {@code CoverImagesTest} 里也有, 但那时验的是纯函数;
 *       这里验的是<b>它真的接在了这处调用点上</b>。</li>
 *   <li><b>null 封面出的是空串而不是 null。</b>{@code Map.of} 不允许 null 值, 而这里
 *       三个键必须都存在 —— 前端对所有尺寸一视同仁地读 {@code .large}, 缺键与空串
 *       在 {@code <img src="">} 上的表现一个是"不发请求"、一个是"请求当前页",
 *       后者会让列表里每张缺图都多打一次自己。空串是那个想被表达的值。</li>
 * </ol>
 *
 * <p>这里刻意不写成 {@code @SpringBootTest}: {@code AnimeMapper} 没有注入的依赖
 * (它只用了静态工具), 起整个容器只会把数据库与预加载器一起拖进来, 而它们与
 * 这几条断言毫无关系。
 */
class AnimeMapperTest {

    /** 白名单内的真封面 (lain.bgm.tv + https + /pic/) */
    private static final String REAL_COVER = "https://lain.bgm.tv/pic/cover/l/aa/01/123.jpg";

    /** 白名单外的存量老地址: 同一个上游但是 http */
    private static final String LEGACY_HTTP_COVER = "http://lain.bgm.tv/pic/cover/l/aa/01/old.jpg";

    private final AnimeMapper mapper = new AnimeMapper();

    @Test
    @DisplayName("白名单内的封面: 三个尺寸都换成代理地址, 且带上原地址")
    void realCoverGoesThroughTheProxy() {
        AnimeDTO dto = mapper.toListItem(animeWithCover(REAL_COVER));

        assertThat(dto.getImages())
                .as("三个尺寸键一个都不能少")
                .containsOnlyKeys("large", "common", "medium");

        assertThat(dto.getImages().values())
                .as("三个尺寸本地只有一张图, 必须是同一个值")
                .allMatch(v -> v.equals(dto.getImages().get("large")))
                .allSatisfy(v -> assertThat(v).startsWith(CoverImages.PROXY_PATH + "?url="));
    }

    /**
     * 代理地址里那个 {@code url} 参数解码后, 必须**正好**是白名单校验通过的那串 ——
     * 而不是它的一部分、也不是过了第二遍编码的样子。
     *
     * <p>这条是「往返」断言: 生成与消费这两侧分处两个文件({@code AnimeMapper} 只在生成侧,
     * {@code CoverImageService} 只在消费侧), 两边对编码方式的假设一旦飘开, 症状是
     * <b>所有封面都 400 "不支持的图片地址"</b> —— 但只在真机上才看得到, 因为没有任何
     * 单元测试真的把这两个方向接起来。这里用 {@code CoverImages.upstream} 当消费者的
     * 替身: 它就是 {@code CoverImageService} 拿来判白名单的那个函数。
     */
    @Test
    @DisplayName("往返: 代理地址里的 url 参数解出来就是原地址, 且仍过得了白名单")
    void theProxiedUrlRoundTrips() {
        String proxied = mapper.toListItem(animeWithCover(REAL_COVER)).getImages().get("large");

        String encoded = proxied.substring((CoverImages.PROXY_PATH + "?url=").length());
        String decoded = org.springframework.web.util.UriUtils.decode(encoded, StandardCharsets.UTF_8);

        assertThat(decoded).isEqualTo(REAL_COVER);
        assertThat(CoverImages.upstream(decoded))
                .as("消费侧还得能认回来 —— 生成侧编出来的串若过不了自己的白名单, 就是全站 400")
                .isEqualTo(REAL_COVER);
    }

    @Test
    @DisplayName("白名单外的存量地址: 一个字都不改, 仍按改动前那样直连")
    void nonWhitelistedCoverStaysUntouched() {
        AnimeDTO dto = mapper.toListItem(animeWithCover(LEGACY_HTTP_COVER));

        assertThat(dto.getImages().values())
                .as("被改写成代理地址的话, 这些行会从'还能显示'变成'一定不显示'")
                .allSatisfy(v -> assertThat(v).isEqualTo(LEGACY_HTTP_COVER));
    }

    @Test
    @DisplayName("没封面: 三个键是空串而不是 null, 也不是缺键")
    void missingCoverIsAnEmptyString() {
        AnimeDTO dto = mapper.toListItem(animeWithCover(null));

        assertThat(dto.getImages())
                .as("键必须都在 —— 前端读 images.large 时不判存在性")
                .containsOnlyKeys("large", "common", "medium");
        assertThat(dto.getImages().values())
                .as("空串: <img src=\"\"> 不发请求, 缺键才是问题")
                .allSatisfy(v -> assertThat(v).isEmpty());
    }

    /**
     * 详情页的封面与列表页必须是同一个 URL。
     *
     * <p>{@code toDetail} 是复用 {@code toListItem} 的, 所以这条今天是白拿的; 写下来是
     * 因为它<b>不必一直白拿</b> —— 详情页哪天自己拼一次 images, 就会出现"列表页走代理、
     * 详情页直连"的分叉, 而两个页面各自看都是对的。
     */
    @Test
    @DisplayName("详情页与列表页用的是同一份 images")
    void detailReusesTheListImages() {
        Anime entity = animeWithCover(REAL_COVER);

        Map<String, String> list = mapper.toListItem(entity).getImages();
        Map<String, String> detail = mapper.toDetail(entity).getImages();

        assertThat(detail).isEqualTo(list);
    }

    @Test
    @DisplayName("null 实体进不出 NPE, 列表里的 null 被滤掉")
    void nullsAreHandled() {
        assertThat(mapper.toListItem(null)).isNull();
        assertThat(mapper.toDetail(null)).isNull();

        List<AnimeDTO> dtos = mapper.toListItems(Arrays.asList(animeWithCover(REAL_COVER), null));
        assertThat(dtos).hasSize(1);
    }

    private static Anime animeWithCover(String coverUrl) {
        return Anime.builder()
                .id(1)
                .title("原名")
                .titleCn("中文名")
                .coverUrl(coverUrl)
                .build();
    }
}
