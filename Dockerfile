FROM bellsoft/liberica-openjdk-alpine:14
COPY build/libs/*.jar /app/wstunnel.jar
CMD java -jar /app/wstunnel.jar server -p $PORT
