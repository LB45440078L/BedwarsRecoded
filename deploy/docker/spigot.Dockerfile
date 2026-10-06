# BedwarsRecoded game pod: Spigot server + the plugin, one match per pod.
# Multi-stage: build the plugin with the reactor, build Spigot from source, then
# assemble a minimal runtime.
#
# Build from the repo root:
#   docker build -f deploy/docker/spigot.Dockerfile -t bedwars-spigot:1.0.0 .
#
# Spigot is NOT distributed as a prebuilt jar, so it is built here with BuildTools,
# the official SpigotMC build tool, from the pinned Minecraft revision. That build is
# slow (it decompiles and patches the vanilla server) but it is the only legitimate
# way to ship a Spigot server, and pinning SPIGOT_REV keeps pods reproducible.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY BedwarsRecoded-API BedwarsRecoded-API
COPY BedwarsRecoded-Core BedwarsRecoded-Core
COPY BedwarsRecoded-Spigot BedwarsRecoded-Spigot
COPY BedwarsRecoded-Velocity BedwarsRecoded-Velocity
COPY BedwarsRecoded-Controller BedwarsRecoded-Controller
RUN mvn -q -pl BedwarsRecoded-Spigot -am -DskipTests package

# ---------------------------------------------------------------------------
# Build Spigot itself. BuildTools needs a JDK (it compiles the server) and git.
# --compile spigot builds Spigot only and skips the CraftBukkit/API artifacts we
# do not run. The bundler jar it emits is the runnable server.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS spigot
ARG SPIGOT_REV=26.3
RUN apt-get update && apt-get install -y --no-install-recommends curl git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /buildtools
RUN curl -fsSL -o BuildTools.jar \
        https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar \
    && java -jar BuildTools.jar --rev "${SPIGOT_REV}" --compile spigot \
    && cp "$(ls -1 spigot-*.jar | head -1)" /spigot.jar \
    && ls -l /spigot.jar

FROM eclipse-temurin:25-jre AS runtime

RUN apt-get update && apt-get install -y --no-install-recommends curl unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /server
# Spigot is a standalone bundler jar: it resolves its own libraries on first start.
COPY --from=spigot /spigot.jar /server/spigot.jar

COPY --from=build /src/BedwarsRecoded-Spigot/target/BedwarsRecoded-Spigot-*.jar /server/plugins/BedwarsRecoded.jar

# A game pod is non-interactive: there is no operator to click through the
# Minecraft EULA, so accept it here and pre-seed minimal server config. The world
# itself comes from the staged template at boot.
#
# white-list is stated EXPLICITLY on purpose. It used to be left to the server's own
# default, and this server defaults it to *true* -- with an empty whitelist.json
# that rejects every player ("You are not whitelisted on this server!"). A game pod
# must accept the players the controller routes to it; the whitelist is not the access
# control here, the controller's queue is.
RUN printf 'eula=true\n' > /server/eula.txt \
    && printf 'online-mode=false\nlevel-name=world\nspawn-protection=0\nmax-players=40\nview-distance=6\nsimulation-distance=4\nwhite-list=false\nenforce-whitelist=false\n' > /server/server.properties

# The arena templates the pod can stage as its main world. In production these live in
# object storage and are fetched at boot; a template baked here makes the image
# self-contained (and is how the dev/test cluster gets a real map).
COPY deploy/templates /templates

# Stage the arena BEFORE the server starts: the server reads its main world during
# startup, so the world has to exist before the JVM runs. See entrypoint.sh.
COPY deploy/docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# A game pod never keeps arenas on disk: templates are pulled from S3 at boot.
# G1 rather than ZGC: ZGC's native overhead does not fit a small container limit well
# (a 1.5 GiB pod OOMKilled while loading a real arena). A conservative heap percentage
# leaves room for metaspace, Netty's direct buffers and the JVM itself.
# Override per-deployment with the JAVA_TOOL_OPTIONS env var (gameServerSet.jvmOptions).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["/entrypoint.sh"]
