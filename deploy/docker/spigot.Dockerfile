# BedwarsRecoded game pod: Paper server + the plugin, one match per pod.
# Multi-stage: build the plugin with the reactor, then assemble a minimal runtime.
#
# Build from the repo root:
#   docker build -f deploy/docker/spigot.Dockerfile -t bedwars-spigot:1.0.0 .
#
# The Paper server jar is fetched at build time. Pin the exact build so pods are
# reproducible; PAPER_BUILD is a PaperMC build number for the chosen version.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY BedwarsRecoded-API BedwarsRecoded-API
COPY BedwarsRecoded-Core BedwarsRecoded-Core
COPY BedwarsRecoded-Spigot BedwarsRecoded-Spigot
COPY BedwarsRecoded-Velocity BedwarsRecoded-Velocity
COPY BedwarsRecoded-Controller BedwarsRecoded-Controller
RUN mvn -q -pl BedwarsRecoded-Spigot -am -DskipTests package

FROM eclipse-temurin:25-jre AS runtime

ARG PAPER_VERSION=26.2
ARG PAPER_BUILD=latest

RUN apt-get update && apt-get install -y --no-install-recommends curl jq \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /server
# Fetch the requested Paper build via the PaperMC fill v3 API ("latest" or a build id).
RUN curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/${PAPER_VERSION}/builds/${PAPER_BUILD}" -o /tmp/paper.json \
    && curl -fsSL "$(jq -r '.downloads["server:default"].url' /tmp/paper.json)" -o paper.jar \
    && rm /tmp/paper.json \
    && ls -l paper.jar

COPY --from=build /src/BedwarsRecoded-Spigot/target/BedwarsRecoded-Spigot-*.jar /server/plugins/BedwarsRecoded.jar

# A game pod is non-interactive: there is no operator to click through the
# Minecraft EULA, so accept it here and pre-seed minimal server config. The world
# itself comes from the Slime template at boot.
RUN printf 'eula=true\n' > /server/eula.txt \
    && printf 'online-mode=false\nspawn-protection=0\nmax-players=40\nview-distance=6\nsimulation-distance=4\n' > /server/server.properties

# A game pod never keeps arenas on disk: templates are pulled from S3 at boot.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"

ENTRYPOINT ["java", "-jar", "paper.jar", "--nogui"]