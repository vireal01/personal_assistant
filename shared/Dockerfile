# Build stage
FROM gradle:8-jdk17 AS build

# Копируем все файлы проекта
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src

# Собираем shared модуль
RUN gradle :shared:build --no-daemon

# Runtime stage (shared модуль обычно не запускается отдельно)
FROM amazoncorretto:17-alpine
RUN apk add --no-cache bash

# Копируем JAR файл shared модуля
RUN mkdir /app
COPY --from=build /home/gradle/src/shared/build/libs/*.jar /app/shared.jar

# Shared модуль обычно используется как библиотека, но если нужно запустить:
# ENTRYPOINT ["java", "-jar", "/app/shared.jar"]
