# syntax=docker/dockerfile:1

# --- Build stage: full JDK and Maven, discarded after the build ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Dependencies first: this layer is reused while only the source code changes
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q dependency:go-offline

COPY lombok.config ./
COPY src src
# Tests run in CI with ./mvnw verify: they need Docker (Testcontainers), which is not available inside this build
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -DskipTests package \
    && java -Djarmode=tools -jar target/transaction-execution-api-*.jar extract --layers --launcher --destination extracted

# --- Runtime stage: JRE only, no compiler or build tools ---
FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app

# One layer per Spring Boot layer, from least to most frequently changed
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app
EXPOSE 8080
# Use up to 75% of the container memory limit for the heap instead of the JVM default of 25%
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
