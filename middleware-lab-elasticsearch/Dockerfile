# syntax=docker/dockerfile:1.7
#
# Image for this module. The build context is the REPOSITORY ROOT, not this directory:
# it is a multi-module Maven build and the parent pom plus middleware-lab-common are needed.
# docker-compose.yml next to this file already sets that up. By hand:
#
#   docker build -f middleware-lab-elasticsearch/Dockerfile -t lab/es:dev .     # from the repo root

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Poms first: dependency resolution is cached as long as no pom changes.
COPY pom.xml ./
COPY middleware-lab-common/pom.xml middleware-lab-common/
COPY middleware-lab-elasticsearch/pom.xml middleware-lab-elasticsearch/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline -DskipTests || true

COPY middleware-lab-common middleware-lab-common
COPY middleware-lab-elasticsearch middleware-lab-elasticsearch

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl middleware-lab-elasticsearch -am -DskipTests package \
 && cp middleware-lab-elasticsearch/target/*.jar /workspace/app.jar

# ---------- runtime ----------
FROM eclipse-temurin:21-jre AS runtime

# curl is here on purpose: it is what the healthcheck and your own poking around use.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --uid 10001 lab
USER lab
WORKDIR /home/lab

COPY --from=build --chown=lab:lab /workspace/app.jar app.jar

# Container-aware defaults; override with JAVA_OPTS at run time.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
