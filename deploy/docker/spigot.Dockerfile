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
RUN mvn -q -pl BedwarsRecoded-Spigot -am -DskipTests package

FROM eclipse-temurin:25-jre AS runtime

ARG PAPER_VERSION=26.2
ARG PAPER_BUILD=129
ARG PAPERMC_API=https://api.papermc.io/v2

RUN apt-get update && apt-get install -y --no-install-recommends curl jq \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /server
# Fetch the exact Paper build for this version.
RUN curl -fsSL "${PAPERMC_API}/projects/paper/versions/${PAPER_VERSION}" \
      | jq -r ".builds[-1]" > /dev/null \
    && JAR="paper-${PAPER_VERSION}-${PAPER_BUILD}.jar" \
    && curl -fsSL -o paper.jar \
       "${PAPERMC_API}/projects/paper/versions/${PAPER_VERSION}/builds/${PAPER_BUILD}/downloads/${JAR}"

COPY --from=build /src/BedwarsRecoded-Spigot/target/BedwarsRecoded-Spigot-*.jar /server/plugins/BedwarsRecoded.jar

# A game pod never keeps arenas on disk: templates are pulled from S3 at boot.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"

ENTRYPOINT ["java", "-jar", "paper.jar", "--nogui"]