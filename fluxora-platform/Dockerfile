# Fluxora Platform — 控制台后端（Spring Boot，多阶段构建）
#
# 依赖缓存策略：
# Layer 1: 仅复制 pom.xml，下载所有依赖到 ~/.m2（缓存层）
# Layer 2: 复制源码并编译打包（源码变更不触发重新下载）
# Layer 3: JRE 运行时镜像，仅包含可执行 JAR
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# ---- 依赖缓存层：仅复制 POM ----
COPY pom.xml .
COPY fluxora-common/pom.xml fluxora-common/
COPY fluxora-platform/pom.xml fluxora-platform/
COPY fluxora-gateway/pom.xml fluxora-gateway/
# 通过阿里源下载当前模块及其传递依赖到本地仓库；pom.xml 不变则不重建
RUN mvn -pl fluxora-platform -am dependency:go-offline -DskipTests -q

# ---- 源码层：替换 POM 占位为完整源码并打包 ----
COPY fluxora-common fluxora-common
COPY fluxora-platform fluxora-platform
RUN mvn -pl fluxora-platform -am package -DskipTests

# ---- 运行时层：精简 JRE，不含 Maven ----
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/fluxora-platform/target/fluxora-platform-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
# 使用 shell 形式使 $JAVA_OPTS 展开；exec 确保 java 成为 PID 1 正确接收 Docker 信号
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
