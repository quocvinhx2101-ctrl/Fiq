# syntax=docker/dockerfile:1.7
FROM node:24.7-bookworm-slim AS ui
WORKDIR /src/fiq-ui
COPY fiq-ui/package.json fiq-ui/package-lock.json ./
RUN npm ci
COPY fiq-ui/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-noble AS backend
WORKDIR /src
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY fiq-domain/ fiq-domain/
COPY fiq-delta/ fiq-delta/
COPY fiq-engine-spark/ fiq-engine-spark/
COPY fiq-spark-job/ fiq-spark-job/
COPY fiq-server/ fiq-server/
COPY --from=ui /src/fiq-server/src/main/resources/META-INF/resources/ fiq-server/src/main/resources/META-INF/resources/
RUN ./gradlew :fiq-server:quarkusBuild :fiq-spark-job:shadowJar --no-daemon

FROM eclipse-temurin:21-jre-noble AS runtime
RUN useradd --system --uid 10001 --create-home fiq
WORKDIR /opt/fiq
COPY --from=backend --chown=fiq:fiq /src/fiq-server/build/quarkus-app/ ./
COPY --from=backend --chown=fiq:fiq /src/fiq-spark-job/build/libs/*-all.jar ./fiq-spark-job.jar
USER 10001
EXPOSE 9091
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar quarkus-run.jar"]
