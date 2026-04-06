# ============================================================
# enterprise-claw-4j — 多阶段构建 Dockerfile
# ============================================================
# 构建阶段: JDK 21 (Alpine)
# 运行阶段: JRE 21 (Alpine) + 非 root 用户
# ============================================================

# ---- 构建阶段 ----
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# 先复制 pom.xml，利用 Docker 层缓存加速依赖下载
COPY pom.xml .
# 下载所有依赖 (后续代码变更不会触发此层重建)
RUN mvn dependency:go-offline -B

# 复制源代码并构建
COPY src ./src
RUN mvn clean package -DskipTests -B \
    && mv target/enterprise-claw-4j-*.jar target/app.jar

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="OpenClaw Team"
LABEL description="Enterprise-grade Java Agent Framework powered by Anthropic Claude"

# 创建非 root 用户
RUN addgroup -S claw4j && adduser -S claw4j -G claw4j

WORKDIR /app

# 从构建阶段复制 JAR
COPY --from=builder /build/target/app.jar app.jar

# 创建工作空间目录并设置权限
RUN mkdir -p /app/workspace && chown -R claw4j:claw4j /app

USER claw4j

# JVM 参数: 容器感知 + 内存限制 + 优雅关闭
ENV JAVA_OPTS="\
-XX:+UseContainerSupport \
-XX:MaxRAMPercentage=75.0 \
-Dserver.shutdown=graceful \
-Dspring.lifecycle.timeout-per-shutdown-phase=30s"

EXPOSE 8080

# 健康检查: 每 30 秒检查 /actuator/health
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]
