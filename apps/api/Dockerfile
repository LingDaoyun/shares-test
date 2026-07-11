FROM eclipse-temurin:17-jre

WORKDIR /app

RUN groupadd --system app \
    && useradd --system --gid app --home-dir /app app

COPY apps/api/target/ai-stock-api-0.1.0-SNAPSHOT.jar /app/app.jar
COPY apps/api/docker-entrypoint.sh /usr/local/bin/ai-stock-api-entrypoint

RUN chmod 0755 /usr/local/bin/ai-stock-api-entrypoint

ENV SERVER_PORT=19080 \
    TZ=Asia/Shanghai

EXPOSE 19080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=5 \
  CMD curl -fsS http://127.0.0.1:19080/actuator/health | grep -q '"status":"UP"'

ENTRYPOINT ["/usr/local/bin/ai-stock-api-entrypoint"]
