# ============================================================
#  备份容器 —— 跑 anime-tracker/scripts/backup/backup.sh
#
#  底镜像用的是数据库自己那个: pg_dump 的大版本必须与服务器一致
#  (16 对 16), 用同一个镜像就等于是同一个来源, 不用再对一次版本.
#  引一个第三方备份镜像的代价是多一条供应链、多一份要跟的版本,
#  而这里需要的东西只有 pg_dump 和一个 sh.
# ============================================================
FROM postgres:16-alpine

# 单独建一层拷贝 + chmod: 从 Windows 上构建时文件的可执行位不一定带得过来
# (git 只记一个 bit, 而这个仓库是在 Windows 上开发的), 所以权限在这里显式给.
COPY backup.sh /usr/local/bin/backup.sh
RUN chmod +x /usr/local/bin/backup.sh

# 直接把脚本当入口: 不经过 postgres 镜像那个 docker-entrypoint.sh ——
# 它做的事(初始化数据目录、建库建角色)在备份容器里一件都不需要,
# 而它对于"不是 postgres 命令"的参数是直接 exec, 绕开它可以少一层要理解的逻辑.
ENTRYPOINT ["/usr/local/bin/backup.sh"]
