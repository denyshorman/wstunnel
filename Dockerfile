FROM bellsoft/liberica-openjdk-alpine:19
ENV WSTUNNEL_BINARY_PATH=/app/wstunnel.jar
ENV JAVA_OPTS="-Xss512k -Xmx300m -XX:CICompilerCount=2"
COPY build/libs/*.jar $WSTUNNEL_BINARY_PATH
CMD java $JAVA_OPTS -jar $WSTUNNEL_BINARY_PATH server -p $PORT
