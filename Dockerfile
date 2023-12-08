FROM eclipse-temurin:21-alpine
ENV WSTUNNEL_BINARY_PATH=/app/wstunnel.jar
COPY build/libs/*.jar $WSTUNNEL_BINARY_PATH
CMD java -jar $WSTUNNEL_BINARY_PATH server -p $PORT
