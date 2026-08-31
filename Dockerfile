FROM eclipse-temurin:17-jdk AS build

WORKDIR /app

COPY src/main/java/web/WebApplication.java src/main/java/web/WebApplication.java
COPY src/main/java/core/net/HattrickAPI.java src/main/java/core/net/HattrickAPI.java

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p lib classes \
    && curl -fsSL -o lib/scribejava-core-8.3.3.jar https://repo1.maven.org/maven2/com/github/scribejava/scribejava-core/8.3.3/scribejava-core-8.3.3.jar \
    && curl -fsSL -o lib/scribejava-java8-8.3.3.jar https://repo1.maven.org/maven2/com/github/scribejava/scribejava-java8/8.3.3/scribejava-java8-8.3.3.jar \
    && javac -cp "lib/*" -d classes src/main/java/core/net/HattrickAPI.java src/main/java/web/WebApplication.java

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/classes /app/classes
COPY --from=build /app/lib /app/lib

ENV PORT=10000

EXPOSE 10000

CMD ["java", "-cp", "/app/classes:/app/lib/*", "web.WebApplication"]
