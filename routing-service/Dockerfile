FROM eclipse-temurin:25-jdk@sha256:b8705e936964c228dfce359e63dbf08f6c69cb380ed1d576b1f6b1064f15567d AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY scheduler-service/pom.xml scheduler-service/pom.xml
COPY calculation-engine/pom.xml calculation-engine/pom.xml
COPY solver-service/pom.xml solver-service/pom.xml
COPY routing-service routing-service
RUN chmod +x mvnw && ./mvnw -q -pl routing-service -am -DskipTests package
FROM eclipse-temurin:25-jre@sha256:a640968ae55027b322cedd5f8228b01a84baff0b9e9360e47601c6a111ba1699
# Runs as a fixed unprivileged user, uid and gid 10001, the same in all three service images.
# It loads its prepared graph without writing (routing.graph.read-only), so the map volume is mounted read-only.
RUN groupadd --system --gid 10001 waterflex \
    && useradd --system --uid 10001 --gid waterflex --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin waterflex
WORKDIR /app
COPY --from=build /src/routing-service/target/routing-service-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8001
ENTRYPOINT ["java", "-jar", "app.jar"]
