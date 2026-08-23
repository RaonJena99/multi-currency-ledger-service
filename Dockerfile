# Build Stage
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

# Copy gradle wrapper and related files for dependency resolution caching
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Grant execute permission for gradlew
RUN chmod +x gradlew

# Download dependencies (this layer will be cached unless build.gradle changes)
# Since we don't have the src code yet, we just run dependencies resolution
RUN ./gradlew dependencies --no-daemon || true

# Copy source code
COPY src src

# Build the application (exclude tests as they are run in CI)
RUN ./gradlew bootJar --no-daemon -x test

# Runtime Stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Add a non-root user for security
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring

# Copy the built jar from the builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Expose default application port
EXPOSE 8080

# Environment variables to optimize JVM
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XshowSettings:vm"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
