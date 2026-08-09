# Build the shaded jar inside the image so a release needs no local JDK.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# Copy the Gradle wiring first; this layer only rebuilds when dependencies change.
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

COPY src/ src/
RUN ./gradlew --no-daemon shadowJar


FROM eclipse-temurin:21-jre-alpine
WORKDIR /relay

# Unprivileged: the proxy needs no root, and port 25565 is above 1024.
RUN addgroup -S relay && adduser -S -G relay relay \
    && mkdir -p /relay/data \
    && chown -R relay:relay /relay
USER relay

COPY --from=build --chown=relay:relay /build/build/libs/relay-*.jar /relay/relay.jar

# Config, logs and the SQLite database (once §6.3 lands) all live here, so one mount
# covers the whole of a deployment's state.
VOLUME ["/relay/data"]
WORKDIR /relay/data

EXPOSE 25565

# Container memory is the real limit, so let the JVM discover it rather than pinning
# a heap size that will be wrong on the next host.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /relay/relay.jar"]
