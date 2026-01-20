FROM gradle:9.2.1-jdk AS builder
WORKDIR /app

COPY . .
RUN gradle --no-daemon bootJar

FROM gcr.io/distroless/java21-debian12:latest
ENV TZ="Europe/Oslo"
ENV JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError"

WORKDIR /data
COPY --from=builder /app/build/libs/*.jar /data/app.jar

USER nonroot
EXPOSE 8080
CMD ["app.jar"]
