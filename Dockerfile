FROM eclipse-temurin:25-alpine
RUN addgroup -S wstunnel && adduser -S wstunnel -G wstunnel
COPY --chown=wstunnel:wstunnel build/libs/*.jar /app/wstunnel.jar
WORKDIR /app
USER wstunnel
ENTRYPOINT ["java", "-jar", "/app/wstunnel.jar"]
