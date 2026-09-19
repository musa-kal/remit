# syntax=docker/dockerfile:1
# routing-service image. Build from the repository root so the parent pom and remit-common are in
# the context:
#   docker build -f routing-service/Dockerfile -t remit/routing-service .

# --- Stage 1: build the layered Spring Boot jar --------------------------------------------------
FROM maven:3-eclipse-temurin-17 AS build
WORKDIR /workspace

# Poms first: the dependency layer below is rebuilt only when a pom changes, not on every code edit.
# Maven reads every module of the reactor, so all four poms are needed even for one service.
COPY pom.xml ./
COPY remit-common/pom.xml remit-common/
COPY intake-service/pom.xml intake-service/
COPY routing-service/pom.xml routing-service/
COPY api-service/pom.xml api-service/
RUN mvn -B -q -pl routing-service -am dependency:go-offline

COPY remit-common/src remit-common/src
COPY routing-service/src routing-service/src
RUN mvn -B -q -pl routing-service -am package -DskipTests \
    && cp routing-service/target/routing-service-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# --- Stage 2: runtime ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd --system --gid 10001 remit \
    && useradd --system --uid 10001 --gid remit --home-dir /app --no-create-home remit

# One COPY per Spring Boot layer, least to most frequently changed, so a code change only
# replaces the small application layer.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER 10001:10001
EXPOSE 8082

HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --start-interval=3s --retries=3 \
    CMD curl -fsS "http://localhost:${REMIT_ROUTING_PORT:-8082}/actuator/health" >/dev/null || exit 1

# Extra JVM flags go in JAVA_TOOL_OPTIONS (for example -Xss512k or a debug agent).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "application.jar"]
