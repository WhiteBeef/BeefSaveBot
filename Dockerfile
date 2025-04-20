FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY target/BeefSaveBot-0.0.1-SNAPSHOT.jar app.jar

RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app

COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

RUN apk add --no-cache ffmpeg yt-dlp

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]