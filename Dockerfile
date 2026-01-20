FROM gradle:jdk23 AS builder
WORKDIR /app

USER root
COPY . .
RUN gradle --no-daemon build

FROM eclipse-temurin:23-jre
ENV TZ="Europe/Oslo" JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError"
COPY --from=builder /app/build/libs/novari-qlik-sharepoint-sync*.jar /data/app.jar

WORKDIR /data
USER root

ENTRYPOINT ["java", "-jar", "/data/app.jar"]
EXPOSE 8080
