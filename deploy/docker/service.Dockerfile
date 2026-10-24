# Image for one Fleetloom service, from its jar built by ./mvnw install -DskipTests.
#   docker build -f deploy/docker/service.Dockerfile --build-arg JAR=fleetloom-fleet/target/fleetloom-fleet-0.1.0-SNAPSHOT-exec.jar .
FROM eclipse-temurin:25-jre
ARG JAR
RUN useradd --system --uid 10001 fleetloom
COPY ${JAR} /app/app.jar
USER fleetloom
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
