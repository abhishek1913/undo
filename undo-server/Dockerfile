FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/undo-server-*-exec.jar /app/app.jar
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
