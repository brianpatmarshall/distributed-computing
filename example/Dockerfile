FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Copy the parent pom and the example module
COPY pom.xml .
COPY example/pom.xml example/
COPY example/src example/src
# Build only the example module; -N on the parent prevents resolving sibling modules
RUN mvn -N install -q && mvn -f example/pom.xml clean package -q -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/example/target/example-1.0-SNAPSHOT-jar-with-dependencies.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
