# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd -r -u 10001 app && mkdir -p /data && chown app /data
COPY --from=build /src/target/roster-sync-1.0.0.jar /app/app.jar
USER app
ENV JAVA_OPTS="-Xmx512m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
