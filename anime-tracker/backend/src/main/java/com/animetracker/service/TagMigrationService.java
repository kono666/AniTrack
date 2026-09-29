package com.animetracker.service;

import com.animetracker.entity.Anime;
import com.animetracker.entity.AnimeTag;
import com.animetracker.entity.Tag;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.AnimeTagRepository;
import com.animetracker.repository.TagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 应用启动前将 anime.tags (逗号分隔) 迁移到 tag + anime_tag 关联表.
 * Order(1) 确保在应用接受请求前完成. 幂等.
 *
 * <p><b>整个迁移是一个事务, 这一点是必须的</b>(审计 M10). 它的幂等是靠开头那句
 * "关联表里已经有行就跳过" 实现的 —— 而这句话只有在"要么全做完、要么一行没做"的
 * 前提下才成立. 不加事务时, 中途失败(约束冲突、进程被杀、OOM)会留下一批标签和
 * 一部分关联, 下次启动看到"已经有行"直接跳过, **残缺就这样永久留在了库里**,
 * 而且没有任何报错: 页面上少了一些标签, 谁也不会发现.
 *
 * <p>代价写清楚: 数据量大时这是一个长事务, 期间 tag/anime_tag 两张表按迁移前的
 * 状态对外(此服务在启动阶段跑, 还没有请求进来, 所以这段窗口实际是空的);
 * 内存里也不再按批提交 —— 每 1000 条那几处 flush 只影响发送时机, 不再影响提交.
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

    /**
     * 加在 run 上而不是类上: 这个类只做这一件事, 但"整个类都是事务"读起来
     * 会让人以为将来加个查询方法也会被套进事务里. 写在方法上, 边界在哪一目了然.
     *
     * <p>Spring 能给它开事务的前提是: 方法必须是 public, 且调用来自**代理**
     * (CommandLineRunner 由 Spring 从容器里取, 走的就是代理). 把这段逻辑挪进
     * 一个 private 方法、然后 this.xxx() 自调用, 事务会**静默**消失 ——
     * AnnotationTransactionAttributeSource 那道判定在下面有一个测试钉着.
     */
    @Override
    @Transactional
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
