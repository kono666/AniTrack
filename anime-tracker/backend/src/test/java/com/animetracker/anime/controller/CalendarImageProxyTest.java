package com.animetracker.anime.controller;

import com.animetracker.anime.dto.AnimeMapper;
import com.animetracker.anime.dto.BangumiDTO.CalendarDay;
import com.animetracker.anime.dto.BangumiDTO.CalendarItem;
import com.animetracker.anime.dto.BangumiDTO.ImagesDTO;
import com.animetracker.anime.service.AnimeService;
import com.animetracker.anime.service.SubjectExtrasService;
import com.animetracker.common.config.SubjectExtrasRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 日历接口出口处的图片地址改写。
 *
 * <p>守的是一条部署期问题：Bangumi 日历返回 {@code http://lain.bgm.tv/…} 明文地址，
 * 白名单只收 https，原样透传在 https 站点上会被浏览器按混合内容拦掉 —— 本地开发
 * 永远看不出来，上线才会整页白图。所以这里断言的是**出口形状**：
 * 五个尺寸都必须变成同源 {@code /api/img?url=https%3A%2F%2F…}。
 *
 * <p>用 Mockito 直接构造控制器而不是起 Spring 上下文：这条逻辑是纯数据改写，
 * 不需要数据库、过滤器链或 Flyway 参与；起上下文只会让用例慢十秒。
 */
class CalendarImageProxyTest {

    private static final String L = "http://lain.bgm.tv/pic/cover/l/1c/b4/390200.jpg";
    private static final String C = "http://lain.bgm.tv/pic/cover/c/1c/b4/390200.jpg";
    private static final String M = "http://lain.bgm.tv/pic/cover/m/1c/b4/390200.jpg";
    private static final String G = "http://lain.bgm.tv/pic/cover/g/1c/b4/390200.jpg";
    private static final String S = "http://lain.bgm.tv/pic/cover/s/1c/b4/390200.jpg";

    private BangumiController controller(AnimeService animeService) {
        return new BangumiController(
                animeService,
                mock(AnimeMapper.class),
                mock(SubjectExtrasService.class),
                mock(SubjectExtrasRateLimiter.class));
    }

    private static ImagesDTO httpImages() {
        ImagesDTO images = new ImagesDTO();
        images.setLarge(L);
        images.setCommon(C);
        images.setMedium(M);
        images.setGrid(G);
        images.setSmall(S);
        return images;
    }

    private static CalendarDay dayWith(ImagesDTO images) {
        CalendarItem item = new CalendarItem();
        item.setId(390200);
        item.setName("列女战纪：女娲石记");
        item.setImages(images);

        CalendarDay day = new CalendarDay();
        day.setWeekday(java.util.Map.of("en", "Mon", "cn", "星期一", "id", "1"));
        day.setItems(List.of(item));
        return day;
    }

    @Test
    @DisplayName("五个尺寸的 http 明文地址都被升级成 https 并换成同源代理")
    void everySizeIsProxiedOverHttps() {
        AnimeService animeService = mock(AnimeService.class);
        ImagesDTO images = httpImages();
        when(animeService.getCalendar()).thenReturn(List.of(dayWith(images)));

        controller(animeService).getCalendar();

        for (String url : List.of(images.getLarge(), images.getCommon(), images.getMedium(),
                images.getGrid(), images.getSmall())) {
            // 注意 UriUtils.encodeQueryParam 不编码 : 与 /（它们在 query 里合法），
            // 所以这里是明文 https://……，不是 %3A%2F%2F
            assertThat(url)
                    .startsWith("/api/img?url=https://lain.bgm.tv/pic/cover/")
                    .as("必须是 https，否则 https 站点仍会被判混合内容")
                    .doesNotContain("url=http://");
        }
    }

    @Test
    @DisplayName("已经是代理地址的（缓存里那份）再跑一遍不变 —— 这条改写是幂等的")
    void rewritingIsIdempotent() {
        AnimeService animeService = mock(AnimeService.class);
        ImagesDTO images = httpImages();
        when(animeService.getCalendar()).thenReturn(List.of(dayWith(images)));

        BangumiController controller = controller(animeService);
        controller.getCalendar();
        String once = images.getLarge();
        controller.getCalendar();

        assertThat(images.getLarge()).isEqualTo(once);
    }

    @Test
    @DisplayName("images 或 items 为 null 时不炸（上游某些条目就是没有图）")
    void nullsAreTolerated() {
        AnimeService animeService = mock(AnimeService.class);

        CalendarDay noItems = new CalendarDay();
        noItems.setItems(null);

        CalendarItem itemWithoutImages = new CalendarItem();
        itemWithoutImages.setId(1);
        CalendarDay noImages = new CalendarDay();
        noImages.setItems(List.of(itemWithoutImages));

        when(animeService.getCalendar()).thenReturn(List.of(noItems, noImages));

        assertThat(controller(animeService).getCalendar().getData()).hasSize(2);
    }
}
