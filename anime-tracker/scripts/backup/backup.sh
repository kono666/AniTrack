#!/bin/sh
# ============================================================
#  AniTrack · 数据库备份循环
#
#  在一个单独的容器里跑 (见 docker-compose.yml 的 backup 服务):
#  每隔 BACKUP_INTERVAL_SECONDS 秒 pg_dump 一次, 保留 BACKUP_KEEP_DAYS 天.
#
#  为什么是个常驻循环而不是 crontab: 这个容器里只需要跑这一件事, 而 crontab
#  会额外引入「cron 起了没 / 任务真的注册上了没 / 容器的时区对不对」三个
#  看不见的失败点. 一个 while 循环加 sleep 没有这些问题, 而且 sleep 是
#  可中断的, docker stop 会把它叫醒.
#
#  这个脚本的取舍只有一条: **宁可没有备份文件, 也不要留下一个看起来像备份
#  的东西**. 备份文件平时没人打开, 它只在你最需要它的那一刻被打开 ——
#  那也是最不适合发现"这份备份是半截的"的时刻. 所以下面每一步失败都是
#  丢弃而不是保留: 写 .part、验完整、才改名.
# ============================================================

set -eu

# pipefail 是「失败就丢弃」这条规则成立的前提.
#
# 没有它, `pg_dump | gzip` 的退出码取的是 gzip 的 —— 而 pg_dump 中途死掉时
# gzip 照样能成功地压缩它已经收到的部分并返回 0, 于是截断的 dump 会被当成
# 一份好备份. 所以这里不"尽量打开": 打不开就直接退出, 让问题在启动那一刻
# 暴露(容器起不来 / unhealthy), 而不是在几个月后的恢复现场暴露.
set -o pipefail 2>/dev/null || {
    echo "[backup] 这个 sh 不支持 pipefail, 拒绝在不安全的状态下运行" >&2
    exit 1
}

KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
INTERVAL="${BACKUP_INTERVAL_SECONDS:-86400}"
DIR="${BACKUP_DIR:-/backup}"
PREFIX="${BACKUP_PREFIX:-anitrack}"

# 丢掉一份 dump 之后隔多久重试.
# 刻意不做成环境变量: 它是一个恢复路径, 不是一项策略 —— 没有人需要按自己的
# 情况调它, 而多一个可配置项就多一个"填错了也没人发现"的地方.
RETRY_SECONDS=60

log() { echo "[backup] $(date '+%Y-%m-%d %H:%M:%S') $*"; }

mkdir -p "$DIR"

# 上一次运行被 kill 在半路留下的半成品. 启动时清掉:
# .part 永远不会被当成备份(见下), 但它会一直占着磁盘, 而且会让人
# 在 ls 的时候以为"上次备份好像没成功".
find "$DIR" -maxdepth 1 -name "$PREFIX-*.sql.gz.part" -type f -delete 2>/dev/null || true

log "启动: 每 ${INTERVAL}s 备份一次, 保留 ${KEEP_DAYS} 天, 目录 ${DIR}"

while true; do
    stamp=$(date '+%Y%m%d-%H%M%S')
    file="$DIR/$PREFIX-$stamp.sql.gz"
    tmp="$file.part"
    delay="$INTERVAL"

    # --clean --if-exists: 让 dump 可以直接灌回一个已有数据的库(先删后建),
    #   恢复时不用先手动清库. --no-owner: 恢复的目标库里不一定有同名角色,
    #   带上 owner 会让整个恢复因为一个不存在的角色而失败.
    if pg_dump --no-owner --no-privileges --clean --if-exists | gzip -9 > "$tmp"; then
        # 完整性自检, 两条:
        #
        # 一、收尾标记. pg_dump 正常收尾时会写这一行, 只有它跑完才有.
        #     这一步挡的是 pipefail 也挡不住的情况: pg_dump 自己"成功"退出,
        #     但连接中途断过.
        # 二、里面得有建表语句.
        #
        # 第二条是 CI 抓出来的: 备份容器原先只等 postgres 健康, 于是在
        # "PG 能接受连接"的瞬间就 dump 了 —— 而那时后端的 Flyway 还没开始跑,
        # 库里一张表都没有. 这份 4.0K 的 dump 通过了收尾标记检查(它确实是
        # pg_dump 完整产出的), 却被当成了备份, 连 healthcheck 都因此判了 healthy.
        #
        # 空库的 dump 与半截的 dump 是同一种东西: 看起来像备份. 上面那条
        # 取舍对它们一视同仁 —— 丢掉. 只是这一份的成因不是"传输出错", 而是
        # "来得太早", 所以值得再试一次, 而不是等一整天.
        if ! gzip -dc "$tmp" | tail -n 5 | grep -q 'PostgreSQL database dump complete'; then
            rm -f "$tmp"
            log "失败: dump 没有收尾标记(不完整), 已丢弃"
        elif ! gzip -dc "$tmp" | grep -q '^CREATE TABLE'; then
            rm -f "$tmp"
            delay="$RETRY_SECONDS"
            log "跳过: 库里一张表都没有(应用还没跑完迁移, 或者备份跑在了迁移前面)."
            log "      空 schema 的 dump 恢复不出任何东西, 已丢弃; ${RETRY_SECONDS}s 后重试"
        else
            mv "$tmp" "$file"
            log "完成 $(du -h "$file" | cut -f1)  $(basename "$file")"
        fi
    else
        rm -f "$tmp"
        log "失败: pg_dump 或 gzip 返回非零, 已丢弃半截文件"
    fi

    # 保留策略. 用文件的 mtime 而不是文件名里的时间戳:
    # 文件名是给人看的, 拿它做排序和解析是两件容易出错的事.
    # (+N 表示"早于 N 个 24 小时", 所以第 15 天的备份会在它满 14 天后的那次
    #  循环里被删掉 —— 保留期是"至少 14 天", 不是"正好 14 天".)
    find "$DIR" -maxdepth 1 -name "$PREFIX-*.sql.gz" -type f -mtime "+$KEEP_DAYS" -delete 2>/dev/null || true

    # delay 默认是 INTERVAL, 只有"空库"那条路径会把它改成 RETRY_SECONDS
    sleep "$delay"
done
