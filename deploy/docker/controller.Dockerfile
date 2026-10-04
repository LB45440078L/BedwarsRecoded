# BedwarsRecoded controller service. Watches the queue and scales GameServerSets.
#   docker build -f deploy/docker/controller.Dockerfile -t bedwars-controller:1.0.0 .

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY BedwarsRecoded-API BedwarsRecoded-API
COPY BedwarsRecoded-Controller BedwarsRecoded-Controller
RUN mvn -q -pl BedwarsRecoded-Controller -am -DskipTests package

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=build /src/BedwarsRecoded-Controller/target/BedwarsRecoded-Controller-*.jar /app/controller.jar

ENV BEDWARS_HTTP_PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/controller.jar"]