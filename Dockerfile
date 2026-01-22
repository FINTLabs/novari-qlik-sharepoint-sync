FROM gradle:jdk23 AS builder
USER root
COPY . .
RUN gradle --no-daemon build

FROM eclipse-temurin:23-jre
ENV TZ="Europe/Oslo" JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError"
COPY --from=builder /home/gradle/build/libs/novari-qlik-sharepoint-sync*.jar /data/app.jar
CMD ["java", "-jar", "/data/app.jar"]
