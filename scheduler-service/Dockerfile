FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY scheduler-service scheduler-service
COPY calculation-engine calculation-engine
COPY solver-service/pom.xml solver-service/pom.xml
COPY routing-service/pom.xml routing-service/pom.xml
RUN chmod +x mvnw && ./mvnw -q -pl scheduler-service -am -DskipTests package
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/scheduler-service/target/scheduler-service-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "app.jar"]
