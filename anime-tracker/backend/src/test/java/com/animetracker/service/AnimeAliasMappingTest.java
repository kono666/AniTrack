package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import com.animetracker.config.RankingProperties;
import com.animetracker.dto.BangumiDTO.InfoboxItem;
import com.animetracker.dto.BangumiDTO.SubjectDTO;
import com.animetracker.entity.Anime;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.AnimeTagRepository;
import com.animetracker.repository.EpisodeRepository;
import com.animetracker.repository.TagRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * infobox 的别名有没有真的落到实体的 aliases 上.
 *
 * <p>为什么这条要单独验: {@code AnimeAliases} 那个纯函数测得再全, 也只是"能抠出来";
 * 抠出来的东西有没有被 {@code applySubject} 写进实体、写的时候会不会把别人先前写进去的
 * 抹掉, 是另一件事. 而这一层的错法同样安静 —— 别名没写进去, 搜索就继续搜不到, 界面
 * 上跟"这个类根本没做"一模一样.
 *
 * <p>不启 Spring: 这里要验的是一处赋值, 不该顺带连库、跑启动预加载(它会联网).
 */
class AnimeAliasMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ID = 265;

    private AnimeRepository animeRepository;
    private AnimeService animeService;

    /** save 收到的那个实体 —— 断言就断言它, 而不是 upsertAnime 的返回值(是同一个, 但要确认) */
    private Anime saved;

    @BeforeEach
    void setUp() {
        animeRepository = mock(AnimeRepository.class);
        animeService = new AnimeService(
                animeRepository,
                mock(EpisodeRepository.class),
                mock(TagRepository.class),
                mock(AnimeTagRepository.class),
                mock(BangumiApiClient.class),
                mock(BangumiApiProperties.class),
                new RankingProperties(),
                new ConcurrentMapCacheManager());

        when(animeRepository.save(any(Anime.class))).thenAnswer(inv -> {
            saved = inv.getArgument(0);
            return saved;
        });
    }

    private static SubjectDTO dtoWithAliases() {
        SubjectDTO dto = new SubjectDTO();
        dto.setId(ID);
        dto.setName("新世紀エヴァンゲリオン");
        dto.setNameCn("新世纪福音战士");

        InfoboxItem cn = new InfoboxItem();
        cn.setKey("中文名");
        cn.setValue(text("\"新世纪福音战士\""));
        InfoboxItem aliases = new InfoboxItem();
        aliases.setKey("别名");
        aliases.setValue(text("""
                [{"v":"Neon Genesis Evangelion"},
                 {"k":"中国大陆公映版译名","v":"天鹰战士"},
                 {"v":"EVA"}]
                """));
        dto.setInfobox(List.of(cn, aliases));
        return dto;
    }

    private static com.fasterxml.jackson.databind.JsonNode text(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("测试用的 JSON 写错了: " + json, e);
        }
    }

    @Test
    @DisplayName("infobox 的别名被写进实体的 aliases（逗号分隔, 只取 v）")
    void writesAliasesFromInfobox() {
        animeService.upsertAnime(dtoWithAliases());

        assertThat(saved.getAliases())
                .isEqualTo("Neon Genesis Evangelion,天鹰战士,EVA");
    }

    /**
     * 第二个 DTO 没有 infobox 时, 已有的别名**不能被抹掉**.
     *
     * <p>这是 {@code applySubject} 里"有值才覆盖"那条规矩在别名上的具体后果, 而且它比别的
     * 字段更容易踩: Bangumi 的搜索接口不是每个条目都带 infobox(详情接口才比较全), 于是
     * "搜索同步一次"完全可能把一个已经有别名的条目再写一遍 —— 无条件赋值的话, 那一次搜索
     * 就把别名清成了 null, 下次搜别名又搜不到, 且看不出是谁清的.
     */
    @Test
    @DisplayName("后续同步没带 infobox 时, 已写进去的别名不被抹掉")
    void doesNotWipeAliasesWhenInfoboxIsAbsent() {
        animeService.upsertAnime(dtoWithAliases());
        assertThat(saved.getAliases()).isNotBlank();

        // 第二次: 同一个 id, 带回来的 DTO 没有 infobox(模拟搜索接口那条路径)
        when(animeRepository.findById(ID)).thenReturn(Optional.of(saved));
        SubjectDTO withoutInfobox = new SubjectDTO();
        withoutInfobox.setId(ID);
        withoutInfobox.setName("新世紀エヴァンゲリオン");
        animeService.upsertAnime(withoutInfobox);

        assertThat(saved.getAliases())
                .as("搜索同步不该把详情拿到的别名抹掉")
                .isEqualTo("Neon Genesis Evangelion,天鹰战士,EVA");
    }
}
