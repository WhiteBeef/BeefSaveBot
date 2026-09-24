FROM eclipse-temurin:21-jdk-noble AS builder

WORKDIR /app

COPY target/BeefSaveBot-0.0.1-SNAPSHOT.jar app.jar

RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jdk-noble

# LibreOffice нужен для документов, таблиц и презентаций и весит ~600 МБ.
# Собрать образ без него: docker compose build --build-arg INSTALL_LIBREOFFICE=false
ARG INSTALL_LIBREOFFICE=true
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    DEBIAN_FRONTEND=noninteractive

# Системные утилиты ставим до копирования приложения, чтобы слой кэшировался между сборками
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      ffmpeg imagemagick librsvg2-bin poppler-utils python3 python3-venv \
      fonts-dejavu-core fonts-liberation && \
    if [ "$INSTALL_LIBREOFFICE" = "true" ]; then \
      apt-get install -y --no-install-recommends \
        libreoffice-writer-nogui libreoffice-calc-nogui libreoffice-impress-nogui; \
    fi && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

# yt-dlp ставится после приложения, чтобы при каждой пересборке подтягивалась свежая версия.
# curl-cffi нужен для impersonation: без него TikTok отдаёт yt-dlp заглушку вместо страницы
RUN python3 -m venv /opt/ytdlp-venv && \
    /opt/ytdlp-venv/bin/pip install --no-cache-dir -U "yt-dlp[default,curl-cffi]" && \
    ln -sf /opt/ytdlp-venv/bin/yt-dlp /usr/local/bin/yt-dlp

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
