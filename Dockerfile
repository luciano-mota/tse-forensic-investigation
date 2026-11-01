FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /work
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home forensic
WORKDIR /app
COPY --from=build /work/target/tse-forensic-investigation-0.1.0.jar app.jar
USER forensic
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
