# 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先拷贝 pom 单独拉依赖，能吃掉 Docker 层缓存——改代码不会重下依赖
COPY pom.xml .
RUN mvn -q -B dependency:go-offline -DskipTests

COPY src ./src
RUN mvn -q -B -DskipTests package

# 运行阶段
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        git \
        curl \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /build/target/*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
