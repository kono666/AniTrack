# ============================================================
#  前端镜像 —— 构建 dist 并用 nginx 托管, 顺便做反向代理
#
#  为什么是「镜像内构建」而不是「把本机的 dist 挂进容器」:
#  挂载的写法有一个不会报错的失败模式 —— 忘了 npm run build, 或者 dist 被
#  清过一半, nginx 照样启动成功, 只是首页白屏或 404. 而这次审计发现的正是
#  这个: 仓库里那份 dist 只剩一个 565B 的 index.html, assets 全丢.
#  把构建放进镜像之后, 「能起来」就等于「dist 是刚构建出来的」.
#
#  构建 (在 anime-tracker/frontend 目录下执行, 上下文就是这里):
#      docker build -t anitrack-frontend .
#
#  最终镜像里没有 node_modules、没有源码、没有 npm —— 只有构建产物和 nginx.
# ============================================================

# ---------- 阶段 1: 构建 dist ----------
FROM node:26-alpine AS build

WORKDIR /build

# 先只拷这两个文件并单独装依赖, 让这一层能被缓存:
# 只要 package.json / package-lock.json 没动, 改前端代码重建时就不用重新装依赖.
COPY package.json package-lock.json ./

# npm ci 而不是 npm install: 它严格按 package-lock.json 装, 锁文件与 package.json
# 对不上时直接失败. install 会顺手改锁文件 —— 那等于让构建过程能产出新的依赖树,
# 于是「我这能构建」和「CI 能构建」可以是两件不同的事.
RUN npm ci

COPY . .

# vite build. 产物在 /build/dist (见 vite.config.js: 没有自定义 outDir)
RUN npm run build

# ---------- 阶段 2: 托管 ----------
# nginx:alpine 自带 busybox 的 wget, 下面 HEALTHCHECK 要用它, 不用额外装 curl.
FROM nginx:alpine

# 时区: 与后端容器保持一致. 这里影响的只有访问日志的时间戳,
# 但两份日志对着看时差 8 小时, 排查问题时会先怀疑自己看错了文件.
RUN apk add --no-cache tzdata
ENV TZ=Asia/Shanghai

# 换成我们自己的 server 配置:
#   - SPA 的 try_files 回退 (没有它, 直接访问 /profile 是 404)
#   - /api 反代到 backend:8080, 并**覆盖** X-Forwarded-For (见文件内注释)
#   - /actuator/health 反代出去给外部 uptime 监控用
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 构建产物.
# 注意 nginx.conf 里 root 指向的就是这个目录, 两处必须一致 ——
# 写错了不会报错, 只会得到一个 403/404 的站.
COPY --from=build /build/dist /usr/share/nginx/html

# 官方镜像默认让 master 以 root 起、worker 以 nginx 用户跑.
# 这里不动这个结构: master 需要 root 才能绑 80 端口和写 /var/cache/nginx.
# 真正处理请求的 worker 本来就不是 root, 而它才是对外的那个进程.
EXPOSE 80

# 探活打自己的 /healthz (nginx.conf 里 return 200), 不走后端:
# 这个探针要回答的是「nginx 还在不在」. 把它指到后端的话, 后端一抖动
# 就会让 nginx 被判成 unhealthy 然后被重启 —— 而重启 nginx 解决不了后端的问题.
# 后端自己的健康由它自己的探针表达 (docker-compose.yml 里的 depends_on).
#
# 地址写 127.0.0.1 而不是 localhost, 这是踩过一次的:
# 这个镜像里 /etc/hosts 有 "::1 localhost", 而 musl 的解析顺序把 IPv6 排在前面,
# 于是 wget localhost 会先去连 [::1]:80. 本镜像只 listen 80 (IPv4),
# 那个连接被拒 —— 结果是 nginx 明明起得好好的, 却被判成 unhealthy,
# 整个 compose up --wait 直接失败 (CI #22 就是这么红的).
# 写死 127.0.0.1 让这个探针不依赖解析顺序.
# (后端的探针用 localhost 没问题: Tomcat 是双栈监听的, ::1 也连得上.)
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/healthz

# 官方镜像自带的 ENTRYPOINT/CMD 保持不动 (它已经处理了信号转发:
# docker stop 会让 nginx 走完 graceful shutdown, 而不是掐断正在传的响应)
