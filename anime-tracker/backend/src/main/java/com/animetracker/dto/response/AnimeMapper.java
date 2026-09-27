package com.animetracker.dto.response;

import com.animetracker.entity.Anime;
import com.animetracker.util.TagTranslationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Anime 实体 → AnimeDTO 转换器.
 *
 * <p>独立 Component 而非 DTO 上的静态方法, 便于:
 * <ul>
 *   <li>注入依赖(TagTranslationUtil 等)</li>
 *   <li>单元测试时 Mock</li>
 *   <li>扩展转换逻辑(如从其它数据源补全 collection 信息)</li>
 * </ul>
 */
@Component
public class AnimeMapper {

    private static final Logger log = LoggerFactory.getLogger(AnimeMapper.class);

    private static final String DEFAULT_PLATFORM = "TV";
    private static final AnimeDTO.CollectionInfo EMPTY_COLLECTION =
            new AnimeDTO.CollectionInfo(0, 0, 0, 0, 0);

    /**
     * 列表项 DTO — 不包含 summary / tags / rank, 减少传输量.
     */
    public AnimeDTO toListItem(Anime entity) {
        if (entity == null) return null;
        return AnimeDTO.builder()
                .id(entity.getId())
                .name(entity.getTitle())
                .nameCn(entity.getTitleCn())
                .date(entity.getDate())
                .totalEpisodes(entity.getTotalEpisodes())
                .images(buildImages(entity.getCoverUrl()))
                .rating(buildRating(entity))
                .collection(EMPTY_COLLECTION)
                .build();
    }

    /**
     * 详情 DTO — 包含完整信息.
     */
    public AnimeDTO toDetail(Anime entity) {
        if (entity == null) return null;
        AnimeDTO dto = toListItem(entity);
        dto.setSummary(entity.getSummary());
        dto.setPlatform(entity.getPlatform() != null ? entity.getPlatform() : DEFAULT_PLATFORM);
        dto.setRank(entity.getRank());
        dto.setTags(buildTags(entity.getTags()));
        return dto;
    }

    /**
     * 批量转换列表
     */
    public List<AnimeDTO> toListItems(Collection<Anime> entities) {
        return entities.stream()
                .filter(Objects::nonNull)
                .map(this::toListItem)
                .collect(Collectors.toList());
    }

    // ── 私有构建方法 ──────────────────────────────────

    private Map<String, String> buildImages(String coverUrl) {
        String url = coverUrl != null ? coverUrl : "";
        // 本地库只存一张封面图, 三个尺寸字段复用同一 URL (对齐 Bangumi 的 images 结构)
        return Map.of("large", url, "common", url, "medium", url);
    }

    private AnimeDTO.RatingInfo buildRating(Anime entity) {
        return new AnimeDTO.RatingInfo(
                entity.getRating() != null ? entity.getRating() : 0.0,
                entity.getRatingCount() != null ? entity.getRatingCount() : 0
        );
    }

    private List<AnimeDTO.TagInfo> buildTags(String tagsCsv) {
        if (tagsCsv == null || tagsCsv.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(tagsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(tag -> new AnimeDTO.TagInfo(TagTranslationUtil.translate(tag), 0))
                .collect(Collectors.toList());
    }
}
