FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/shopfront-server-*-exec.jar /app/app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
