FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY fluxora-common fluxora-common
COPY fluxora-platform fluxora-platform
RUN mvn -pl fluxora-platform -am package -DskipTests
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/fluxora-platform/target/fluxora-platform-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
