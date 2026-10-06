# syntax=docker/dockerfile:1.7
# The post-trade service (S16): multi-stage, JRE runtime, non-root. Also runs its own migrations as a one-off job.
# Built from the repository root: docker build -f deploy/docker/post-trade.Dockerfile .

FROM eclipse-temurin:21.0.12.1_1-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY contracts contracts
COPY services/post-trade services/post-trade
# The reactor lists every module; the exchange's poms are enough for Maven to resolve the build order.
COPY services/exchange/pom.xml services/exchange/pom.xml
COPY services/exchange/exchange-core/pom.xml services/exchange/exchange-core/pom.xml
COPY services/exchange/exchange-app/pom.xml services/exchange/exchange-app/pom.xml
COPY services/exchange/exchange-bench/pom.xml services/exchange/exchange-bench/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl services/post-trade -am package -DskipTests -Dspotless.check.skip=true

FROM eclipse-temurin:21.0.12.1_1-jre
RUN useradd --system --uid 10001 prayog
COPY --from=build /src/services/post-trade/target/post-trade-*.jar /app/app.jar
USER prayog
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
