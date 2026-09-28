package com.animetracker.service;

import com.animetracker.entity.Anime;
import com.animetracker.entity.AnimeTag;
import com.animetracker.entity.Tag;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.AnimeTagRepository;
import com.animetracker.repository.TagRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 应用启动前将 anime.tags (逗号分隔) 迁移到 tag + anime_tag 关联表.
 * Order(1) 确保在应用接受请求前完成. 幂等.
 */
@Component
@Order(1)
public class TagMigrationService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TagMigrationService.class);

    private final AnimeRepository animeRepo;
    private final TagRepository tagRepo;
    private final AnimeTagRepository animeTagRepo;

    public TagMigrationService(AnimeRepository animeRepo, TagRepository tagRepo, AnimeTagRepository animeTagRepo) {
        this.animeRepo = animeRepo;
        this.tagRepo = tagRepo;
        this.animeTagRepo = animeTagRepo;
    }

    @Override
    public void run(String... args) {
        if (animeTagRepo.existsByAnimeIdNotNull()) {
            log.info("[TagMigration] 已完成过, 跳过");
            return;
        }

        List<Anime> all = animeRepo.findAll();
        if (all.isEmpty()) {
            log.info("[TagMigration] anime表为空, 跳过");
            return;
        }

        log.info("[TagMigration] 开始迁移 {} 条...", all.size());
        long start = System.currentTimeMillis();

        // Phase 1: 收集所有唯一标签名, 批量写入 tag 表
        Set<String> allTagNames = new LinkedHashSet<>();
        Map<Integer, Set<String>> animeTags = new LinkedHashMap<>();

        for (Anime a : all) {
            if (a.getTags() == null || a.getTags().isBlank()) continue;
            Set<String> names = new LinkedHashSet<>();
            for (String t : a.getTags().split(",")) {
                String name = t.trim();
                if (!name.isEmpty() && !name.matches("\\d{4}")) {
                    names.add(name);
                    allTagNames.add(name);
                }
            }
            if (!names.isEmpty()) {
                animeTags.put(a.getId(), names);
            }
        }

        // 批量创建标签
        Map<String, Tag> tagCache = new HashMap<>();
        List<Tag> newTags = new ArrayList<>();
        for (String name : allTagNames) {
            Tag existing = tagRepo.findByName(name).orElse(null);
            if (existing != null) {
                tagCache.put(name, existing);
            } else {
                Tag t = Tag.builder().name(name).build();
                newTags.add(t);
                tagCache.put(name, t);
            }
        }
        tagRepo.saveAll(newTags);
        tagRepo.flush();
        log.info("[TagMigration] Phase1: {} 个标签 (新增 {})", allTagNames.size(), newTags.size());

        // Phase 2: 批量写入 anime_tag 关联
        List<AnimeTag> batch = new ArrayList<>();
        int count = 0;
        for (Map.Entry<Integer, Set<String>> entry : animeTags.entrySet()) {
            Integer animeId = entry.getKey();
            for (String name : entry.getValue()) {
                Tag tag = tagCache.get(name);
                if (tag != null && tag.getId() != null) {
                    batch.add(AnimeTag.builder().animeId(animeId).tag(tag).build());
                }
            }
            // 每1000条刷一次
            if (batch.size() >= 1000) {
                animeTagRepo.saveAll(batch);
                count += batch.size();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            animeTagRepo.saveAll(batch);
            count += batch.size();
        }

        long elapsed = (System.currentTimeMillis() - start) / 1000;
        log.info("[TagMigration] 完成: {} 条番剧, {} 条关联, {} 个标签 ({}s)",
                animeTags.size(), count, allTagNames.size(), elapsed);
    }
}
