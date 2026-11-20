# syntax=docker/dockerfile:1

# Build every module once; each runtime target below copies its jar out of this stage.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B package -DskipTests

# Shared by the controller and worker targets. Exec-form ENTRYPOINT makes java PID 1, so docker
# stop's SIGTERM reaches the JVM and Spring shuts down gracefully.
FROM eclipse-temurin:21-jre AS app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

FROM app AS controller
COPY --from=build /src/controller/target/objstore-controller.jar /app/app.jar

FROM app AS worker
COPY --from=build /src/worker/target/objstore-worker.jar /app/app.jar

# The on-demand client: the objstore CLI, plus curl (from the base image) and jq.
FROM eclipse-temurin:21-jre AS client
RUN apt-get update \
    && apt-get install -y --no-install-recommends jq \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/cli/target/objstore-cli.jar /app/objstore-cli.jar
COPY --chmod=755 <<'EOF' /usr/local/bin/objstore
#!/bin/sh
exec java -jar /app/objstore-cli.jar "$@"
EOF
CMD ["objstore", "--help"]

# Opt-in demo control for the web UI (Compose profile "demo"): stops, starts and corrupts workers
# through the Docker socket. Never part of the default topology.
FROM python:3.13-alpine AS demo-control
RUN apk add --no-cache docker-cli
COPY demo-control/server.py /app/server.py
ENTRYPOINT ["python3", "-u", "/app/server.py"]
