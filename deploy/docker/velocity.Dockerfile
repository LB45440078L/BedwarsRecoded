# BedwarsRecoded Velocity proxy. Persistent, single entry point.
#   docker build -f deploy/docker/velocity.Dockerfile -t bedwars-velocity:1.0.0 .

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY BedwarsRecoded-API BedwarsRecoded-API
COPY BedwarsRecoded-Velocity BedwarsRecoded-Velocity
RUN mvn -q -pl BedwarsRecoded-Velocity -am -DskipTests package

FROM eclipse-temurin:25-jre AS runtime

ARG VELOCITY_VERSION=3.4.0
ARG VELOCITY_URL=https://api.papermc.io/v2/projects/velocity

RUN apt-get update && apt-get install -y --no-install-recommends curl jq \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /proxy
RUN JAR="velocity-${VELOCITY_VERSION}.jar" \
    && curl -fsSL -o velocity.jar \
       "https://fill.papermc.io/v3/projects/velocity/versions/${VELOCITY_VERSION}/builds/latest/downloads/${JAR}" \
    || curl -fsSL -o velocity.jar \
       "https://api.papermc.io/v2/projects/velocity/versions/${VELOCITY_VERSION}/builds"

COPY --from=build /src/BedwarsRecoded-Velocity/target/BedwarsRecoded-Velocity-*.jar /proxy/plugins/BedwarsRecoded-Velocity.jar

ENV CONTROLLER_URL=http://bedwars-controller:8080
ENTRYPOINT ["java", "-jar", "velocity.jar"]