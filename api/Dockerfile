# Build stage - используем платформу хоста для сборки
FROM --platform=$BUILDPLATFORM gradle:8-jdk17 AS build

COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle :api:shadowJar --no-daemon

# Runtime stage
FROM amazoncorretto:17
RUN mkdir /app
COPY --from=build /home/gradle/src/api/build/libs/api-all.jar /app/api.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/api.jar"]