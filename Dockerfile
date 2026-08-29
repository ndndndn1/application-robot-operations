FROM node:26-alpine@sha256:2d984a15c9b54fd0aeb608b8e0d0d83529eb34d2966db27a1fb4f1edc3d298a3 AS web-build
WORKDIR /web
COPY web/package.json web/package-lock.json web/tsconfig.json web/vite.config.ts web/index.html ./
COPY web/src ./src
RUN npm ci --ignore-scripts && npm run typecheck && npm run build

FROM maven:3.9-eclipse-temurin-21@sha256:c07f7ccfb8ca6c9fa29ee523f00afa7d2ca6132c92f8652c4aebb5ee3491f502 AS backend-build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
COPY --from=web-build /web/dist ./src/main/resources/static
RUN mvn -B test package

FROM backend-build AS test
CMD ["mvn", "-B", "test"]

FROM eclipse-temurin:21-jre@sha256:8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55 AS runtime
LABEL org.opencontainers.image.source="https://github.com/ndndndn1/application-robot-operations"
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=backend-build /workspace/target/application-robot-operations-1.1.0.jar app.jar
RUN groupadd --gid 10001 fleet && useradd --uid 10001 --gid fleet --no-create-home --shell /usr/sbin/nologin fleet \
    && chown -R fleet:fleet /app
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
