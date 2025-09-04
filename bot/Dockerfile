# Build stage
FROM gradle:8-jdk17 AS build

COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle :bot:shadowJar --no-daemon

# Runtime stage
FROM amazoncorretto:17-alpine
RUN apk add --no-cache bash
RUN mkdir /app
COPY --from=build /home/gradle/src/bot/build/libs/telegram-bot-all.jar /app/bot.jar

ENTRYPOINT ["java", "-jar", "/app/bot.jar"]
