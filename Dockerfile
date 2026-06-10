FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn clean package -q -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/Anvil-1.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-Denv.path=/app", "-jar", "app.jar"]
