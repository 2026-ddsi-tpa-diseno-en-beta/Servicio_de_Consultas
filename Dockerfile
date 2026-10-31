FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -B clean package -DskipTests
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/target/donatrack-servicio-consultas-1.0.0.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","app.jar"]
