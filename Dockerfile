# =============================================================================
# saas-identity-platform-kotlin — 生产镜像
#
#   builder  → eclipse-temurin:21-jdk-jammy + gradle wrapper 9.8.1 installDist
#              （application 插件现成产出 build/install/<slug>/{bin,lib}，
#               零 build.gradle 改动——不加 shadow，门禁面零影响）
#   runtime  → eclipse-temurin:21-jre-jammy（非 slim：仓库无 -jre-slim tag，
#               springboot v0.1.7 修正先例）+ bin 启动脚本 + lib 全依赖
#
# 数据库：PostgreSQL（远程）。容器内不持有 DB 文件 —— 运行期必须通过
#         DATABASE_URL 环境变量注入连接串（由 VPS kotlin.env 注入）。
#
# 端口：容器内 Netty 监听 SERVER_PORT=5109（host=container 单层映射，
#       ADR-0018；X09 槽）。SERVER_PORT 不在镜像内 ENV 兜底——fail-fast
#       键由 env-file 保证（suite 硬规则 §1：禁 env 默认值兜底）。
#
# 镜像族系（与 springboot 同侧）：JVM 两段式，jammy 非 alpine
#（避免 musl libc + native deps surprise，springboot Dockerfile 注释先例）。
# =============================================================================

# ---------- Stage 1: builder ----------
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

# 缓存友好的层：先只复制 gradle 元文件跑依赖解析，再 copy src。
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts openapitools.json ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --parallel help > /dev/null 2>&1 || true

COPY src ./src
# 注意：settings.gradle.kts 是 rootProject.name = rootDir.name —— 容器里 rootDir=WORKDIR
# （/app），项目名跟随 WORKDIR 基名 = "app"（2026-10-25 首航 CI 指纹：cp 仓名路径必落空）。
# install 目录名 = bin 启动脚本名 = "app"，与 WORKDIR 改名联动。
RUN ./gradlew --no-daemon installDist \
 && cp -r build/install/app /app/dist

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# jammy 自带 wget？不——temurin jre-jammy 缺 wget，HEALTHCHECK 探 /health 需要
RUN apt-get update \
 && apt-get install -y --no-install-recommends wget ca-certificates \
 && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/dist/ /app/

# JVM 在容器内堆上限参考 cgroup 内存限额（默认 75%）；bin 脚本透传 JAVA_OPTS
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
ENV TZ=UTC

EXPOSE 5109

# Netty 装配 + Exposed 连库冷启动 @ 小 VPS。probe 走 /health（busybox wget
# 解析 127.0.0.1 字面量，不经 ::1——IPv6 localhost 陷阱防在案）。
# 未就绪时 wget 非 0 → restart-loop，fail-loud 是想要的。
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:5109/health || exit 1

ENTRYPOINT ["/app/dist/bin/app"]
