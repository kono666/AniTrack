package com.animetracker.config;

import com.animetracker.service.AnimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时给存量番剧补齐 season / status.
 *
 * <p>为什么是一次性的启动任务, 而不是塞进迁移脚本: 推导逻辑要判日期、算季节、
 * 比较今天, 用 SQL 写就得在 H2 和 PostgreSQL 两套方言里各写一遍(而两边一旦
 * 有细微差别, 开发库和生产库的数据就会不一样, 且没人会察觉). 何况本项目有一条
 * 约定: 迁移脚本只放 DDL, 不放业务数据 —— 补数据不该混在改表结构的脚本里.
 *
 * <p>为什么不是定时任务: 它只在"部署了一批没有这三个字段的老数据"时有意义,
 * 跑一次就够了. 之后新数据由同步路径自己带上(见 AnimeService.upsertAnime),
 * 而 status 随时间变化那部分由每次启动的重算覆盖.
 *
 * <p>失败不阻断启动: 补不齐只是筛选功能少几个结果, 不该让整个服务起不来.
 */
@Component
public class AnimeFieldBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AnimeFieldBackfillRunner.class);

    private final AnimeService animeService;

    public AnimeFieldBackfillRunner(AnimeService animeService) {
        this.animeService = animeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            animeService.backfillDerivedFields();
        } catch (Exception e) {
            log.warn("补齐 season/status 失败, 跳过: {}", e.getMessage());
        }
    }
}
