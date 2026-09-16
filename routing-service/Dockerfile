FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY scheduler-service/pom.xml scheduler-service/pom.xml
COPY routing-service routing-service
RUN chmod +x mvnw && ./mvnw -q -pl routing-service -am -DskipTests package
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/routing-service/target/routing-service-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8001
ENTRYPOINT ["java", "-jar", "app.jar"]
