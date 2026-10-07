# syntax=docker/dockerfile:1
# =============================================================================
#  BedwarsRecoded game-server image
# =============================================================================
#  One container = one Minecraft server running the BedwarsRecoded plugin.
#
#  The server implementation is a build argument:
#
#    SERVER_ENGINE=spigot  (default) - Spigot, the target this plugin is built for.
#                                      Spigot ships no prebuilt jar, so this compiles
#                                      it with the official BuildTools - UNLESS a jar
#                                      is supplied in server-jars/, which skips that
#                                      entirely (see below).
#    SERVER_ENGINE=paper             - Paper, downloaded from the PaperMC API. Paper
#                                      publishes prebuilt jars, so this never compiles.
#
#  THE FAST PATH - skip the Spigot compile by supplying the jar:
#      cp /path/to/spigot-26.3.jar server-jars/
#      docker build -f deploy/docker/gameserver.Dockerfile -t bedwars-spigot:1.0.0 .
#
#  Resolution order lives in resolve-server-jar.sh: a supplied jar, else a Paper
#  download, else the Spigot compile - and the compile only happens when nothing
#  else was available.
#
#  The image itself is engine-agnostic from here on: the jar is always
#  /server/server.jar, so the entrypoint, probes and manifests do not care which
#  engine was selected.
# =============================================================================

# -----------------------------------------------------------------------------
# The plugin, built by the reactor.
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS plugin
WORKDIR /src
COPY pom.xml .
COPY BedwarsRecoded-API BedwarsRecoded-API
COPY BedwarsRecoded-Core BedwarsRecoded-Core
COPY BedwarsRecoded-Spigot BedwarsRecoded-Spigot
COPY BedwarsRecoded-Velocity BedwarsRecoded-Velocity
COPY BedwarsRecoded-Controller BedwarsRecoded-Controller
RUN mvn -q -pl BedwarsRecoded-Spigot -am -DskipTests package

# -----------------------------------------------------------------------------
# Resolve the server jar. This stage carries the JDK, git and python3 the two
# build paths need; none of that reaches the runtime image.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS server
ARG SERVER_ENGINE=spigot
ARG SPIGOT_REV=26.3
ARG PAPER_VERSION=
ARG PAPER_BUILD=
ARG PAPER_CHANNEL=STABLE
ARG BUILD_TOOLS_URL=
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl git python3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY deploy/docker/resolve-server-jar.sh /usr/local/bin/resolve-server-jar.sh
# A jar placed here is used verbatim; an empty folder just means "fall through to
# the engine's own build path".
COPY server-jars /server-jars
RUN chmod +x /usr/local/bin/resolve-server-jar.sh \
    && SERVER_ENGINE="${SERVER_ENGINE}" \
       SPIGOT_REV="${SPIGOT_REV}" \
       PAPER_VERSION="${PAPER_VERSION}" \
       PAPER_BUILD="${PAPER_BUILD}" \
       PAPER_CHANNEL="${PAPER_CHANNEL}" \
       BUILD_TOOLS_URL="${BUILD_TOOLS_URL:-https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar}" \
       /usr/local/bin/resolve-server-jar.sh /server.jar \
    && ls -l /server.jar

FROM eclipse-temurin:25-jre AS runtime

# curl+unzip: the entrypoint pulls an arena template from S3-compatible storage at boot.
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /server
# A standalone server jar: it resolves its own libraries on first start.
COPY --from=server /server.jar /server/server.jar

COPY --from=plugin /src/BedwarsRecoded-Spigot/target/BedwarsRecoded-Spigot-*.jar /server/plugins/BedwarsRecoded.jar

# A game server is non-interactive: there is no operator to click through the
# Minecraft EULA, so accept it here and pre-seed minimal server config. The world
# itself comes from the staged template at boot.
#
# white-list is stated EXPLICITLY on purpose. It used to be left to the server's own
# default, and this server defaults it to *true* -- with an empty whitelist.json
# that rejects every player ("You are not whitelisted on this server!"). A game server
# must accept the players the controller routes to it; the whitelist is not the access
# control here, the controller's queue is.
RUN printf 'eula=true\n' > /server/eula.txt \
    && printf 'online-mode=false\nlevel-name=world\nspawn-protection=0\nmax-players=40\nview-distance=6\nsimulation-distance=4\nwhite-list=false\nenforce-whitelist=false\n' > /server/server.properties

# This server sits behind a proxy: bungeecord forwarding must be on, or a player the
# proxy sends here cannot complete the handshake. The same file is used by the lobby.
COPY deploy/docker/spigot.yml /server/spigot.yml

# The arena templates the server can stage as its main world. In production these live in
# object storage and are fetched at boot; a template baked here makes the image
# self-contained (and is how the dev/test cluster gets a real map).
COPY deploy/templates /templates

# Stage the arena BEFORE the server starts: the server reads its main world during
# startup, so the world has to exist before the JVM runs. See entrypoint.sh.
COPY deploy/docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# A game server never keeps arenas on disk: templates are pulled from S3 at boot.
# G1 rather than ZGC: ZGC's native overhead does not fit a small container limit well
# (a 1.5 GiB pod OOMKilled while loading a real arena). A conservative heap percentage
# leaves room for metaspace, Netty's direct buffers and the JVM itself.
# Override per-deployment with the JAVA_TOOL_OPTIONS env var (gameServerSet.jvmOptions).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["/entrypoint.sh"]