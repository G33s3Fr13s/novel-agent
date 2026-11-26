# ─── Build stage ───────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /app
COPY settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn dependency:go-offline -q || true
COPY src ./src
RUN mvn package -DskipTests -q

# ─── Runtime stage ─────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

COPY scripts/onboard.sh /app/scripts/onboard.sh
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/scripts/onboard.sh /app/entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
