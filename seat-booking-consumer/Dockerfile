FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Copy pre-built jar from host to avoid Gradle network timeouts during docker build
COPY seat-booking-consumer/build/libs/seat-booking-consumer.jar app.jar

EXPOSE 8090

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
