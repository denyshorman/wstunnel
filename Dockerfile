FROM bellsoft/liberica-openjdk-alpine:14
COPY build/libs/*.jar /app/app.jar
CMD java -jar /app/app.jar server -p $PORT
