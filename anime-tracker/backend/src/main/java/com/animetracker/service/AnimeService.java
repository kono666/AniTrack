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

import java.time.LocalDate;
import java.time.LocalDateTime;
import com.animetracker.util.AnimeFields;
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
                            upsertCalendarItem(item);
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
        // 本地没有，调 API.
        // 走 upsertAnime 而不是自己 save: 顺手把标签也写进 anime_tag
        // (以前这条路径只存主表, 于是"点开过的番剧"在标签索引里是缺的).
        SubjectDTO dto = bangumiApiClient.getSubjectDetail(subjectId);
        if (dto != null) {
            return upsertAnime(dto);
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
                .sorted((a, b) -> {
                    if ("date".equals(sort)) {
                        String da = a.getDate();
                        String db = b.getDate();
                        // 缺播出日的排最后, 与下面 rank 的 9999 兜底同一个口径:
                        // 「这个值不知道」不该排到「知道且最大」的前面.
                        //
                        // 这里原本是 nullsLast(...).compare(b, a) —— 内外两次"反过来"
                        // 叠在一起, 净效果成了**缺日期的排最前**, 正好与意图相反.
                        // 首页"最近更新"打开就是一屏没有日期的番.
                        // 也刻意不写成 nullsLast().reversed(): reversed() 会把 null 的
                        // 处理一起翻过去, 同一个坑再踩一遍. 写法与 getByTags 里那份
                        // 保持一致, 免得两处口径再次分叉.
                        if (da == null && db == null) return 0;
                        if (da == null) return 1;
                        if (db == null) return -1;
                        return db.compareTo(da);
                    }
                    return Integer.compare(
                            a.getRank() != null ? a.getRank() : 9999,
                            b.getRank() != null ? b.getRank() : 9999);
                })
                .collect(Collectors.toList());
    }

    /**
     * 分页版的筛选, 返回结构与搜索接口一致: {@code {list, total, page}}.
     *
     * <p>为什么筛选也要分页: 它此前一次返回**全部**匹配行 —— 无参数时就是整张表
     * (线上 470 行, 前端 Search.vue 读的是 {@code res.data.data.list}, 会一次全渲染).
     * 数据再长下去, 一个请求就能让两边各自扛一份任意大的结果集, 与 1.3 里
     * 给 limit 封顶要挡的是同一件事.
     *
     * <p>顺序是**先筛后排再切页**, 这也是这里不能再让控制器自己切的原因:
     * 先切页会把"第几页"切到未筛选的集合上, 于是 total 变成页大小、后面的页
     * 少几条. 交给 {@link #buildSearchResult} 一并处理, 顺便复用它已经修好的
     * 越界夹取与 long 起点(见那里的注释).
     *
     * <p>传给 Agent 工具的仍是 {@link #getFiltered} 那份完整列表 —— 工具那边由
     * {@code max-tool-result-chars} 截断, 不需要分页语义.
     */
    public Map<String, Object> getFilteredPage(String year, String season, String status,
                                               String tag, String sort, int page, int limit) {
        return buildSearchResult(getFiltered(year, season, status, tag, sort), page, limit);
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
            upsertAnime(dto);
        }
    }

    /**
     * 把一个 Bangumi 条目写进本地库: 有就刷新, 没有就新建.
     *
     * <p>这是**唯一**一处 Anime ←→ DTO 的映射. 在这之前它有四份副本
     * ({@code toAnimeEntity}、{@code CachePreloader.toAnime}、
     * {@code DataRefreshService.toAnime} 和 {@code mergeUpdate}), 后果不是"代码重复"
     * 这么抽象 —— season/status/rank 三个字段就是因为要改四处而一处都没改,
     * 470 行数据全是 NULL, 而按这三个字段筛选的接口一直对外开着.
     * 收敛成一处之后, 再加字段只有这一个地方要动.
     *
     * <p>更新走的是"先 findById 拿到受管实体再改", 不是"造一个带 id 的游离实体
     * 丢给 save": 后者在 id 非空时走 merge, 会把实体上**没有赋值**的字段
     * 一并写成 null(merge 拷贝全部映射字段, 包括 null), 于是一次按关键词的搜索
     * 同步就能把之前辛苦填上的 season/status 抹掉. 这类问题不会报错, 只会
     * 让字段过一阵子又变回 NULL.
     */
    @Transactional
    public Anime upsertAnime(SubjectDTO dto) {
        Anime a = animeRepository.findById(dto.getId())
                .orElse(Anime.builder().id(dto.getId()).build());
        applySubject(a, dto);
        saveAnimeWithTags(a);
        return a;
    }

    /**
     * 把 DTO 上的字段拷到实体上. 每个字段都"有值才覆盖" ——
     * Bangumi 的搜索结果与详情接口返回的字段并不一致(搜索不带部分字段),
     * 无条件覆盖会在每次同步时把之前拿到的值清成 null.
     *
     * <p>末尾三个是算出来的字段, 它们不来自 DTO 的任何直接字段:
     * season 从 date 推, status 从 date + 总集数 + 今天推, rank 从 rating.rank 取
     * (0 = 未上榜, 转成 null). 推导口径见 {@link AnimeFields}.
     */
    private void applySubject(Anime a, SubjectDTO dto) {
        if (dto.getName() != null) a.setTitle(dto.getName());
        if (dto.getNameCn() != null) a.setTitleCn(dto.getNameCn());
        if (dto.getSummary() != null) a.setSummary(dto.getSummary());
        if (dto.getImages() != null) {
            String cover = dto.getImages().getLarge() != null
                    ? dto.getImages().getLarge()
                    : dto.getImages().getCommon();
            if (cover != null) a.setCoverUrl(cover);
        }
        if (dto.getDate() != null) a.setDate(dto.getDate());
        if (dto.getPlatform() != null) a.setPlatform(dto.getPlatform());
        if (dto.getTotalEpisodes() != null) a.setTotalEpisodes(dto.getTotalEpisodes());
        if (dto.getRating() != null) {
            if (dto.getRating().getScore() != null) a.setRating(dto.getRating().getScore());
            if (dto.getRating().getTotal() != null) a.setRatingCount(dto.getRating().getTotal());
            a.setRank(AnimeFields.rankOf(dto.getRating().getRank()));
        }
        if (dto.getTags() != null) {
            a.setTags(dto.getTags().stream()
                    .map(TagDTO::getName)
                    .collect(Collectors.joining(",")));
        }
        applyDerivedFields(a, LocalDate.now());
    }

    /**
     * 重算 season/status. rank 不在这里 —— 它不是算出来的, 只能从 Bangumi 拿.
     *
     * <p>抽出来是为了让"补齐存量数据"和"同步新数据"走同一套口径:
     * 两边各写一份的话, 补出来的值和之后同步进去的值会慢慢对不上.
     */
    private void applyDerivedFields(Anime a, LocalDate today) {
        a.setSeason(AnimeFields.seasonOf(a.getDate()));
        a.setStatus(AnimeFields.statusOf(a.getDate(), a.getTotalEpisodes(), today));
    }

    /**
     * 日历条目落库. 日历是"当前在播"的**权威**清单, 所以它比推导更可信:
     * 长连载(总集数未知)靠 date + 集数估出来会是"早已完结", 而它出现在日历里
     * 就说明还在播 —— 这里把 status 直接定成 airing.
     *
     * <p>同样修掉了另外两个被丢掉的字段: {@code air_date} 和 {@code rank}.
     * 这两个字段以前既没读也没写, 结果是所有从日历来的番剧 date 都是 NULL
     * (实测线上 470 行里有 145 行), 而 date 为空意味着 season 和 status
     * 都推不出来 —— 一个字段没写, 连带两个字段一起废掉.
     *
     * <p>已存在的行也会被刷新(以前是 {@code if (!existsById)} 直接跳过):
     * 跳过的代价是老数据永远停在"什么都没有"的状态, 只能等它碰巧被别的同步路径
     * 再捞一次.
     */
    @Transactional
    public Anime upsertCalendarItem(CalendarItem item) {
        if (item == null || item.getId() == null) {
            return null;
        }
        Anime a = animeRepository.findById(item.getId())
                .orElse(Anime.builder().id(item.getId()).build());

        if (item.getName() != null) a.setTitle(item.getName());
        if (item.getNameCn() != null) a.setTitleCn(item.getNameCn());
        if (item.getImages() != null) {
            String cover = item.getImages().getLarge() != null
                    ? item.getImages().getLarge()
                    : item.getImages().getCommon();
            if (cover != null) a.setCoverUrl(cover);
        }
        if (item.getRating() != null) {
            if (item.getRating().getScore() != null) a.setRating(item.getRating().getScore());
            if (item.getRating().getTotal() != null) a.setRatingCount(item.getRating().getTotal());
        }
        if (item.getAirDate() != null) a.setDate(item.getAirDate());
        // 只在日历真的给了这个字段时才动 rank: 日历条目有时不带 rank,
        // 那种情况是"不知道", 不是"没有排名" —— 无条件写会把上一次从详情接口
        // 拿到的名次抹成 null.
        if (item.getRank() != null) a.setRank(AnimeFields.rankOf(item.getRank()));

        applyDerivedFields(a, LocalDate.now());
        a.setStatus(AnimeFields.STATUS_AIRING);   // 在日历里 = 正在播, 覆盖推导结果

        saveAnimeWithTags(a);
        return a;
    }

    /**
     * 给存量数据补齐 season/status.
     *
     * <p>为什么需要它: 推导只在同步路径上跑, 而库里的老数据不会自己再被同步一次
     * —— 470 行里 325 行有 date, 光靠"下次同步时会填上"是等不到的.
     *
     * <p>**只读本地列, 不联外网**: season 和 status 都能从已有的 date / 总集数
     * 算出来. rank 算不出来(那是 Bangumi 的榜单名次), 只能靠同步时从
     * {@code rating.rank} 带回来 —— 所以这个方法不碰 rank, 不假装能补.
     *
     * <p>每次启动重算全部行, 而不是只补 NULL 的那些: status 会随时间变化
     * ("放送中"过几个月就该变成"已完结"), 只补 NULL 的话这些值会永远停在
     * 第一次算出来的那一刻. 重算是幂等的, 且只在值真的变了的时候才会发 UPDATE
     * (走受管实体的脏检查).
     *
     * @return 实际发生变化的行数
     */
    @Transactional
    public int backfillDerivedFields() {
        List<Anime> all = animeRepository.findAll();
        LocalDate today = LocalDate.now();
        int changed = 0;
        for (Anime a : all) {
            String season = AnimeFields.seasonOf(a.getDate());
            String status = AnimeFields.statusOf(a.getDate(), a.getTotalEpisodes(), today);
            if (Objects.equals(season, a.getSeason()) && Objects.equals(status, a.getStatus())) {
                continue;
            }
            a.setSeason(season);
            a.setStatus(status);
            changed++;
        }
        log.info("补齐推导字段: 扫描 {} 行, 变更 {} 行", all.size(), changed);
        return changed;
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

        // 越界的分页参数在这里夹回合法区间, 目的是不让它走到 subList 去抛越界.
        // 控制器那层已经有 @Min/@Max, 拦的是网页来的请求; 这里管的是绕过控制器的
        // 调用方(Agent 工具、内部直接调用). 对它们来说, 一个越界的分页参数应该
        // 退化成「第一页」, 而不是把整条调用链炸成 500.
        int safePage = Math.max(page, 1);
        int safeLimit = Math.max(limit, 1);
        // 起点用 long 算: page 只封了下界, page=Integer.MAX_VALUE 时
        // (page-1)*limit 会溢出成负数, 于是又绕回 subList(负, 正) 的那个越界.
        long startL = (long) (safePage - 1) * safeLimit;
        int start = startL >= total ? total : (int) startL;
        int end = (int) Math.min(startL + safeLimit, total);
        List<Anime> pageList = start < total ? list.subList(start, end) : Collections.emptyList();

        Map<String, Object> data = new HashMap<>();
        data.put("list", pageList);
        data.put("total", total);
        // 回报夹过之后的值: 调用方拿到的这一页确实来自 safePage 页
        data.put("page", safePage);
        return data;
    }
}
