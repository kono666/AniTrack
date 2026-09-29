# ============================================================
#  后端镜像 —— 两阶段构建
#
#  第一阶段用 Maven 镜像编译打包, 第二阶段只把 jar 搬进 JRE 镜像.
#  于是最终镜像里没有源码、没有 Maven、也没有 JDK: 体积小了一半以上,
#  而且镜像里不再有「源码」这第二份东西 —— 它只该是一种形态的东西.
#
#  构建 (在 anime-tracker/backend 目录下执行, 上下文就是这里):
#      docker build -t anitrack-backend .
#  想连测试一起跑 (CI 里值得, 本地每次构建都跑没必要):
#      docker build --build-arg SKIP_TESTS=false -t anitrack-backend .
#
#  运行需要的外部条件 (数据库地址、JWT 密钥、初始管理员):
#  都通过环境变量传入, 一份可参考的清单在 docker-compose.yml 里.
# ============================================================

# ---------- 阶段 1: 编译 ----------
FROM maven:3-eclipse-temurin-26 AS build

WORKDIR /build

# 先只拷 pom.xml 并单独把依赖拉到本地仓库.
# 这样这一层能被 Docker 缓存: 只要 pom.xml 没动, 改 Java 代码重建时就不用重新下载依赖
# (这一步在慢网络下要好几分钟, 而它跟代码改动其实毫无关系).
COPY pom.xml .
# 末尾的 || true 是刻意的: 这一步纯粹是「预取缓存」的优化, 不是构建的必要条件.
# dependency:go-offline 偶尔会因为它自己也管不着的插件/仓库问题失败 ——
# 那种情况下让下面的 package 正常去下就行, 不该因优化失败而让整个构建失败.
RUN mvn -B -q dependency:go-offline || echo "依赖预取未完成, 后续 package 阶段会重新下载"

# 源码放在依赖层之后: 保证「改代码」只让这一层之后的缓存失效.
COPY src ./src

# 镜像构建默认不跑测试. 理由: 测试要跑一遍完整的 Spring 上下文, 镜像构建又是 CI 里
# 每次提交都要做的事 —— 那件事交给 CI 单独跑一次更合适, 而不是塞进 build 里.
ARG SKIP_TESTS=true
RUN mvn -B -DskipTests=${SKIP_TESTS} package

# ---------- 阶段 2: 运行 ----------
# 选 alpine 基底有两个原因: 体积小; 自带 busybox 的 wget ——
# 下面 HEALTHCHECK 要用它, 也就不用为了探活再装一个 curl (少一层、少一个要跟的包).
FROM eclipse-temurin:25-jre-alpine

# 时区.
# 容器默认走 UTC, 而这个项目的日志时间戳、AI 助手的「每日调用额度」重置
# 都按本地日期算: 在 UTC 下额度会在北京时间早上 8 点重置, 日志也要减 8 小时看.
# alpine 不自带时区数据, 装一下才能让 TZ 生效.
RUN apk add --no-cache tzdata
ENV TZ=Asia/Shanghai

# 镜像默认走 postgres profile.
# 写进镜像, 而不是留给部署时手动指定, 是为了消灭「忘了设 profile」这种失误 ——
# 它不会报错, 只会安静地用错配置 (比如连到 H2 或走开发兜底密钥).
# 需要在容器里改用别的 profile 时, -e SPRING_PROFILES_ACTIVE=dev 覆盖即可.
ENV SPRING_PROFILES_ACTIVE=postgres

# JVM 参数:
#   MaxRAMPercentage 让 JVM 按容器给的内存上限算堆大小. 不设的话 JVM 读到的是
#     宿主机的总内存 (老版本 JDK 尤其如此), 结果在容器里被 OOM 杀掉.
#   ExitOnOutOfMemoryError: 内存撑不住时干脆退出让编排系统重启它,
#     好过带着半死不活的状态继续对外服务.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

# 用非 root 用户跑应用.
# 跑一个 Java 服务根本不需要 root, 而容器里的 root 一旦被利用, 逃到宿主机的成本低得多.
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
# 版本号写死在 COPY 里会在每次改版本时失效, 所以用通配符取 target 下那个 fat jar
# (spring-boot-maven-plugin 生成的 .jar.original 不匹配 *.jar).
COPY --from=build /build/target/*.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

# 探活走 /actuator/health/liveness: 它免登录 (探针手里不可能有 JWT), 而且只问
# 「进程还在不在」—— 不碰数据库.
#
# 为什么不直接用 /actuator/health: 那份带 db 组件, 数据库不可用时实测要等 Hikari 的
# 连接超时 (30 秒) 才回 503, 会先撞上探针超时. 而且数据库挂掉时重启后端容器
# 解决不了任何问题, 只会被 restart 策略变成一轮一轮的重启循环.
# 「依赖不可用」该由 /actuator/health/readiness 表达 (负载均衡 / 编排系统看它).
#
# start-period 给足 60 秒: 首次启动要跑 Flyway 迁移, 慢的时候十几秒;
# 这期间探针失败不算数, 免得刚起来就被判成 unhealthy.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null http://localhost:8080/actuator/health/liveness

# exec form 里手写 sh -c + exec: 让 java 成为 PID 1, 于是 docker stop 发的 SIGTERM
# 会直接交给 JVM, Spring 能走完优雅关闭 (停止接收新请求、关连接池), 而不是被硬杀.
# $JAVA_OPTS 保持可覆盖, 部署时想调 JVM 参数不用重新构建镜像.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
