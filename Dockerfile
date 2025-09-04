## Build stage
#FROM gradle:8-jdk17 AS build
#
## Копируем все файлы проекта
#COPY --chown=gradle:gradle . /home/gradle/src
#WORKDIR /home/gradle/src
#
## Собираем все модули
#RUN gradle build --no-daemon
#
## Runtime stage для API модуля
#FROM amazoncorretto:17-alpine
#RUN apk add --no-cache bash
#EXPOSE 8080
#
#RUN mkdir /app
#COPY --from=build /home/gradle/src/api/build/libs/*-all.jar /app/app.jar
#
#ENTRYPOINT ["java", "-jar", "/app/app.jar"]