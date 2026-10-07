# BedwarsRecoded Velocity proxy. Persistent, single entry point.
#   docker build -f deploy/docker/velocity.Dockerfile -t bedwars-velocity:1.0.0 .

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY BedwarsRecoded-API BedwarsRecoded-API
COPY BedwarsRecoded-Core BedwarsRecoded-Core
COPY BedwarsRecoded-Spigot BedwarsRecoded-Spigot
COPY BedwarsRecoded-Velocity BedwarsRecoded-Velocity
COPY BedwarsRecoded-Controller BedwarsRecoded-Controller
RUN mvn -q -pl BedwarsRecoded-Velocity -am -DskipTests package

FROM eclipse-temurin:25-jre AS runtime

ARG VELOCITY_VERSION=4.2.0

RUN apt-get update && apt-get install -y --no-install-recommends curl jq \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /proxy
# Fetch the latest stable build for VELOCITY_VERSION via the PaperMC fill v3 API.
RUN curl -fsSL "https://fill.papermc.io/v3/projects/velocity/versions/${VELOCITY_VERSION}/builds/latest" -o /tmp/velocity.json \
    && curl -fsSL "$(jq -r '.downloads["server:default"].url' /tmp/velocity.json)" -o velocity.jar \
    && rm /tmp/velocity.json \
    && ls -l velocity.jar

COPY --from=build /src/BedwarsRecoded-Velocity/target/BedwarsRecoded-Velocity-*.jar /proxy/plugins/BedwarsRecoded-Velocity.jar

ENV CONTROLLER_URL=http://bedwars-controller:8080

# The proxy's own configuration: `try = ["lobby"]` and the [servers] entry for the
# lobby are what make players land in a hub instead of being dropped. See the file.
COPY deploy/docker/velocity.toml /proxy/velocity.toml

ENTRYPOINT ["java", "-jar", "velocity.jar"]