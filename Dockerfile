FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app

COPY target/BeefSaveBot-0.0.1-SNAPSHOT.jar app.jar

RUN apk add --no-cache ffmpeg yt-dlp

ENTRYPOINT ["java", "-jar", "app.jar"]
