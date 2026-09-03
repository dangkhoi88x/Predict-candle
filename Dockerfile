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
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app

# The glob matches only the repackaged fat jar; Maven also leaves a .jar.original beside it,
# which does not end in .jar. Copying by glob keeps this working across version bumps.
COPY --from=build /app/target/*.jar app.jar

# Nothing here needs to write to the filesystem or bind a privileged port.
RUN useradd --create-home --uid 10001 candles
USER candles

# The only way this application is deployed, so it is where "this is not a developer's
# laptop" gets stated. StartupSecretsCheck refuses to boot under this profile while
# AUTH_JWT_SECRET or ROUND_TOKEN_SECRET are still the defaults published in application.yaml.
ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
