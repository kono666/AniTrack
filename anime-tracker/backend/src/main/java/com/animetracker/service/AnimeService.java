package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import com.animetracker.dto.BangumiDTO.*;
import com.animetracker.entity.Anime;
import com.animetracker.entity.AnimeTag;
import com.animetracker.entity.Episode;
import com.animetracker.entity.Tag;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.AnimeTagRepository;
import com.animetracker.repository.EpisodeRepository;
import com.animetracker.repository.TagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import com.animetracker.util.TagTranslationUtil;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnimeService {
    // 不依赖 rank 字段(API不返回)，用评分排序

    private static final Logger log = LoggerFactory.getLogger(AnimeService.class);

    private final AnimeRepository animeRepository;
    private final EpisodeRepository episodeRepository;
    private final TagRepository tagRepository;
    private final AnimeTagRepository animeTagRepository;
    private final BangumiApiClient bangumiApiClient;
    private final BangumiApiProperties props;

    public AnimeService(AnimeRepository animeRepository,
                        EpisodeRepository episodeRepository,
                        TagRepository tagRepository,
                        AnimeTagRepository animeTagRepository,
                        BangumiApiClient bangumiApiClient,
                        BangumiApiProperties props) {
        this.animeRepository = animeRepository;
        this.episodeRepository = episodeRepository;
        this.tagRepository = tagRepository;
        this.animeTagRepository = animeTagRepository;
        this.bangumiApiClient = bangumiApiClient;
        this.props = props;
    }

    // ==================== 搜索 ====================

    /** 搜索：先本地，本地不够再调 API */
    public Map<String, Object> searchAnime(String keyword, int page, int limit) {
        boolean hasKeyword = keyword != null && !keyword.trim().isEmpty();

        if (hasKeyword) {
            // 有关键词：本地搜
            List<Anime> local = animeRepository.searchByKeyword(keyword.trim());
            if (local.size() >= limit) {
                return buildSearchResult(local, page, limit);
            }
            // 本地不够，调 API 补充
            SearchResponse resp = bangumiApiClient.searchSubjects(keyword, 1, Math.max(limit, 20));
            if (resp != null && resp.getData() != null && !resp.getData().isEmpty()) {
                cacheAll(resp.getData());
                local = animeRepository.searchByKeyword(keyword.trim());
            }
            return buildSearchResult(local, page, limit);
        } else {
            // 无关键词：返回全部本地数据（排行页用）
            List<Anime> all = animeRepository.findByOrderByRatingDesc();
            return buildSearchResult(all, page, limit);
        }
    }

    // ==================== 排行 / 最新 / 浏览 ====================
    // 以下全部从本地缓存读取，启动预加载器已拉取 Top 200 到本地

    @Cacheable(value = "ranking", key = "'rank_' + #limit")
    public List<Anime> getRanking(int limit) {
        List<Anime> local = animeRepository.findByOrderByRatingDesc();
        // 本地不够20条时从 API 补充热门排行
        if (local.size() < Math.min(limit, 20)) {
            try {
                SearchResponse resp = bangumiApiClient.searchSubjects("", 1, Math.max(limit, 30));
                if (resp != null && resp.getData() != null) {
                    cacheAll(resp.getData());
                    local = animeRepository.findByOrderByRatingDesc();
                    log.info("排行榜: API补充后共 {} 条", local.size());
                }
            } catch (Exception e) {
                log.warn("排行榜API补充失败: {}", e.getMessage());
            }
        }
        return local.size() > limit ? local.subList(0, limit) : local;
    }

    @Cacheable(value = "latest", key = "#limit")
    public List<Anime> getLatest(int limit) {
        List<Anime> local = animeRepository.findByOrderByDateDesc();
        // 检查最新一条是否在3个月内, 超过则从API补充
        boolean needRefresh = local.isEmpty();
        if (!needRefresh && local.get(0).getDate() != null) {
            try {
                String topDate = local.get(0).getDate();
                if (topDate.length() >= 7) {
                    java.time.YearMonth topYm = java.time.YearMonth.parse(topDate.substring(0, 7));
                    needRefresh = topYm.isBefore(java.time.YearMonth.now().minusMonths(3));
                }
            } catch (Exception e) { needRefresh = local.size() < limit; }
        }

        if (needRefresh) {
            try {
                int year = java.time.Year.now().getValue();
                for (String kw : new String[]{String.valueOf(year), String.valueOf(year - 1), "新番", "剧场版"}) {
                    SearchResponse resp = bangumiApiClient.searchSubjects(kw, 1, 20);
                    if (resp != null && resp.getData() != null) {
                        cacheAll(resp.getData());
                    }
                    Thread.sleep(500);
                }
                local = animeRepository.findByOrderByDateDesc();
                log.info("最新: API补充后共 {} 条", local.size());
            } catch (Exception e) {
                log.warn("最新API补充失败: {}", e.getMessage());
            }
        }
        return local.size() > limit ? local.subList(0, limit) : local;
    }

    /** 每日放送：缓存2小时 (番剧排期不会频繁变动) */
    @Cacheable(value = "calendar", key = "'today'")
    public List<CalendarDay> getCalendar() {
        List<CalendarDay> days = bangumiApiClient.getCalendar();
        // 异步缓存日历中的动漫
        if (!days.isEmpty()) {
            for (CalendarDay day : days) {
                if (day.getItems() != null) {
                    for (CalendarItem item : day.getItems()) {
                        try {
                            cacheCalendarItem(item);
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return days;
    }

    // ==================== 详情 / 剧集 ====================

    @Transactional
    public Anime getAnimeDetail(Integer subjectId) {
        Optional<Anime> cached = animeRepository.findById(subjectId);
        if (cached.isPresent()) {
            return cached.get();
        }
        // 本地没有，调 API
        SubjectDTO dto = bangumiApiClient.getSubjectDetail(subjectId);
        if (dto != null) {
            Anime anime = toAnimeEntity(dto);
            anime.setCacheUpdatedAt(LocalDateTime.now());
            return animeRepository.save(anime);
        }
        return null;
    }

    @Transactional
    public List<Episode> getEpisodes(Integer subjectId) {
        Optional<Anime> animeOpt = animeRepository.findById(subjectId);
        if (animeOpt.isEmpty()) {
            return Collections.emptyList();
        }
        Anime anime = animeOpt.get();

        List<Episode> cached = episodeRepository.findByAnimeOrderByEpisodeNumAsc(anime);
        if (!cached.isEmpty()) {
            return cached;
        }

        List<EpisodeDTO> dtos = bangumiApiClient.getEpisodes(subjectId);
        if (!dtos.isEmpty()) {
            List<Episode> episodes = dtos.stream()
                    .map(d -> toEpisodeEntity(d, anime))
                    .collect(Collectors.toList());
            episodeRepository.saveAll(episodes);
            return episodes;
        }
        return Collections.emptyList();
    }

    // ==================== 筛选 / 标签 ====================

    public Map<String, Object> getFilterMeta() {
        Map<String, Object> meta = new HashMap<>();
        List<String> years = animeRepository.findByOrderByDateDesc().stream()
                .map(Anime::getDate)
                .filter(Objects::nonNull)
                .map(d -> d.length() >= 4 ? d.substring(0, 4) : d)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
        meta.put("years", years);
        meta.put("statuses", List.of(
                Map.of("value", "finished", "label", "已完结"),
                Map.of("value", "airing", "label", "放送中")
        ));
        return meta;
    }

    public List<Anime> getFiltered(String year, String season, String status, String tag, String sort) {
        // tag过滤走关联表
        List<Anime> baseList;
        if (tag != null && !tag.isEmpty()) {
            Set<String> tagSet = new HashSet<>();
            tagSet.add(tag);
            // 同时尝试中→英翻译后的英文名
            TagTranslationUtil.reverseTranslateAll(tag).forEach(tagSet::add);
            baseList = getByTags(tagSet);
        } else {
            baseList = animeRepository.findByOrderByRankAsc();
        }

        return baseList.stream()
                .filter(a -> year == null || year.isEmpty()
                        || (a.getDate() != null && a.getDate().startsWith(year)))
                .filter(a -> season == null || season.isEmpty()
                        || season.equals(a.getSeason()))
                .filter(a -> status == null || status.isEmpty()
                        || status.equals(a.getStatus()))
                .sorted((a, b) -> "date".equals(sort)
                        ? Comparator.<String>nullsLast(Comparator.naturalOrder())
                        .compare(b.getDate(), a.getDate())
                        : Integer.compare(
                        a.getRank() != null ? a.getRank() : 9999,
                        b.getRank() != null ? b.getRank() : 9999))
                .collect(Collectors.toList());
    }

    @Cacheable(value = "tags", key = "'all'")
    public List<Map<String, Object>> getAllTags() {
        // 新表有数据 → 走快速JOIN
        if (animeTagRepository.hasAny()) {
            List<Object[]> raw = tagRepository.findAllWithCount();
            return raw.stream()
                    .filter(row -> !((String) row[0]).matches("\\d{4}"))
                    .limit(30)
                    .map(row -> Map.of("name", row[0], "count", row[1]))
                    .collect(Collectors.toList());
        }
        // 新表空(迁移未完成) → 回退旧方法兜底
        List<Anime> all = animeRepository.findAll();
        Map<String, Integer> tagCount = new LinkedHashMap<>();
        for (Anime a : all) {
            if (a.getTags() != null) {
                for (String t : a.getTags().split(",")) {
                    String name = t.trim();
                    if (!name.isEmpty() && !name.matches("\\d{4}")) {
                        tagCount.merge(name, 1, Integer::sum);
                    }
                }
            }
        }
        return tagCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(30)
                .map(e -> Map.of("name", (Object) e.getKey(), "count", (Object) e.getValue()))
                .collect(Collectors.toList());
    }

    /** 使用 anime_tag JOIN 查询, 走索引. 新表空则回退旧方法 */
    public List<Anime> getByTags(Set<String> tagNames) {
        if (tagNames.isEmpty()) return Collections.emptyList();

        // 新表有数据 → 走快速JOIN
        if (animeTagRepository.hasAny()) {
            List<Tag> tags = tagRepository.findAll().stream()
                    .filter(t -> tagNames.contains(t.getName()))
                    .collect(Collectors.toList());
            if (tags.isEmpty()) return Collections.emptyList();

            Set<Long> tagIds = tags.stream().map(Tag::getId).collect(Collectors.toSet());
            List<Integer> animeIds = animeTagRepository.findAll().stream()
                    .filter(at -> tagIds.contains(at.getTag().getId()))
                    .map(AnimeTag::getAnimeId)
                    .distinct()
                    .collect(Collectors.toList());

            if (animeIds.isEmpty()) return Collections.emptyList();
            return animeRepository.findAllById(animeIds).stream()
                    .sorted((a, b) -> {
                        String da = a.getDate(); String db = b.getDate();
                        if (da == null && db == null) return 0;
                        if (da == null) return 1;
                        if (db == null) return -1;
                        return db.compareTo(da);
                    })
                    .collect(Collectors.toList());
        }

        // 新表空(迁移未完成) → 回退旧方法
        return animeRepository.findAll().stream()
                .filter(a -> a.getTags() != null
                        && Arrays.stream(a.getTags().split(","))
                                 .map(String::trim)
                                 .anyMatch(tagNames::contains))
                .sorted((a, b) -> {
                    String da = a.getDate(); String db = b.getDate();
                    if (da == null && db == null) return 0;
                    if (da == null) return 1;
                    if (db == null) return -1;
                    return db.compareTo(da);
                })
                .collect(Collectors.toList());
    }

    // ==================== 内部方法 ====================

    /** 保存番剧实体并同步标签到 anime_tag 关联表 */
    @Transactional
    public void saveAnimeWithTags(Anime anime) {
        anime.setCacheUpdatedAt(LocalDateTime.now());
        animeRepository.save(anime);

        // 清除旧标签关联
        animeTagRepository.deleteByAnimeId(anime.getId());

        // 写入新标签
        if (anime.getTags() != null && !anime.getTags().isBlank()) {
            for (String t : anime.getTags().split(",")) {
                String name = t.trim();
                if (name.isEmpty() || name.matches("\\d{4}")) continue;
                Tag tag = tagRepository.findByName(name)
                        .orElseGet(() -> tagRepository.save(Tag.builder().name(name).build()));
                animeTagRepository.save(AnimeTag.builder().animeId(anime.getId()).tag(tag).build());
            }
        }
    }

    private void cacheAll(List<SubjectDTO> dtos) {
        for (SubjectDTO dto : dtos) {
            if (dto.getId() == null) continue;
            Anime entity = toAnimeEntity(dto);
            saveAnimeWithTags(entity);
        }
    }

    private void cacheCalendarItem(CalendarItem item) {
        if (!animeRepository.existsById(item.getId())) {
            Anime a = Anime.builder().id(item.getId()).build();
            a.setTitle(item.getName());
            a.setTitleCn(item.getNameCn());
            if (item.getImages() != null) {
                a.setCoverUrl(item.getImages().getLarge() != null
                        ? item.getImages().getLarge()
                        : item.getImages().getCommon());
            }
            if (item.getRating() != null) {
                a.setRating(item.getRating().getScore());
                a.setRatingCount(item.getRating().getTotal());
            }
            a.setCacheUpdatedAt(LocalDateTime.now());
            animeRepository.save(a);
        }
    }

    private Anime toAnimeEntity(SubjectDTO dto) {
        Anime a = animeRepository.findById(dto.getId())
                .orElse(Anime.builder().id(dto.getId()).build());

        a.setTitle(dto.getName());
        a.setTitleCn(dto.getNameCn());
        a.setSummary(dto.getSummary());
        if (dto.getImages() != null) {
            a.setCoverUrl(dto.getImages().getLarge() != null
                    ? dto.getImages().getLarge()
                    : dto.getImages().getCommon());
        }
        a.setDate(dto.getDate());
        a.setPlatform(dto.getPlatform());
        a.setTotalEpisodes(dto.getTotalEpisodes());
        if (dto.getRating() != null) {
            a.setRating(dto.getRating().getScore());
            a.setRatingCount(dto.getRating().getTotal());
        }
        if (dto.getTags() != null) {
            a.setTags(dto.getTags().stream()
                    .map(TagDTO::getName)
                    .collect(Collectors.joining(",")));
        }
        return a;
    }

    private Episode toEpisodeEntity(EpisodeDTO dto, Anime anime) {
        Episode ep = Episode.builder()
                .id(dto.getId())
                .anime(anime)
                .episodeNum(dto.getEp() != null ? dto.getEp().intValue() : 0)
                .title(dto.getName())
                .airdate(dto.getAirdate())
                .duration(dto.getDuration())
                .cacheUpdatedAt(LocalDateTime.now())
                .build();
        if (dto.getNameCn() != null && !dto.getNameCn().isEmpty()) {
            ep.setTitle(dto.getNameCn());
        }
        return ep;
    }

    private Map<String, Object> buildSearchResult(List<Anime> list, int page, int limit) {
        int total = list.size();
        int start = (page - 1) * limit;
        int end = Math.min(start + limit, total);
        List<Anime> pageList = start < total ? list.subList(start, end) : Collections.emptyList();

        Map<String, Object> data = new HashMap<>();
        data.put("list", pageList);
        data.put("total", total);
        data.put("page", page);
        return data;
    }
}
