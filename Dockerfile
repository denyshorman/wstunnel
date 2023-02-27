FROM bellsoft/liberica-openjdk-alpine:19
ENV WSTUNNEL_BINARY_PATH=/app/wstunnel.jar
COPY build/libs/*.jar $WSTUNNEL_BINARY_PATH
CMD java -jar $WSTUNNEL_BINARY_PATH server -p $PORT
