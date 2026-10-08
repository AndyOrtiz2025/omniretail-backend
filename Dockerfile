# syntax=docker/dockerfile:1

# ---------- Etapa 1: compilacion ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Primero solo el wrapper y el pom: esta capa (dependencias) se cachea mientras el pom no cambie.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Las pruebas ya corren en CI; aqui solo se empaqueta.
RUN ./mvnw -B -DskipTests -Djacoco.skip=true package \
    && cp target/*.jar app.jar

# ---------- Etapa 2: runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/data/media && chown -R app:app /app

COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar

USER app

ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080 \
    MEDIA_STORAGE_PATH=/app/data/media \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
