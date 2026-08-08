FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY build.gradle .
COPY settings.gradle .
COPY src src
RUN gradle build -x test

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
