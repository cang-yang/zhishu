FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /workspace

# 配置国内 Maven 镜像（解决容器内下载超时）
COPY settings.xml /root/.m2/settings.xml

# 先缓存依赖
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# 复制源码并编译
COPY docs ./docs
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=60.0 -XX:InitialRAMPercentage=20.0"

COPY --from=builder /workspace/target/*.jar /app/app.jar

EXPOSE 8081

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]