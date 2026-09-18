# Build with the JDK, ship on the JRE — the runtime image has no compiler, no Maven and no
# source in it. Uses the maven image rather than ./mvnw because .mvn/wrapper/maven-wrapper.jar
# is gitignored, so a clean checkout would have to download the wrapper before it can start.
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /app

# Dependencies resolve from pom.xml alone, so this layer is cached until pom.xml changes —
# editing source does not re-download the dependency tree.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
# Extracted into a plain jar plus lib/ here, not in the runtime stage: the AOT cache below can only
# hold classes read from plain jars, and extracting there would leave the fat jar in a layer of its
# own that a later rm cannot take back out of the image. Renamed first because the extracted jar
# keeps the source's name, and the ENTRYPOINT should not change with the project version. The glob
# matches only the repackaged jar; Maven's .jar.original beside it does not end in .jar.
RUN mvn -q -B package -DskipTests \
 && cp target/*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --destination extracted

FROM eclipse-temurin:25-jre
WORKDIR /app

# Nothing here needs to write to the filesystem or bind a privileged port.
RUN useradd --create-home --uid 10001 candles

COPY --from=build --chown=candles /app/extracted extracted

# An AOT cache (JEP 483/514/515): the classes a start loads, and profiles of the methods it runs,
# recorded by one training run here and mapped in at every start. The free instance has a tenth of
# a CPU and sleeps when idle, so the visitor who wakes it waits for the JVM to load the same ~20k
# classes every time. Measured as the CPU the container burns before it first answers (three runs
# each, 512 MB): 8.3s without the cache, 5.2s with it. CPU is what the instance is short of, so that
# is the share of a cold start this removes; wall-clock runs at --cpus 0.1 were too noisy to quote.
#
# The training run is here rather than in the build stage because the ENTRYPOINT must use the same
# JDK and the same classpath as the run that recorded the cache, or the cache is not used.
#
# -XX:-AOTClassLinking is what keeps this safe to deploy. With linking on, the cache also carries
# archived heap objects, and a start whose heap differs from the training run's does not fall back:
# the JVM refuses to boot ("archived heap objects cannot be loaded"). That happened here with the
# heap render.yaml's MaxRAMPercentage gives; pinning -Xms/-Xmx to match only moves the cliff to the
# day someone changes the memory. Without linking it boots under any heap or GC (checked with a
# smaller heap and with G1), and it measured no slower than linking with a pinned heap.
#
# spring.context.exit=onRefresh stops after the context is built, before runners, schedulers and
# the web server. There is no database during a build, so the training run skips what would reach
# one (Flyway, Hibernate's schema validation and its JDBC metadata lookup) and uses the dev
# profile, since prod refuses to boot on the default secrets. The classes those skip are a small
# share and simply load the normal way. -Xlog:aot=error hides a few hundred lines about optional
# classes (OAuth2, SAML) the cache cannot hold.
RUN java -XX:+UseSerialGC -XX:-AOTClassLinking -Xlog:aot=error -XX:AOTCacheOutput=extracted/app.aot \
        -Dspring.context.exit=onRefresh \
        -Dspring.profiles.active=dev \
        -Dspring.flyway.enabled=false \
        -Dspring.jpa.hibernate.ddl-auto=none \
        -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
        -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
        -jar extracted/app.jar
USER candles

# The only way this application is deployed, so it is where "this is not a developer's
# laptop" gets stated. StartupSecretsCheck refuses to boot under this profile while
# AUTH_JWT_SECRET or ROUND_TOKEN_SECRET are still the defaults published in application.yaml.
ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080
# UseSerialGC matches the training run and render.yaml's JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:AOTCache=extracted/app.aot", "-jar", "extracted/app.jar"]
