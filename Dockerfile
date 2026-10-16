FROM node:22-bookworm-slim AS frontend
WORKDIR /build/frontend
RUN npm install --global pnpm@11.25.0
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm test && pnpm build

FROM maven:3.9.11-eclipse-temurin-21 AS backend
WORKDIR /build/backend
COPY backend/pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY backend/src ./src
RUN mvn -B -ntp package dependency:copy-dependencies -DincludeScope=runtime

FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=backend /build/backend/target/habit-tracker-api-0.1.0.jar ./api.jar
COPY --from=backend /build/backend/target/dependency/ ./lib/
COPY --from=frontend /build/frontend/dist/ ./public/
ENV APP_HOST=0.0.0.0 FRONTEND_DIR=/app/public
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/api.jar:/app/lib/*", "br.com.habittracker.Main"]
