# Build Stage
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

# Copy gradle wrapper and related files for dependency resolution caching
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
# lombok.config 가 없으면 @RequiredArgsConstructor 가 필드의 @Qualifier/@Value 를 생성자로 옮기지 않는다.
# 로컬·CI 빌드에는 있고 이미지 빌드에만 빠지면, 같은 타입 빈이 여럿인 RestClient 주입이 모호해져
# 이미지가 기동조차 하지 못한다(NoUniqueBeanDefinitionException). 테스트로는 잡히지 않는 차이다.
COPY lombok.config .

RUN chmod +x gradlew

# Warm the dependency cache. This layer is reused unless build.gradle changes.
RUN ./gradlew dependencies --no-daemon || true

COPY src src

# 테스트는 Testcontainers(Docker)가 필요하므로 이미지 빌드 안에서는 건너뛴다.
# CI 와 릴리스 워크플로가 이미지를 빌드하기 전에 전체 테스트를 실행한다.
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

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
