FROM bellsoft/liberica-openjdk-alpine:14
COPY build/libs/*.jar /app/app.jar
CMD ["/usr/lib/jvm/jdk/bin/java", "-jar", "/app/app.jar", "server"]
