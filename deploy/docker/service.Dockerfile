# Image for one RCS3000 service, from its jar built by ./mvnw install -DskipTests.
#   docker build -f deploy/docker/service.Dockerfile --build-arg JAR=rcs-fleet/target/rcs-fleet-0.1.0-SNAPSHOT-exec.jar .
FROM eclipse-temurin:25-jre
ARG JAR
RUN useradd --system --uid 10001 rcs
COPY ${JAR} /app/app.jar
USER rcs
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
