# Fluxora Gateway — AI 中继网关（Vert.x，多阶段构建）
#
# 依赖缓存策略：
# Layer 1: 仅复制 pom.xml，下载所有依赖到 ~/.m2（缓存层）
# Layer 2: 复制源码、编译、提取运行时依赖
# Layer 3: JRE 运行时镜像，通过 java -cp 直接启动
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# ---- 依赖缓存层：仅复制 POM ----
COPY pom.xml .
COPY fluxora-common/pom.xml fluxora-common/
COPY fluxora-gateway/pom.xml fluxora-gateway/
COPY fluxora-platform/pom.xml fluxora-platform/
RUN mvn -pl fluxora-gateway -am dependency:go-offline -DskipTests -q

# ---- 源码层：编译 + 安装 + 提取运行时依赖 ----
# install 确保 fluxora-common 进入本地仓库，后续 copy-dependencies 可解析 reactor 内依赖
COPY fluxora-common fluxora-common
COPY fluxora-gateway fluxora-gateway
RUN mvn -pl fluxora-gateway -am install -DskipTests -q
RUN mvn -pl fluxora-gateway dependency:copy-dependencies \
    -DoutputDirectory=/workspace/lib -DincludeScope=runtime -q

# ---- 运行时层：精简 JRE，不含 Maven ----
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/fluxora-gateway/target/classes /app/classes
COPY --from=build /workspace/fluxora-common/target/classes /app/common-classes
COPY --from=build /workspace/lib /app/lib
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp /app/classes:/app/common-classes:/app/lib/* io.fluxora.gateway.FluxoraGatewayApplication"]
