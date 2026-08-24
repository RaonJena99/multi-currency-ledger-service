# Build Stage
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

# Copy gradle wrapper and related files for dependency resolution caching
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

RUN chmod +x gradlew

# Warm the dependency cache. This layer is reused unless build.gradle changes.
RUN ./gradlew dependencies --no-daemon || true

COPY src src

# Tests run in CI (they need Docker for Testcontainers), so skip them here.
RUN ./gradlew bootJar --no-daemon -x test

# Runtime Stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 원장은 UTC 기준으로 월차 귀속과 대사 시간창을 계산한다.
# 애플리케이션에서 TimeZone.setDefault 를 호출하더라도 일부 빈은 그보다 먼저 생성되므로
# 컨테이너 수준에서 확정해 두는 편이 안전하다.
ENV TZ=UTC

# HEALTHCHECK 에서 사용한다. jre 이미지에는 curl 이 포함되어 있지 않다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r spring && useradd -r -g spring spring

# jar 소유권을 실행 사용자에게 준다.
COPY --from=builder --chown=spring:spring /app/build/libs/*.jar app.jar

USER spring:spring

EXPOSE 8080

# 컨테이너 메모리 한도를 인식하게 하고(MaxRAMPercentage), 세대별 ZGC 를 사용한다.
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["sh", "-c", "curl -fsS http://localhost:8080/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
