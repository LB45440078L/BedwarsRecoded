# BedwarsRecoded controller service. Watches the queue and scales GameServerSets.
#   docker build -f deploy/docker/controller.Dockerfile -t bedwars-controller:1.0.0 .

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY BedwarsRecoded-API BedwarsRecoded-API
COPY BedwarsRecoded-Core BedwarsRecoded-Core
COPY BedwarsRecoded-Spigot BedwarsRecoded-Spigot
COPY BedwarsRecoded-Velocity BedwarsRecoded-Velocity
COPY BedwarsRecoded-Controller BedwarsRecoded-Controller
RUN mvn -q -pl BedwarsRecoded-Controller -am -DskipTests package

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=build /src/BedwarsRecoded-Controller/target/BedwarsRecoded-Controller-*.jar /app/controller.jar

# The Docker provisioner shells out to the docker CLI. It is only needed when the
# controller runs inside a container and provisions sibling containers, so it is
# opt-in and off by default (keeps the image small for the Kubernetes path).
ARG WITH_DOCKER_CLI=false
RUN if [ "$WITH_DOCKER_CLI" = "true" ]; then \
        apt-get update \
        && apt-get install -y --no-install-recommends docker.io \
        && rm -rf /var/lib/apt/lists/*; \
    fi

ENV BEDWARS_HTTP_PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/controller.jar"]