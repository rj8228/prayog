# syntax=docker/dockerfile:1.7
# The exchange service (BUILD_PLAN 16.2 #29): multi-stage, JRE runtime, non-root.
# Built from the repository root: docker build -f deploy/docker/exchange.Dockerfile .

# The jar is platform-independent: it is built once on the build machine's own platform ($BUILDPLATFORM) and
# copied into a runtime image per target platform (make images: linux/amd64 and linux/arm64).
FROM --platform=$BUILDPLATFORM eclipse-temurin:21.0.12.1_1-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY contracts contracts
COPY services/exchange services/exchange
COPY services/post-trade/pom.xml services/post-trade/pom.xml
# The Maven repository is cached between builds, so only changed code is recompiled.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl services/exchange/exchange-app -am package -DskipTests -Dspotless.check.skip=true

FROM eclipse-temurin:21.0.12.1_1-jre
RUN useradd --system --uid 10001 prayog && mkdir -p /data/journal && chown prayog /data/journal
COPY --from=build /src/services/exchange/exchange-app/target/exchange-app-*.jar /app/app.jar
USER prayog
# Agrona needs the jdk.internal.misc export (ADR 0007); the heap follows the container's memory limit.
# JDK_JAVA_OPTIONS (read by the java launcher) accepts module flags; JAVA_TOOL_OPTIONS does not.
ENV JDK_JAVA_OPTIONS="--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -XX:MaxRAMPercentage=75"
VOLUME /data/journal
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
