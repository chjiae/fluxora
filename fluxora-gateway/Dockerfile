FROM maven:3.9-eclipse-temurin-25
WORKDIR /workspace
COPY pom.xml .
COPY fluxora-common fluxora-common
COPY fluxora-gateway fluxora-gateway
EXPOSE 8081
CMD ["mvn", "-q", "-pl", "fluxora-gateway", "-am", "exec:java", "-Dexec.mainClass=io.fluxora.gateway.FluxoraGatewayApplication"]
