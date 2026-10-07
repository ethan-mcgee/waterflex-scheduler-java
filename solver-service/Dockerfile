FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY calculation-engine calculation-engine
COPY solver-service solver-service
COPY scheduler-service/pom.xml scheduler-service/pom.xml
COPY routing-service/pom.xml routing-service/pom.xml
RUN chmod +x mvnw && ./mvnw -q -pl solver-service -am -DskipTests package
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/solver-service/target/solver-service-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8002
ENTRYPOINT ["java", "-jar", "app.jar"]
