# Build stage - gradle поддерживает multi-arch
FROM --platform=$BUILDPLATFORM gradle:8-jdk17 AS build

# Аргументы для multi-platform
ARG TARGETPLATFORM
ARG BUILDPLATFORM
RUN echo "Building on $BUILDPLATFORM for $TARGETPLATFORM"

COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle buildFatJar --no-daemon

# Runtime stage - используем amazoncorretto, он поддерживает multi-arch
FROM amazoncorretto:17-alpine
RUN apk add --no-cache bash
EXPOSE 8080

RUN mkdir /app
COPY --from=build /home/gradle/src/build/libs/*-all.jar /app/app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]