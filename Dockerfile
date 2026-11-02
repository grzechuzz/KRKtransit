FROM maven:3.10.0-eclipse-temurin-25 AS build
WORKDIR /repo
COPY pom.xml ./
COPY schedule-core schedule-core
COPY schedule-contracts schedule-contracts
COPY schedule-service schedule-service
COPY realtime-contracts realtime-contracts
COPY realtime-ingestion-service realtime-ingestion-service
COPY stop-events-contracts stop-events-contracts
COPY stop-events-service stop-events-service
COPY live-vehicles-contracts live-vehicles-contracts
COPY live-vehicles-service live-vehicles-service
COPY prediction-contracts prediction-contracts
COPY public-api-service public-api-service
RUN --mount=type=cache,id=krktransit-maven,target=/root/.m2,sharing=locked \
    mvn --batch-mode --no-transfer-progress -Dmaven.test.skip=true package
RUN for service in schedule-service realtime-ingestion-service stop-events-service live-vehicles-service public-api-service; do \
        java -Djarmode=tools -jar "$service/target/$service-0.1.0.jar" extract --layers \
            --application-filename app.jar --destination "/layers/$service" && \
        mkdir -p "/layers/$service/dependencies" "/layers/$service/spring-boot-loader" \
            "/layers/$service/snapshot-dependencies" "/layers/$service/application"; \
    done

FROM eclipse-temurin:25.0.5_8-jre
ARG SERVICE
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app \
    && install -d -o app -g app /data
WORKDIR /app
COPY --from=build /layers/${SERVICE}/dependencies/ ./
COPY --from=build /layers/${SERVICE}/spring-boot-loader/ ./
COPY --from=build /layers/${SERVICE}/snapshot-dependencies/ ./
COPY --from=build /layers/${SERVICE}/application/ ./
USER app
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
